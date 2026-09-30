#!/usr/bin/env python3
"""Enable Ranchu SMP/PSCI when the engine is running with TCG."""

from pathlib import Path
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_ranchu_smp.py path/to/hw/arm/ranchu.c")
    path = Path(sys.argv[1])
    text = path.read_text()
    old_psci = "    /* No PSCI for TCG yet */\n    if (kvm_enabled()) {"
    new_psci = (
        "    /* Android GSI testing runs Ranchu with TCG on ARM64 hosts. "
        "Expose PSCI so secondary vCPUs can start there too. */\n"
        "    if (true) {"
    )
    if old_psci not in text:
        raise SystemExit("Ranchu TCG PSCI guard was not found")
    text = text.replace(old_psci, new_psci, 1)
    old_max_cpus = "    .max_cpus = 1,"
    if old_max_cpus not in text:
        raise SystemExit("Ranchu max CPU limit was not found")
    text = text.replace(old_max_cpus, "    .max_cpus = 8,", 1)
    path.write_text(text)


if __name__ == "__main__":
    main()
