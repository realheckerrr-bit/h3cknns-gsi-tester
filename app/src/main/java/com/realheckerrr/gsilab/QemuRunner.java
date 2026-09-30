package com.realheckerrr.gsilab;

import java.io.File;
import java.util.List;

/** JNI boundary for the dynamically loaded Android QEMU system engine. */
public final class QemuRunner {
    static {
        // Load the packaged runtime and renderer before QEMU's dlopen bridge
        // runs. This keeps the self-contained APK path reliable across
        // Android linker namespaces and device vendors.
        loadOptional("c++_shared");
        System.loadLibrary("gsi_runner");
        loadOptional("compat-musl");
        loadOptional("compat-limbo");
        loadOptional("glib-2.0");
        loadOptional("pixman-1");
        loadOptional("SDL2");
        loadOptional("compat-SDL2-addons");
        loadOptional("compat-SDL2-ext");
        loadOptional("gfxstream_backend");
    }

    private QemuRunner() {}

    private static void loadOptional(String name) {
        try {
            System.loadLibrary(name);
        } catch (UnsatisfiedLinkError ignored) {
            // The engine reports a clear failure if a required companion is absent.
        }
    }

    public static boolean enginePresent(String nativeLibraryDir) {
        return nativeLibraryDir != null
                && new File(nativeLibraryDir, "libqemu-system-aarch64.so").isFile();
    }

    public static long start(File engine, List<String> arguments) {
        String[] args = arguments.toArray(new String[0]);
        return nativeStart(engine.getAbsolutePath(), args);
    }

    public static void stop(long handle) {
        if (handle != 0) nativeStop(handle);
    }

    public static boolean isRunning(long handle) {
        return handle != 0 && nativeIsRunning(handle);
    }

    private static native long nativeStart(String enginePath, String[] arguments);

    private static native void nativeStop(long handle);

    private static native boolean nativeIsRunning(long handle);
}
