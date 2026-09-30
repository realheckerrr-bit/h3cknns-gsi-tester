#!/usr/bin/env python3
"""Extract available kernel and ramdisk payloads from Android boot images."""

import argparse
import struct
from pathlib import Path


def uint32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def align(value: int, boundary: int) -> int:
    return ((value + boundary - 1) // boundary) * boundary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("boot_image", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    data = args.boot_image.read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    if data[:8] == b"VNDRBOOT":
        page_size = uint32(data, 12) or 4096
        ramdisk_size = uint32(data, 24)
        ramdisk_end = page_size + ramdisk_size
        if not ramdisk_size:
            return
        if ramdisk_end > len(data):
            raise SystemExit("vendor boot ramdisk exceeds the file boundary")
        (args.output / "ramdisk").write_bytes(data[page_size:ramdisk_end])
        return
    if data[:8] != b"ANDROID!":
        raise SystemExit("not an Android boot or vendor_boot image")
    header_version = uint32(data, 40)
    page_size = 4096 if header_version >= 3 else uint32(data, 36) or 2048
    kernel_size = uint32(data, 8)
    ramdisk_size = uint32(data, 12) if header_version >= 3 else uint32(data, 16)
    kernel_offset = page_size
    ramdisk_offset = align(kernel_offset + kernel_size, page_size)
    kernel_end = kernel_offset + kernel_size
    ramdisk_end = ramdisk_offset + ramdisk_size
    if kernel_end > len(data) or ramdisk_end > len(data):
        raise SystemExit("boot image payload exceeds the file boundary")
    if kernel_size:
        (args.output / "kernel").write_bytes(data[kernel_offset:kernel_end])
    if ramdisk_size:
        (args.output / "ramdisk").write_bytes(data[ramdisk_offset:ramdisk_end])


if __name__ == "__main__":
    main()
