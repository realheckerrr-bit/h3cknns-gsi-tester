#!/usr/bin/env python3
"""Merge generic and vendor Android newc ramdisks into one gzip archive."""

import argparse
import gzip
import lzma
import struct
import subprocess
import sys
from collections import OrderedDict
from pathlib import Path


def align4(value: int) -> int:
    return (value + 3) & ~3


def decompress(data: bytes, name: str) -> bytes:
    if data[:6] in (b"070701", b"070702"):
        return data
    if data.startswith(b"\x1f\x8b"):
        return gzip.decompress(data)
    if data.startswith(b"\xfd7zXZ\x00"):
        return lzma.decompress(data)
    try:
        decoded = decode_legacy_lz4(data)
        if decoded[:6] in (b"070701", b"070702"):
            return decoded
    except ValueError:
        pass
    # Android boot images sometimes omit the .lz4 suffix and vendor tools
    # have emitted more than one legacy LZ4 frame signature. Try the host
    # decoder for any non-CPIO payload before declaring the ramdisk invalid.
    try:
        decoded = subprocess.run(["lz4", "-d", "-l", "-c"], input=data, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, check=True).stdout
        if decoded[:6] in (b"070701", b"070702"):
            return decoded
    except (OSError, subprocess.CalledProcessError):
        pass
    return data


def decode_legacy_lz4(data: bytes) -> bytes:
    """Decode Android's block-framed legacy LZ4 ramdisk format."""
    magic = b"\x02!L\x18"
    output = bytearray()
    offset = 0
    found = False
    while offset + 8 <= len(data):
        start = data.find(magic, offset)
        if start < 0:
            break
        found = True
        offset = start + 4
        while offset + 4 <= len(data):
            block_size = struct.unpack_from("<I", data, offset)[0]
            offset += 4
            if block_size == 0:
                break
            raw = bool(block_size & 0x80000000)
            size = block_size & 0x7FFFFFFF
            if size > len(data) - offset:
                raise ValueError("truncated legacy LZ4 block")
            if raw:
                output.extend(data[offset:offset + size])
            else:
                decode_lz4_block(data, offset, size, output)
            offset += size
    if not found or not output:
        raise ValueError("not a legacy LZ4 stream")
    return bytes(output)


def decode_lz4_block(data: bytes, offset: int, size: int, output: bytearray) -> None:
    end = offset + size
    position = offset
    while position < end:
        token = data[position]
        position += 1
        literal_length = token >> 4
        if literal_length == 15:
            literal_length += read_lz4_length(data, end, position)
            position = decode_lz4_block.cursor
        if literal_length > end - position:
            raise ValueError("truncated legacy LZ4 literals")
        output.extend(data[position:position + literal_length])
        position += literal_length
        if position == end:
            break
        if position + 2 > end:
            raise ValueError("truncated legacy LZ4 match")
        match_offset = data[position] | (data[position + 1] << 8)
        position += 2
        if match_offset == 0 or match_offset > len(output):
            raise ValueError("invalid legacy LZ4 match offset")
        match_length = token & 15
        if match_length == 15:
            match_length += read_lz4_length(data, end, position)
            position = decode_lz4_block.cursor
        match_length += 4
        start = len(output) - match_offset
        for index in range(match_length):
            output.append(output[start + index % match_offset])


def read_lz4_length(data: bytes, end: int, position: int) -> int:
    length = 0
    while position < end:
        value = data[position]
        position += 1
        length += value
        if value != 255:
            decode_lz4_block.cursor = position
            return length
    raise ValueError("truncated legacy LZ4 length")


decode_lz4_block.cursor = 0


def parse(data: bytes):
    entries = []
    position = 0
    while position + 110 <= len(data):
        magic = data[position:position + 6]
        if magic not in (b"070701", b"070702"):
            raise ValueError("ramdisk is not a newc CPIO archive")
        fields = [int(data[position + 6 + index * 8:position + 14 + index * 8], 16)
                  for index in range(13)]
        name_size = fields[11]
        content_size = fields[6]
        name_start = position + 110
        name_end = name_start + name_size
        content_start = align4(name_end)
        content_end = content_start + content_size
        if name_size < 1 or content_end > len(data):
            raise ValueError("truncated newc CPIO entry")
        name = data[name_start:name_end - 1].decode("utf-8")
        if name == "TRAILER!!!":
            break
        entries.append((magic, fields, name, data[content_start:content_end]))
        position = align4(content_end)
    if not entries:
        raise ValueError("ramdisk contains no newc CPIO entries")
    return entries


def serialize(entries) -> bytes:
    output = bytearray()
    for magic, original, name, content in entries:
        fields = list(original)
        name_bytes = name.encode("utf-8") + b"\x00"
        fields[6] = len(content)
        fields[11] = len(name_bytes)
        fields[12] = sum(content) & 0xFFFFFFFF if magic == b"070702" else 0
        output.extend(magic)
        output.extend(b"".join(f"{field:08x}".encode("ascii") for field in fields))
        output.extend(name_bytes)
        output.extend(b"\x00" * ((-len(output)) & 3))
        output.extend(content)
        output.extend(b"\x00" * ((-len(output)) & 3))
    trailer = b"TRAILER!!!\x00"
    fields = [0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, len(trailer), 0]
    output.extend(b"070701")
    output.extend(b"".join(f"{field:08x}".encode("ascii") for field in fields))
    output.extend(trailer)
    output.extend(b"\x00" * ((-len(output)) & 3))
    return bytes(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("generic", type=Path)
    parser.add_argument("vendor", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    merged = OrderedDict()
    for source in (args.generic, args.vendor):
        encoded = source.read_bytes()
        expanded = decompress(encoded, source.name)
        print(f"{source}: encoded={len(encoded)} expanded={len(expanded)} "
              f"magic={expanded[:16].hex()}", file=sys.stderr)
        entries = parse(expanded)
        for entry in entries:
            merged[entry[2]] = entry
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(gzip.compress(serialize(list(merged.values())), mtime=0))


if __name__ == "__main__":
    main()
