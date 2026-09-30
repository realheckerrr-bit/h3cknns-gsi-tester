// Bridge QEMU's GoldfishPipeServiceOps to the AndroidPipe implementation
// exported by libgfxstream_backend.so.

#include <dlfcn.h>

#include <stddef.h>
#include <stdint.h>

extern "C" {
#include "host-common/goldfish_pipe.h"
}

namespace {

using GuestOpen = GoldfishHostPipe* (*)(GoldfishHwPipe*);
using GuestOpenWithFlags = GoldfishHostPipe* (*)(GoldfishHwPipe*, uint32_t);
using GuestClose = void (*)(GoldfishHostPipe*, int);
using GuestPoll = GoldfishPipePollFlags (*)(GoldfishHostPipe*);
using GuestRecv = int (*)(GoldfishHostPipe*, GoldfishPipeBuffer*, int);
using GuestWait = void (*)(GoldfishHostPipe*);
using GuestSend = int (*)(GoldfishHostPipe**, const GoldfishPipeBuffer*, int);
using GuestWakeOn = void (*)(GoldfishHostPipe*, unsigned);
using InitThreading = void (*)(void*);

struct PipeFunctions {
    GuestOpen open = nullptr;
    GuestOpenWithFlags openWithFlags = nullptr;
    GuestClose close = nullptr;
    GuestPoll poll = nullptr;
    GuestRecv recv = nullptr;
    GuestWait waitRecv = nullptr;
    GuestSend send = nullptr;
    GuestWait waitSend = nullptr;
    GuestWakeOn wakeOn = nullptr;
    InitThreading initThreading = nullptr;
};

PipeFunctions s_pipe;

// AndroidPipe only needs a valid VmLock interface. QEMU's standalone engine
// has no Android emulator frontend lock, so a no-op implementation is the
// correct equivalent for the single VM process used by this app.
struct NoOpVmLock {
    virtual ~NoOpVmLock() = default;
    virtual void lock() {}
    virtual void unlock() {}
    virtual bool isLockedBySelf() const { return true; }
};

template <typename T>
bool load(void* library, const char* symbol, T* target) {
    *target = reinterpret_cast<T>(dlsym(library, symbol));
    return *target != nullptr;
}

static GoldfishHostPipe* guestOpen(GoldfishHwPipe* pipe) {
    return s_pipe.open ? s_pipe.open(pipe) : nullptr;
}

static GoldfishHostPipe* guestOpenWithFlags(GoldfishHwPipe* pipe, uint32_t flags) {
    return s_pipe.openWithFlags ? s_pipe.openWithFlags(pipe, flags) : nullptr;
}

static void guestClose(GoldfishHostPipe* pipe, GoldfishPipeCloseReason reason) {
    if (s_pipe.close) s_pipe.close(pipe, static_cast<int>(reason));
}

static void guestNoOp(QEMUFile*) {}

static GoldfishHostPipe* guestLoad(QEMUFile*, GoldfishHwPipe*, char*) {
    return nullptr;
}

static void guestSave(GoldfishHostPipe*, QEMUFile*) {}

static GoldfishPipePollFlags guestPoll(GoldfishHostPipe* pipe) {
    return s_pipe.poll ? s_pipe.poll(pipe) : GOLDFISH_PIPE_POLL_HUP;
}

static int guestRecv(GoldfishHostPipe* pipe, GoldfishPipeBuffer* buffers, int count) {
    return s_pipe.recv ? s_pipe.recv(pipe, buffers, count) : GOLDFISH_PIPE_ERROR_IO;
}

static void waitGuestRecv(GoldfishHostPipe* pipe) {
    if (s_pipe.waitRecv) s_pipe.waitRecv(pipe);
}

static int guestSend(GoldfishHostPipe** pipe, const GoldfishPipeBuffer* buffers, int count) {
    return s_pipe.send ? s_pipe.send(pipe, buffers, count) : GOLDFISH_PIPE_ERROR_IO;
}

static void waitGuestSend(GoldfishHostPipe* pipe) {
    if (s_pipe.waitSend) s_pipe.waitSend(pipe);
}

static void guestWakeOn(GoldfishHostPipe* pipe, GoldfishPipeWakeFlags flags) {
    if (s_pipe.wakeOn) s_pipe.wakeOn(pipe, static_cast<unsigned>(flags));
}

static void dmaAddBuffer(void*, uint64_t, uint64_t) {}
static void dmaRemoveBuffer(uint64_t) {}
static void dmaNoOp() {}

static const GoldfishPipeServiceOps kServiceOps = {
        guestOpen,
        guestOpenWithFlags,
        guestClose,
        guestNoOp,
        guestNoOp,
        guestNoOp,
        guestNoOp,
        guestLoad,
        guestSave,
        guestPoll,
        guestRecv,
        waitGuestRecv,
        guestSend,
        waitGuestSend,
        guestWakeOn,
        dmaAddBuffer,
        dmaRemoveBuffer,
        dmaNoOp,
        dmaNoOp,
        guestNoOp,
        guestNoOp,
};

}  // namespace

extern "C" const GoldfishPipeServiceOps* gsi_android_pipe_init(void* backend) {
    if (backend == nullptr) return nullptr;

    if (!load(backend, "_Z23android_pipe_guest_openPv", &s_pipe.open) ||
        !load(backend, "_Z34android_pipe_guest_open_with_flagsPvj", &s_pipe.openWithFlags) ||
        !load(backend, "_Z24android_pipe_guest_closePv15PipeCloseReason", &s_pipe.close) ||
        !load(backend, "_Z23android_pipe_guest_pollPv", &s_pipe.poll) ||
        !load(backend, "_Z23android_pipe_guest_recvPvP17AndroidPipeBufferi", &s_pipe.recv) ||
        !load(backend, "_Z28android_pipe_wait_guest_recvPv", &s_pipe.waitRecv) ||
        !load(backend, "_Z23android_pipe_guest_sendPPvPK17AndroidPipeBufferi", &s_pipe.send) ||
        !load(backend, "_Z28android_pipe_wait_guest_sendPv", &s_pipe.waitSend) ||
        !load(backend, "_Z26android_pipe_guest_wake_onPvj", &s_pipe.wakeOn) ||
        !load(backend, "_ZN7android11AndroidPipe13initThreadingEPNS_6VmLockE",
              &s_pipe.initThreading)) {
        return nullptr;
    }

    static NoOpVmLock vmLock;
    s_pipe.initThreading(&vmLock);
    return &kServiceOps;
}
