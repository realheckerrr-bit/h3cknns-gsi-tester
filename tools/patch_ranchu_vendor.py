#!/usr/bin/env python3
"""Patch Ranchu vendor fstab for separate direct system/vendor test disks."""

import re
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
    if mountpoint == "/metadata" or device.endswith("/metadata"):
        return b"#" + line[1:] if line[:1] != b"#" else line
    if "first_stage_mount" not in trimmed or "logical" not in trimmed:
        return line
    if mountpoint == "/system":
        columns[0] = "/dev/block/vda"
    elif mountpoint == "/vendor":
        columns[0] = "/dev/block/vdb"
    else:
        return b"#" + line[1:] if line[:1] != b"#" else line
    for index, column in enumerate(columns):
        columns[index] = ",".join(value for value in column.split(",") if value not in ("logical", "avb=vbmeta"))
    rebuilt = " ".join(columns).encode("utf-8")
    if len(rebuilt) > len(line):
        raise ValueError("patched fstab line is longer than its ext4 slot")
    return rebuilt + b" " * (len(line) - len(rebuilt))


def main():
    path = sys.argv[1]
    data = bytearray(open(path, "rb").read())
    changed = 0
    start = 0
    for end in range(len(data) + 1):
        if end != len(data) and data[end] != 10:
            continue
        original = bytes(data[start:end])
        updated = transform(original)
        if updated != original:
            data[start:end] = updated
            changed += 1
        start = end + 1
    if changed == 0:
        print("no Ranchu fstab entries needed patching")
        return
    with open(path, "wb") as output:
        output.write(data)
    print(f"patched {changed} Ranchu vendor fstab lines")


if __name__ == "__main__":
    main()
