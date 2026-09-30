#!/usr/bin/env python3
"""Merge generic and vendor Android newc ramdisks into one gzip archive."""

import argparse
import gzip
import lzma
import struct
import subprocess
from collections import OrderedDict
from pathlib import Path


def align4(value: int) -> int:
    return (value + 3) & ~3


def decompress(data: bytes, name: str) -> bytes:
    if data.startswith(b"\x1f\x8b"):
        return gzip.decompress(data)
    if data.startswith(b"\xfd7zXZ\x00"):
        return lzma.decompress(data)
    if data[:4] == b"\x02!L\x18" or name.lower().endswith(".lz4"):
        return subprocess.run(["lz4", "-d", "-c"], input=data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True).stdout
    return data


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
        entries = parse(decompress(source.read_bytes(), source.name))
        for entry in entries:
            merged[entry[2]] = entry
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(gzip.compress(serialize(list(merged.values())), mtime=0))


if __name__ == "__main__":
    main()
