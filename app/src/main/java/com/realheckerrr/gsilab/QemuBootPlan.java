package com.realheckerrr.gsilab;

import android.content.Context;

import java.io.File;

/**
 * Produces a transparent, inspectable launch plan for the QEMU backend.
 * The actual run is started by QemuBootSession and still does not claim boot success.
 */
public final class QemuBootPlan {
    public final File engine;
    public final boolean enginePresent;

    private QemuBootPlan(File engine, boolean enginePresent) {
        this.engine = engine;
        this.enginePresent = enginePresent;
    }

    public static QemuBootPlan inspect(Context context) {
        File engine = new File(context.getApplicationInfo().nativeLibraryDir, "libqemu-system-aarch64.so");
        return new QemuBootPlan(engine, QemuRunner.enginePresent(context.getApplicationInfo().nativeLibraryDir));
    }

    public String render(GsiAnalysis gsi, GuestBundleAnalysis guest) {
        StringBuilder out = new StringBuilder();
        out.append("QEMU LAUNCH PLAN\n");
        out.append("  bundled engine: ").append(engine.getAbsolutePath()).append('\n');
        out.append("  engine bundled in this APK: ").append(enginePresent ? "yes" : "no").append('\n');
        out.append("  system source: ").append(gsi == null ? "missing" : gsi.inputName).append('\n');
        out.append("  guest source: ").append(guest == null ? "missing" : guest.inputName).append('\n');
        out.append("\nThe final runner will extract only verified guest entries into app-private storage and invoke:\n");
        out.append("  libqemu-system-aarch64.so -M ranchu (Cuttlefish=virt, generic=virt) -cpu max -m 4096 -smp 4\n");
        out.append("    -kernel <kernel> -initrd <ramdisk.img>\n");
        out.append("    -drive file=<system.img>,format=raw,readonly=on + profile-specific virtio block device\n");
        out.append("    -drive file=<cache/userdata/vendor>,format=raw + profile-specific disk order\n");
        out.append("    -drive file=<vendor.img or super/vendor_a>,format=raw,readonly=on\n");
        out.append("  Cuttlefish uses virtio-blk-pci-non-transitional; Ranchu uses virtio-mmio in userdata/system/vendor order.\n");
        out.append("    -drive file=<userdata.img>,format=raw -display sdl -serial <console.log>\n");
        if (!enginePresent) out.append("\nSTATUS: not runnable; rebuild with the embedded QEMU engine.\n");
        else out.append("\nSTATUS: self-contained engine detected; no separate QEMU APK is required.\n");
        return out.toString();
    }
}
