#!/usr/bin/env python3
"""Make standalone Ranchu QEMU safe without the Android emulator frontend."""

from pathlib import Path
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} goldfish_pipe.c")

    path = Path(sys.argv[1])
    text = path.read_text()
    marker = "static const GoldfishPipeServiceOps s_null_service_ops = {"
    helpers = """static void null_dma_add_buffer(void* pipe, uint64_t paddr, uint64_t sz) {
    (void)pipe; (void)paddr; (void)sz;
}
static void null_dma_remove_buffer(uint64_t paddr) { (void)paddr; }
static void null_dma_invalidate_host_mappings(void) {}
static void null_dma_reset_host_mappings(void) {}
static void null_dma_save_mappings(QEMUFile* file) { (void)file; }
static void null_dma_load_mappings(QEMUFile* file) { (void)file; }

"""
    if marker not in text:
        raise SystemExit("GoldfishPipe null-service marker not found")
    text = text.replace(marker, helpers + marker, 1)

    old = """    .guest_post_save = null_guest_pre_post_save_load,
};"""
    new = """    .guest_post_save = null_guest_pre_post_save_load,
    .dma_add_buffer = null_dma_add_buffer,
    .dma_remove_buffer = null_dma_remove_buffer,
    .dma_invalidate_host_mappings = null_dma_invalidate_host_mappings,
    .dma_reset_host_mappings = null_dma_reset_host_mappings,
    .dma_save_mappings = null_dma_save_mappings,
    .dma_load_mappings = null_dma_load_mappings,
};"""
    if old not in text:
        raise SystemExit("GoldfishPipe null-service initializer not found")
    path.write_text(text.replace(old, new, 1))


if __name__ == "__main__":
    main()
