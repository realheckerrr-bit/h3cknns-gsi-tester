#!/usr/bin/env python3
"""Patch Ranchu vendor fstab for separate direct system/vendor test disks."""

import re
import mmap
import sys


def transform(line):
    text = line.decode("utf-8", "ignore")
    trimmed = text.strip()
    if not trimmed or trimmed.startswith("#"):
        return line
    columns = re.split(r"\s+", trimmed)
    if len(columns) < 2:
        return line
    device, mountpoint = columns[0], columns[1]
    if trimmed.startswith("file ") and "/dev/block/by-name/" in trimmed:
        return b"#" + line[1:] if line[:1] != b"#" else line
    if mountpoint in ("/metadata", "/data") or device.endswith("/metadata"):
        columns[0] = "/dev/block/vdc"
        if len(columns) > 2 and columns[2] in ("f2fs", "erofs"):
            columns[2] = "ext4"
        if len(columns) > 3:
            allowed = {"noatime", "nosuid", "nodev", "errors=panic"}
            columns[3] = ",".join(flag for flag in columns[3].split(",") if flag in allowed)
            if not columns[3]:
                columns[3] = "defaults"
        if len(columns) > 4:
            columns[4] = "wait"
            columns = columns[:5]
        if mountpoint == "/metadata" or device.endswith("/metadata"):
            if len(columns) > 4:
                columns[4] = "wait,first_stage_mount"
        rebuilt = " ".join(columns).encode("utf-8")
        if len(rebuilt) > len(line):
            raise ValueError("patched data/metadata fstab line is longer than its ext4 slot")
        return rebuilt + b" " * (len(line) - len(rebuilt))
    if "first_stage_mount" not in trimmed or "logical" not in trimmed:
        return line
    if mountpoint in ("/system", "/vendor") and len(columns) > 2 and columns[2] == "erofs":
        return b"#" + line[1:] if line[:1] != b"#" else line
    if mountpoint == "/system":
        columns[0] = "/dev/block/vdb"
    elif mountpoint == "/vendor":
        columns[0] = "/dev/block/vda"
    else:
        return b"#" + line[1:] if line[:1] != b"#" else line
    for index, column in enumerate(columns):
        columns[index] = ",".join(value for value in column.split(",") if value not in ("logical", "avb=vbmeta"))
    rebuilt = " ".join(columns).encode("utf-8")
    if len(rebuilt) > len(line):
        raise ValueError("patched fstab line is longer than its ext4 slot")
    return rebuilt + b" " * (len(line) - len(rebuilt))


def main():
    for path in sys.argv[1:]:
        changed = 0
        with open(path, "r+b") as file, mmap.mmap(file.fileno(), 0, access=mmap.ACCESS_WRITE) as image:
            line_starts = set()
            for needle in (b"logical", b"/metadata", b"super", b"/dev/block/by-name/"):
                cursor = 0
                while True:
                    match = image.find(needle, cursor)
                    if match < 0:
                        break
                    start = image.rfind(b"\n", 0, match) + 1
                    line_starts.add(start)
                    cursor = match + len(needle)
            for start in sorted(line_starts):
                end = image.find(b"\n", start)
                if end < 0:
                    end = len(image)
                original = image[start:end]
                if len(original) < 512 and all(byte in (9, 10, 13) or 32 <= byte < 127 for byte in original):
                    if b"super" in original.lower() or b"metadata" in original.lower():
                        print(f"{path}: candidate {original.rstrip()!r}")
                updated = transform(original)
                if updated != original:
                    if len(updated) != len(original):
                        raise ValueError("patched fstab line changed ext4 file size")
                    image[start:end] = updated
                    changed += 1
                    print(f"{path}: {original.rstrip()!r} -> {updated.rstrip()!r}")
            image.flush()
        print(f"{path}: patched {changed} Ranchu vendor fstab lines")


if __name__ == "__main__":
    main()
