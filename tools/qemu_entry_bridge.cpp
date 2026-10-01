// Enter Android QEMU through the same frontend initialization used by the
// AOSP launcher, while keeping the exported entry point small for JNI.

#include "android-qemu2-glue/qemu-console-factory.h"
#include "android/console.h"

extern "C" int run_qemu_main(int argc, char** argv, void (*on_main_loop_done)(void));

extern "C" int gsi_qemu_run_main(int argc, char** argv) {
    injectQemuConsoleAgents("");

    // The APK supplies ordinary QEMU arguments and its own guest images. Do
    // not require the desktop emulator's generated -android-hw file, but keep
    // the Android console agents available for gfxstream and the display.
    getConsoleAgents()->settings->set_android_qemu_mode(false);
    getConsoleAgents()->settings->set_min_config_qemu_mode(false);

    return run_qemu_main(argc, argv, nullptr);
}
