#!/usr/bin/env python3
"""Extract one linear logical partition from an Android super image."""

import argparse
import struct
from pathlib import Path


SECTOR_SIZE = 512
GEOMETRY_SIZE = 4096
REAL_METADATA_BASE = 4096 + (GEOMETRY_SIZE * 2)
LEGACY_METADATA_BASE = 4096 + GEOMETRY_SIZE
GEOMETRY_MAGIC = 0x616C4467
METADATA_MAGIC = 0x414C5030
LINEAR_EXTENT = 0
ZERO_EXTENT = 1


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def u64(data: bytes, offset: int) -> int:
    return struct.unpack_from("<Q", data, offset)[0]


def read_at(source, offset: int, size: int) -> bytes:
    if offset < 0:
        raise ValueError("negative super-image offset")
    source.seek(offset)
    value = source.read(size)
    if len(value) != size:
        raise ValueError("truncated super-image metadata")
    return value


def read_geometry(source):
    try:
        geometry = read_at(source, 4096, 52)
        if u32(geometry, 0) == GEOMETRY_MAGIC:
            return geometry
    except ValueError:
        pass
    geometry = read_at(source, 0, 52)
    if u32(geometry, 0) != GEOMETRY_MAGIC:
        raise ValueError("invalid super-image geometry magic")
    return geometry


def load_metadata(source, geometry, metadata_base: int, slot: int):
    metadata_max_size = u32(geometry, 40)
    slot_count = u32(geometry, 44)
    if not metadata_max_size or slot < 0 or slot >= slot_count:
        raise ValueError("invalid super-image metadata geometry")

    metadata_offset = metadata_base + slot * metadata_max_size
    header = read_at(source, metadata_offset, 256)
    if u32(header, 0) != METADATA_MAGIC:
        raise ValueError("invalid super-image metadata magic")
    header_size = u32(header, 8)
    tables_size = u32(header, 44)
    if header_size < 128 or header_size + tables_size > metadata_max_size:
        raise ValueError("invalid super-image metadata size")

    partition_table = (u32(header, 80), u32(header, 84), u32(header, 88))
    extent_table = (u32(header, 92), u32(header, 96), u32(header, 100))
    if partition_table[2] < 52 or extent_table[2] < 24:
        raise ValueError("unsupported super-image table entry size")
    table_base = metadata_offset + header_size
    return table_base, partition_table, extent_table


def find_partition(source, metadata, requested: str):
    table_base, partitions, extents = metadata
    partition_offset, partition_count, partition_size = partitions
    fallback = None
    exact = None
    for index in range(partition_count):
        entry = read_at(source, table_base + partition_offset + index * partition_size, partition_size)
        name = entry[:36].split(b"\0", 1)[0].decode("utf-8", "replace")
        first_extent = u32(entry, 40)
        extent_count = u32(entry, 44)
        value = (first_extent, extent_count)
        if name == requested:
            exact = value
        if requested == "vendor" and name in ("vendor_a", "vendor_b") and extent_count != 0:
            fallback = value
        if requested == "system" and name in ("system_a", "system_b") and extent_count != 0:
            fallback = value
    # Some dynamic-partition images carry an empty unsuffixed compatibility
    # entry alongside the populated slot partition. Prefer the slot entry
    # when the exact entry has no extents.
    if exact is not None and exact[1] != 0:
        return exact
    return fallback if fallback is not None else exact


def extract(source_path: Path, requested: str, output_path: Path) -> None:
    with source_path.open("rb") as source:
        metadata = None
        partition = None
        geometry_offset = 4096
        try:
            geometry = read_at(source, geometry_offset, 52)
            if u32(geometry, 0) != GEOMETRY_MAGIC:
                raise ValueError("primary geometry is not present")
            metadata_base = REAL_METADATA_BASE
        except ValueError:
            geometry_offset = 0
            geometry = read_geometry(source)
            metadata_base = LEGACY_METADATA_BASE
        slots = u32(geometry, 44)
        for slot in range(slots):
            candidate = load_metadata(source, geometry, metadata_base, slot)
            partition = find_partition(source, candidate, requested)
            if partition is not None:
                metadata = candidate
                break
        if metadata is None or partition is None:
            raise ValueError(f"super image has no {requested} logical partition")

        _, _, extents = metadata
        extent_offset, _, extent_size = extents
        first_extent, extent_count = partition
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("wb") as output:
            for index in range(extent_count):
                extent = read_at(source, metadata[0] + extent_offset
                                  + (first_extent + index) * extent_size, extent_size)
                sectors = u64(extent, 0)
                extent_type = u32(extent, 8)
                length = sectors * SECTOR_SIZE
                if extent_type == LINEAR_EXTENT:
                    source_offset = u64(extent, 12) * SECTOR_SIZE
                    source.seek(source_offset)
                    remaining = length
                    while remaining:
                        chunk = source.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise ValueError("logical partition extent exceeds super image")
                        output.write(chunk)
                        remaining -= len(chunk)
                elif extent_type == ZERO_EXTENT:
                    zero = b"\0" * min(1024 * 1024, length)
                    remaining = length
                    while remaining:
                        chunk = min(len(zero), remaining)
                        output.write(zero[:chunk])
                        remaining -= chunk
                else:
                    raise ValueError(f"unsupported logical extent type {extent_type}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("super_image", type=Path)
    parser.add_argument("partition")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    extract(args.super_image, args.partition, args.output)


if __name__ == "__main__":
    main()
