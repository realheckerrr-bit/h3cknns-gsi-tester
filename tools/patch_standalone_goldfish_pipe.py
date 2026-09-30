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
    helpers = """static GoldfishHostPipe* standalone_guest_open(GoldfishHwPipe* hw_pipe) {
    return (GoldfishHostPipe*)hw_pipe;
}
static void standalone_guest_close(GoldfishHostPipe* host_pipe,
                                   GoldfishPipeCloseReason reason) {
    (void)host_pipe; (void)reason;
}
static GoldfishPipePollFlags standalone_guest_poll(GoldfishHostPipe* host_pipe) {
    (void)host_pipe;
    return GOLDFISH_PIPE_POLL_OUT;
}
static int standalone_guest_recv(GoldfishHostPipe* host_pipe,
                                 GoldfishPipeBuffer* buffers,
                                 int num_buffers) {
    (void)host_pipe; (void)buffers; (void)num_buffers;
    return GOLDFISH_PIPE_ERROR_AGAIN;
}
static int standalone_guest_send(GoldfishHostPipe** host_pipe,
                                 const GoldfishPipeBuffer* buffers,
                                 int num_buffers) {
    (void)host_pipe;
    int total = 0;
    for (int i = 0; i < num_buffers; ++i) {
        const unsigned char* data = (const unsigned char*)buffers[i].data;
        fprintf(stdout, "standalone goldfish pipe send (%zu bytes): ", buffers[i].size);
        for (size_t j = 0; j < buffers[i].size && j < 160; ++j) {
            unsigned char c = data[j];
            fputc(c >= 32 && c < 127 ? c : '.', stdout);
        }
        fputc('\\n', stdout);
        total += (int)buffers[i].size;
    }
    fflush(stdout);
    return total;
}
static void standalone_guest_wait(GoldfishHostPipe* host_pipe) {
    (void)host_pipe;
}
static void standalone_guest_wake(GoldfishHostPipe* host_pipe,
                                  GoldfishPipeWakeFlags wake_flags) {
    (void)host_pipe; (void)wake_flags;
}

static void null_dma_add_buffer(void* pipe, uint64_t paddr, uint64_t sz) {
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

    old = """    .guest_open = null_guest_open,
    .guest_load = null_guest_load,
    .guest_pre_load = null_guest_pre_post_save_load,
    .guest_post_load = null_guest_pre_post_save_load,
    .guest_pre_save = null_guest_pre_post_save_load,
    .guest_post_save = null_guest_pre_post_save_load,
};"""
    new = """    .guest_open = standalone_guest_open,
    .guest_open_with_flags = standalone_guest_open,
    .guest_close = standalone_guest_close,
    .guest_load = null_guest_load,
    .guest_pre_load = null_guest_pre_post_save_load,
    .guest_post_load = null_guest_pre_post_save_load,
    .guest_pre_save = null_guest_pre_post_save_load,
    .guest_post_save = null_guest_pre_post_save_load,
    .guest_poll = standalone_guest_poll,
    .guest_recv = standalone_guest_recv,
    .wait_guest_recv = standalone_guest_wait,
    .guest_send = standalone_guest_send,
    .wait_guest_send = standalone_guest_wait,
    .guest_wake_on = standalone_guest_wake,"""
    new += """\n    .dma_add_buffer = null_dma_add_buffer,
    .dma_remove_buffer = null_dma_remove_buffer,
    .dma_invalidate_host_mappings = null_dma_invalidate_host_mappings,
    .dma_reset_host_mappings = null_dma_reset_host_mappings,
    .dma_save_mappings = null_dma_save_mappings,
    .dma_load_mappings = null_dma_load_mappings,
};"""
    if old not in text:
        raise SystemExit("GoldfishPipe null-service initializer not found")
    text = text.replace(old, new, 1)
    path.write_text(text)


if __name__ == "__main__":
    main()
