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
        out.append("  engine: ").append(engine.getAbsolutePath()).append('\n');
        out.append("  engine present: ").append(enginePresent ? "yes" : "no").append('\n');
        out.append("  system source: ").append(gsi == null ? "missing" : gsi.inputName).append('\n');
        out.append("  guest source: ").append(guest == null ? "missing" : guest.inputName).append('\n');
        out.append("\nThe final runner will extract only verified guest entries into app-private storage and invoke:\n");
        out.append("  libqemu-system-aarch64.so -M virt (Cuttlefish=gic2, generic=gic3) -cpu max -m 2048 -smp 4\n");
        out.append("    -kernel <kernel> -initrd <ramdisk.img>\n");
        out.append("    -drive file=<system.img>,format=raw,readonly=on + profile-specific virtio-blk-pci\n");
        out.append("    -drive file=<cache/userdata/vendor>,format=raw + profile-specific disk order\n");
        out.append("    -drive file=<vendor.img or super/vendor_a>,format=raw,readonly=on\n");
        out.append("  Cuttlefish uses virtio-blk-pci-non-transitional; Ranchu uses virtio-blk-pci.\n");
        out.append("    -drive file=<userdata.img>,format=raw -display none -serial <console.log>\n");
        if (!enginePresent) out.append("\nSTATUS: not runnable; this APK has no bundled QEMU system engine.\n");
        else out.append("\nSTATUS: engine detected; the app can make a headless boot attempt. Display/input and device-specific validation remain.\n");
        return out.toString();
    }
}
