package com.realheckerrr.gsilab;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** JNI boundary for the dynamically loaded Android QEMU system engine. */
public final class QemuRunner {
    private static final String LOG_TAG = "h3cknn-gsi-runner";
    private static final String ENGINE_NAME = "libqemu-system-aarch64.so";
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
                && new File(nativeLibraryDir, ENGINE_NAME).isFile();
    }

    /**
     * Returns a filesystem path suitable for QEMU's dlopen bridge. Modern
     * Android builds may keep JNI libraries compressed inside base.apk, so the
     * engine is copied out of the APK on first use while its source remains
     * bundled in the APK itself.
     */
    public static synchronized File ensureEngine(Context context) throws IOException {
        File installed = new File(context.getApplicationInfo().nativeLibraryDir, ENGINE_NAME);
        if (installed.isFile() && installed.length() > 0) return installed;

        File directory = new File(context.getFilesDir(), "vm-engine");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create the private QEMU engine directory.");
        }
        File target = new File(directory, ENGINE_NAME);
        if (target.isFile() && target.length() > 0) return target;
        File partial = new File(directory, ENGINE_NAME + ".partial");

        ZipEntry entry = null;
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            entry = apk.getEntry("lib/arm64-v8a/" + ENGINE_NAME);
            if (entry == null) throw new IOException("Bundled QEMU engine is missing from this APK.");
            try (InputStream input = apk.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
        }
        if (!partial.renameTo(target)) {
            if (partial.isFile()) partial.delete();
            throw new IOException("Cannot finalize the private QEMU engine copy.");
        }
        target.setReadable(true, false);
        target.setExecutable(true, false);
        Log.i(LOG_TAG, "Materialized bundled QEMU engine at " + target.getAbsolutePath());
        return target;
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
