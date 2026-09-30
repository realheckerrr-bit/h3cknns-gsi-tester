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
        header_version = uint32(data, 8)
        header_size = uint32(data, 2096) or (page_size)
        section_start = align(header_size, page_size)
        if not ramdisk_size:
            return
        section_end = section_start + ramdisk_size
        if section_end > len(data):
            raise SystemExit("vendor boot ramdisk exceeds the file boundary")
        if header_version >= 4 and len(data) >= 2128:
            dtb_size = uint32(data, 2100)
            table_size = uint32(data, 2112)
            entry_count = uint32(data, 2116)
            entry_size = uint32(data, 2120)
            table_start = align(section_end, page_size)
            table_start = align(table_start + dtb_size, page_size)
            fragments = []
            if entry_size >= 12 and entry_count and table_start + table_size <= len(data):
                for index in range(entry_count):
                    entry = table_start + index * entry_size
                    if entry + 12 > table_start + table_size:
                        break
                    fragment_size = uint32(data, entry)
                    fragment_offset = uint32(data, entry + 4)
                    fragment_end = section_start + fragment_offset + fragment_size
                    if fragment_end > section_end:
                        raise SystemExit("vendor ramdisk table entry exceeds the section")
                    if fragment_size:
                        fragments.append(data[section_start + fragment_offset:fragment_end])
            if fragments:
                (args.output / "ramdisk").write_bytes(b"".join(fragments))
                return
        (args.output / "ramdisk").write_bytes(data[section_start:section_end])
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
