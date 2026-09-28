#!/usr/bin/env python3
"""Patch the stock Ranchu initramfs for direct system/vendor test disks."""

import gzip
import lzma
import struct
import sys


def align4(value):
    return (value + 3) & ~3


def unpack_image(data):
    if data[:2] == b"\x1f\x8b":
        return gzip.decompress(data), "gzip"
    if data[:6] == b"\xfd7zXZ\x00":
        return lzma.decompress(data), "xz"
    if data[:4] == b"\x02\x21\x4c\x18":
        return decode_legacy_lz4(data), "lz4"
    return data, "raw"


def pack_image(data, compression):
    if compression == "gzip":
        return gzip.compress(data, compresslevel=9, mtime=0)
    if compression == "xz":
        return lzma.compress(data, format=lzma.FORMAT_XZ)
    if compression == "lz4":
        return gzip.compress(data, compresslevel=9, mtime=0)
    return data


def decode_legacy_lz4(data):
    output = bytearray()
    offset = 4
    while offset + 4 <= len(data):
        block_size = struct.unpack_from("<I", data, offset)[0]
        offset += 4
        if block_size == 0:
            break
        block = data[offset:offset + block_size]
        offset += block_size
        position = 0
        while position < len(block):
            token = block[position]
            position += 1
            literal_length = token >> 4
            if literal_length == 15:
                while position < len(block) and block[position] == 255:
                    literal_length += 255
                    position += 1
                literal_length += block[position]
                position += 1
            output.extend(block[position:position + literal_length])
            position += literal_length
            if position == len(block):
                break
            if position + 2 > len(block):
                raise ValueError("truncated legacy LZ4 match")
            match_offset = block[position] | (block[position + 1] << 8)
            position += 2
            match_length = token & 15
            if match_length == 15:
                while position < len(block) and block[position] == 255:
                    match_length += 255
                    position += 1
                match_length += block[position]
                position += 1
            match_length += 4
            if match_offset == 0 or match_offset > len(output):
                raise ValueError("invalid legacy LZ4 match offset")
            start = len(output) - match_offset
            for index in range(match_length):
                output.append(output[start + index])
    return bytes(output)


def parse_cpio(data):
    entries = []
    offset = 0
    while offset + 110 <= len(data):
        header = data[offset:offset + 110]
        if header[:6] not in (b"070701", b"070702"):
            raise ValueError("ramdisk is not a newc cpio archive")
        fields = [int(header[i:i + 8], 16) for i in range(14, 110, 8)]
        size = fields[6]
        namesize = fields[11]
        name_start = offset + 110
        name_end = name_start + namesize
        name = data[name_start:name_end - 1].decode("utf-8", "replace")
        content_start = align4(name_end)
        content_end = content_start + size
        if content_end > len(data):
            raise ValueError("truncated cpio entry")
        if name == "TRAILER!!!":
            break
        entries.append((header, name, data[content_start:content_end]))
        offset = align4(content_end)
    if not entries:
        raise ValueError("empty cpio ramdisk")
    return entries


def build_cpio(entries):
    output = bytearray()
    for original_header, name, content in entries:
        header = bytearray(original_header)
        name_bytes = name.encode("utf-8") + b"\0"
        fields = [int(header[i:i + 8], 16) for i in range(14, 110, 8)]
        fields[6] = len(content)
        fields[11] = len(name_bytes)
        fields[12] = 0
        header[:6] = b"070701"
        for index, value in enumerate(fields):
            start = 14 + index * 8
            header[start:start + 8] = f"{value:08x}".encode("ascii")
        output.extend(header)
        output.extend(name_bytes)
        output.extend(b"\0" * (align4(len(output)) - len(output)))
        output.extend(content)
        output.extend(b"\0" * (align4(len(output)) - len(output)))
    trailer_name = b"TRAILER!!!\0"
    trailer = bytearray(b"070701" + b"00000000" * 13)
    trailer[14 + 11 * 8:14 + 12 * 8] = f"{len(trailer_name):08x}".encode("ascii")
    output.extend(trailer)
    output.extend(trailer_name)
    output.extend(b"\0" * (align4(len(output)) - len(output)))
    return bytes(output)


def patch_fstab(content):
    text = content.decode("utf-8", "replace")
    changed = False
    result = []
    for line in text.splitlines(keepends=True):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            result.append(line)
            continue
        columns = stripped.split()
        if len(columns) < 2:
            result.append(line)
            continue
        device, mountpoint = columns[0], columns[1]
        if mountpoint == "/metadata" or device.endswith("/metadata"):
            changed = True
            continue
        if "first_stage_mount" in stripped and "logical" in stripped:
            if mountpoint == "/system":
                columns[0] = "/dev/block/vda"
                changed = True
            elif mountpoint == "/vendor":
                columns[0] = "/dev/block/vdb"
                changed = True
            else:
                changed = True
                continue
            rebuilt = []
            for column in columns:
                values = [value for value in column.split(",") if value not in ("logical", "avb=vbmeta")]
                rebuilt.append(",".join(values))
            line_ending = "\n" if line.endswith("\n") else ""
            line = " ".join(rebuilt) + line_ending
        result.append(line)
    return "".join(result).encode("utf-8"), changed


def main():
    source, target = sys.argv[1:3]
    raw, compression = unpack_image(open(source, "rb").read())
    print(f"decoded ramdisk: compression={compression} size={len(raw)} prefix={raw[:32].hex()}")
    entries = parse_cpio(raw)
    changed = False
    patched = []
    for header, name, content in entries:
        if name.rsplit("/", 1)[-1] in ("fstab.ranchu.initrd", "fstab.ranchu"):
            content, entry_changed = patch_fstab(content)
            changed = changed or entry_changed
        patched.append((header, name, content))
    if not changed:
        raise SystemExit("no Ranchu fstab entries were changed")
    with open(target, "wb") as output:
        output.write(pack_image(build_cpio(patched), compression))


if __name__ == "__main__":
    main()
