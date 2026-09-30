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
    helpers = """typedef struct StandalonePipeState {
    uint32_t service;
    uint64_t process_id;
    bool process_id_ready;
} StandalonePipeState;

static uint64_t standalone_next_process_id = 1;

static StandalonePipeState* standalone_state(GoldfishHostPipe* host_pipe) {
    return (StandalonePipeState*)host_pipe;
}

static GoldfishHostPipe* standalone_guest_open(GoldfishHwPipe* hw_pipe) {
    (void)hw_pipe;
    StandalonePipeState* state = (StandalonePipeState*)calloc(1, sizeof(*state));
    if (state == NULL) return NULL;
    state->process_id = standalone_next_process_id++;
    return (GoldfishHostPipe*)state;
}
static GoldfishHostPipe* standalone_guest_open_with_flags(
        GoldfishHwPipe* hw_pipe, uint32_t flags) {
    (void)flags;
    return standalone_guest_open(hw_pipe);
}
static void standalone_guest_close(GoldfishHostPipe* host_pipe,
                                   GoldfishPipeCloseReason reason) {
    (void)reason;
    free(standalone_state(host_pipe));
}
static GoldfishPipePollFlags standalone_guest_poll(GoldfishHostPipe* host_pipe) {
    StandalonePipeState* state = standalone_state(host_pipe);
    GoldfishPipePollFlags flags = GOLDFISH_PIPE_POLL_OUT;
    if (state != NULL && state->process_id_ready) flags |= GOLDFISH_PIPE_POLL_IN;
    return flags;
}
static int standalone_guest_recv(GoldfishHostPipe* host_pipe,
                                 GoldfishPipeBuffer* buffers,
                                 int num_buffers) {
    StandalonePipeState* state = standalone_state(host_pipe);
    if (state == NULL || !state->process_id_ready) return GOLDFISH_PIPE_ERROR_AGAIN;
    size_t available = 0;
    for (int i = 0; i < num_buffers; ++i) available += buffers[i].size;
    if (available < sizeof(state->process_id)) return GOLDFISH_PIPE_ERROR_INVAL;
    size_t copied = 0;
    for (int i = 0; i < num_buffers && copied < sizeof(state->process_id); ++i) {
        size_t count = buffers[i].size;
        if (count > sizeof(state->process_id) - copied) count = sizeof(state->process_id) - copied;
        memcpy((unsigned char*)buffers[i].data, ((const unsigned char*)&state->process_id) + copied, count);
        copied += count;
    }
    state->process_id_ready = false;
    return (int)sizeof(state->process_id);
}
static int standalone_guest_send(GoldfishHostPipe** host_pipe,
                                 const GoldfishPipeBuffer* buffers,
                                 int num_buffers) {
    StandalonePipeState* state = standalone_state(*host_pipe);
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
        if (state != NULL && state->service == 0 && buffers[i].size >= 19
                && memcmp(data, "pipe:GLProcessPipe", 19) == 0) {
            state->service = 1;
        } else if (state != NULL && state->service == 1 && buffers[i].size >= 4) {
            int32_t confirm = 0;
            memcpy(&confirm, data, sizeof(confirm));
            if (confirm == 100) state->process_id_ready = true;
        }
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
    .guest_open_with_flags = standalone_guest_open_with_flags,
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
