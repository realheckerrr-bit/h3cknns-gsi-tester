#!/usr/bin/env python3
"""Patch all concatenated Ranchu initramfs archives for direct test disks."""

import gzip
import lzma
import struct
import sys


def align4(value):
    return (value + 3) & ~3


def decode_lz4(data):
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
                while block[position] == 255:
                    literal_length += 255
                    position += 1
                literal_length += block[position]
                position += 1
            output.extend(block[position:position + literal_length])
            position += literal_length
            if position == len(block):
                break
            match_offset = block[position] | (block[position + 1] << 8)
            position += 2
            match_length = token & 15
            if match_length == 15:
                while block[position] == 255:
                    match_length += 255
                    position += 1
                match_length += block[position]
                position += 1
            if match_offset == 0 or match_offset > len(output):
                raise ValueError("invalid legacy LZ4 match offset")
            start = len(output) - match_offset
            for index in range(match_length + 4):
                output.append(output[start + index])
    return bytes(output)


def encode_lz4(data):
    output = bytearray(b"\x02\x21\x4c\x18")
    for offset in range(0, len(data), 65536):
        chunk = data[offset:offset + 65536]
        literal_length = len(chunk)
        token = min(literal_length, 15) << 4
        block = bytearray([token])
        if literal_length >= 15:
            remaining = literal_length - 15
            while remaining >= 255:
                block.append(255)
                remaining -= 255
            block.append(remaining)
        block.extend(chunk)
        output.extend(struct.pack("<I", len(block)))
        output.extend(block)
    output.extend(struct.pack("<I", 0))
    return bytes(output)


def unpack(data):
    if data[:2] == b"\x1f\x8b":
        return gzip.decompress(data), "gzip"
    if data[:6] == b"\xfd7zXZ\x00":
        return lzma.decompress(data), "xz"
    if data[:4] == b"\x02\x21\x4c\x18":
        return decode_lz4(data), "lz4"
    return data, "raw"


def repack(data, compression):
    if compression == "gzip":
        return gzip.compress(data, compresslevel=9, mtime=0)
    if compression == "lz4":
        return encode_lz4(data)
    if compression == "xz":
        return lzma.compress(data, format=lzma.FORMAT_XZ)
    return data


def parse_archive(data, start):
    entries = []
    offset = start
    while offset + 110 <= len(data):
        header = data[offset:offset + 110]
        if header[:6] not in (b"070701", b"070702"):
            return None
        try:
            fields = [int(header[i:i + 8], 16) for i in range(6, 110, 8)]
        except ValueError:
            return None
        size = fields[6]
        namesize = fields[11]
        name_start = offset + 110
        name_end = name_start + namesize
        if namesize < 1 or name_end > len(data):
            return None
        name = data[name_start:name_end - 1].decode("utf-8", "replace")
        content_start = align4(name_end)
        content_end = content_start + size
        if content_end > len(data):
            return None
        offset = align4(content_end)
        if name == "TRAILER!!!":
            return entries, offset
        entries.append((header, name, data[content_start:content_end]))
    return None


def parse_cpio(data):
    entries = []
    cursor = 0
    while cursor < len(data):
        candidates = [position for position in (
            data.find(b"070701", cursor), data.find(b"070702", cursor)
        ) if position >= 0]
        if not candidates:
            break
        start = min(candidates)
        parsed = parse_archive(data, start)
        if parsed is None:
            cursor = start + 6
            continue
        archive_entries, end = parsed
        entries.extend(archive_entries)
        cursor = max(end, start + 6)
    if not entries:
        raise ValueError("no newc entries in Ranchu ramdisk")
    return entries


def build_cpio(entries):
    output = bytearray()
    for original, name, content in entries:
        header = bytearray(original)
        name_bytes = name.encode("utf-8") + b"\0"
        fields = [int(header[i:i + 8], 16) for i in range(6, 110, 8)]
        fields[6] = len(content)
        fields[11] = len(name_bytes)
        fields[12] = 0
        header[:6] = b"070701"
        for index, value in enumerate(fields):
            start = 6 + index * 8
            header[start:start + 8] = f"{value:08x}".encode("ascii")
        output.extend(header)
        output.extend(name_bytes)
        output.extend(b"\0" * (align4(len(output)) - len(output)))
        output.extend(content)
        output.extend(b"\0" * (align4(len(output)) - len(output)))
    trailer_name = b"TRAILER!!!\0"
    trailer = bytearray(b"070701" + b"00000000" * 13)
    trailer[6 + 11 * 8:6 + 12 * 8] = f"{len(trailer_name):08x}".encode("ascii")
    output.extend(trailer)
    output.extend(trailer_name)
    output.extend(b"\0" * (align4(len(output)) - len(output)))
    return bytes(output)


def make_header(name, content):
    name_bytes = name.encode("utf-8") + b"\0"
    fields = [0, 0o100644, 0, 0, 1, 0, len(content), 0, 0, 0, 0, len(name_bytes), 0]
    header = bytearray(b"070701" + b"00000000" * 13)
    for index, value in enumerate(fields):
        start = 6 + index * 8
        header[start:start + 8] = f"{value:08x}".encode("ascii")
    return bytes(header)


def patch_fstab(content):
    text = content.decode("utf-8", "replace")
    result = []
    changed = False
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
        if "first_stage_mount" not in stripped or "logical" not in stripped:
            result.append(line)
            continue
        if mountpoint == "/system":
            columns[0] = "/dev/block/vda"
        elif mountpoint == "/vendor":
            columns[0] = "/dev/block/vdb"
        else:
            changed = True
            continue
        for index, column in enumerate(columns):
            columns[index] = ",".join(value for value in column.split(",") if value not in ("logical", "avb=vbmeta"))
        ending = "\n" if line.endswith("\n") else ""
        result.append(" ".join(columns) + ending)
        changed = True
    return "".join(result).encode("utf-8"), changed


def main():
    source, target = sys.argv[1:3]
    raw, compression = unpack(open(source, "rb").read())
    entries = parse_cpio(raw)
    changed = False
    patched = []
    for header, name, content in entries:
        if name.rsplit("/", 1)[-1] in ("fstab.ranchu", "fstab.ranchu.initrd"):
            content, entry_changed = patch_fstab(content)
            changed |= entry_changed
        patched.append((header, name, content))
    if not changed:
        content = (
            b"/dev/block/vda /system ext4 ro wait,first_stage_mount\n"
            b"/dev/block/vdb1 /vendor ext4 ro wait,first_stage_mount\n"
        )
        first_archive = parse_archive(raw, 0)
        if first_archive is None:
            raise ValueError("could not locate the first Ranchu initramfs archive")
        first_entries, first_end = first_archive
        first_entries.append((make_header("fstab.ranchu", content), "fstab.ranchu", content))
        patched_raw = build_cpio(first_entries) + raw[first_end:]
        with open(target, "wb") as output:
            output.write(repack(patched_raw, compression))
        print("inserted direct-disk Ranchu initramfs fstab")
        return
    with open(target, "wb") as output:
        output.write(repack(build_cpio(patched), compression))
    print("patched Ranchu initramfs fstab")


if __name__ == "__main__":
    main()
