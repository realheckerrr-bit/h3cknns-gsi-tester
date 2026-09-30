#!/usr/bin/env python3
"""Print logical partition names and allocated sizes from an Android super image."""

import sys
from pathlib import Path

from extract_logical_partition import (
    REAL_METADATA_BASE,
    LEGACY_METADATA_BASE,
    read_at,
    read_geometry,
    load_metadata,
    u32,
    u64,
)


def main() -> None:
    path = Path(sys.argv[1])
    with path.open("rb") as source:
        try:
            geometry = read_at(source, 4096, 52)
            if u32(geometry, 0) != 0x616C4467:
                raise ValueError
            metadata_base = REAL_METADATA_BASE
        except ValueError:
            geometry = read_geometry(source)
            metadata_base = LEGACY_METADATA_BASE

        for slot in range(u32(geometry, 44)):
            table_base, partitions, extents = load_metadata(source, geometry, metadata_base, slot)
            partition_offset, partition_count, partition_size = partitions
            extent_offset, extent_count, extent_size = extents
            for index in range(partition_count):
                entry = read_at(source, table_base + partition_offset + index * partition_size, partition_size)
                name = entry[:36].split(b"\0", 1)[0].decode("utf-8", "replace")
                first_extent = u32(entry, 40)
                partition_extents = u32(entry, 44)
                total = 0
                for extent_index in range(partition_extents):
                    extent = read_at(source, table_base + extent_offset
                                     + (first_extent + extent_index) * extent_size, extent_size)
                    total += u64(extent, 0) * 512
                print(f"slot={slot} name={name} first={first_extent} extents={partition_extents} bytes={total}")


if __name__ == "__main__":
    main()
