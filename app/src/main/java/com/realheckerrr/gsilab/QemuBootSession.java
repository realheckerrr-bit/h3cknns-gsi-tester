package com.realheckerrr.gsilab;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Starts a headless QEMU attempt for a validated GSI + guest bundle. */
public final class QemuBootSession {
    private final File workDirectory;
    private final File consoleLog;
    private final long nativeHandle;
    private long markerScanOffset;
    private String markerScanCarry = "";
    private boolean androidBootMarkerSeen;

    private QemuBootSession(File workDirectory, File consoleLog, long nativeHandle) {
        this.workDirectory = workDirectory;
        this.consoleLog = consoleLog;
        this.nativeHandle = nativeHandle;
    }

    public static QemuBootSession start(Context context, File gsi, File guestBundle) throws IOException {
        return start(context, gsi, guestBundle, false);
    }

    /**
     * Starts the normal Ranchu profile or a conservative recovery profile.
     * The recovery profile is deliberately different from the first attempt:
     * some ARM64 TCG hosts make the Cortex-A57/multithreaded combination stall
     * while SurfaceFlinger is opening the graphics pipe.
     */
    public static QemuBootSession start(Context context, File gsi, File guestBundle,
                                         boolean recoveryProfile) throws IOException {
        File work = new File(context.getFilesDir(), "vm-session");
        BootAssets assets = BootAssets.prepare(gsi, guestBundle, work);
        File rom = assets.rom != null
                ? assets.rom
                : (assets.ranchu ? null : copyBundledRom(context, work));
        File engine = assets.qemu != null
                ? assets.qemu
                : new File(context.getApplicationInfo().nativeLibraryDir, "libqemu-system-aarch64.so");
        if (!engine.isFile()) throw new IOException("No usable libqemu-system-aarch64.so was found.");
        File log = new File(work, "console.log");
        if (log.exists() && !log.delete()) throw new IOException("Cannot reset console log.");
        List<String> args = new ArrayList<>();
        // Keep the guest vCPU and device threads schedulable while SDL and
        // SurfaceFlinger are active on ARM64 TCG hosts.
        args.add("-accel");
        args.add(assets.ranchu && recoveryProfile ? "tcg,thread=single" : "tcg,thread=multi");
        args.add("-M");
        args.add(assets.ranchu
                ? "ranchu"
                : assets.cuttlefish
                ? "virt,gic-version=2,mte=on,usb=off,dump-guest-core=off"
                : "virt,gic-version=3");
        // The Android emulator's Ranchu guest is validated against the
        // cortex-a57 model.  `max` exposes host/TCG features that can make
        // this older Android 15 kernel enter the graphics pipe and never
        // return from SurfaceFlinger on some ARM64 hosts.
        args.add("-cpu");
        args.add(assets.ranchu ? (recoveryProfile ? "cortex-a53" : "cortex-a57") : "max");
        args.add("-m"); args.add("4096");
        // Keep the Ranchu request aligned with the Android Emulator guest pack;
        // multithreaded TCG lets host-side SDL and device work progress even
        // when this board's TCG PSCI path leaves one guest CPU online.
        args.add("-smp"); args.add(assets.ranchu ? (recoveryProfile ? "1" : "2") : "4");
        args.add("-rtc"); args.add("base=utc");
        args.add("-kernel"); args.add(assets.kernel.getAbsolutePath());
        args.add("-initrd"); args.add(assets.ramdisk.getAbsolutePath());
        args.add("-append");
        args.add(assets.cuttlefish
                ? "loop.max_part=7 init=/init console=ttyAMA0,115200 earlycon=pl011,mmio,0x09000000 androidboot.console=ttyAMA0 "
                + "androidboot.hardware=vsoc androidboot.boot_devices=4010000000.pcie "
                + "androidboot.fstab_suffix=cf.ext4.cts androidboot.force_normal_boot=1 "
                + "androidboot.slot_suffix=_a androidboot.verifiedbootstate=orange "
                + "mac80211_hwsim.radios=0 androidboot.lcd_density=160 "
                + "androidboot.setupwizard_mode=DISABLED security=selinux enforcing=0 "
                + "androidboot.selinux=permissive audit=1 buildvariant=userdebug "
                + "androidboot.vendor.apex.com.android.hardware.audio=none "
                + "androidboot.vendor.apex.com.android.hardware.authsecret=none "
                + "androidboot.vendor.apex.com.android.hardware.boot=none "
                + "androidboot.vendor.apex.com.android.hardware.cas=none "
                + "androidboot.vendor.apex.com.android.hardware.contexthub=none "
                + "androidboot.vendor.apex.com.android.hardware.dumpstate=none "
                + "androidboot.vendor.apex.com.android.hardware.gatekeeper=none "
                + "androidboot.vendor.apex.com.android.hardware.health=none "
                + "androidboot.vendor.apex.com.android.hardware.input.processor=none "
                + "androidboot.vendor.apex.com.android.hardware.keymint=none "
                + "androidboot.vendor.apex.com.android.hardware.net.nlinterceptor=none "
                + "androidboot.vendor.apex.com.android.hardware.neuralnetworks=none "
                + "androidboot.vendor.apex.com.android.hardware.power=none "
                + "androidboot.vendor.apex.com.android.hardware.security.authgraph=none "
                + "androidboot.vendor.apex.com.android.hardware.security.secretkeeper=none "
                + "androidboot.vendor.apex.com.android.hardware.tetheroffload=none "
                + "androidboot.vendor.apex.com.android.hardware.thermal=none "
                + "androidboot.vendor.apex.com.android.hardware.usb=none "
                + "androidboot.vendor.apex.com.android.hardware.uwb=none "
                + "androidboot.vendor.apex.com.android.hardware.threadnetwork=none "
                + "androidboot.vendor.apex.com.android.hardware.wifi=none "
                + "androidboot.vendor.apex.com.google.cf.input.config=none "
                + "androidboot.vendor.apex.com.google.cf.health=none "
                + "androidboot.vendor.apex.com.google.cf.oemlock=none "
                + "androidboot.vendor.apex.com.google.cf.wifi=none "
                + "androidboot.vendor.apex.com.google.cf.wpa_supplicant=none"
                : assets.ranchu
                ? "console=ttyAMA0,115200 androidboot.console=ttyAMA1 androidboot.hardware=ranchu "
                + "androidboot.verifiedbootstate=orange androidboot.qemu=1 "
                + "androidboot.qemu.vsync=60 qemu.gles=1 androidboot.hardware.egl=emulation "
                + "androidboot.cpuvulkan.version=0 androidboot.hardware.vulkan=ranchu "
                + "androidboot.hardware.gltransport=virtio-gpu-asg "
                + "androidboot.qemu.gltransport.name=virtio-gpu-asg "
                + "androidboot.hardware.gralloc=minigbm androidboot.hardware.hwcomposer=ranchu "
                + "androidboot.hardware.hwcomposer.display_finder_mode=drm "
                + "androidboot.hardware.hwcomposer.display_framebuffer_format=rgba "
                + "androidboot.opengles.version=196609 "
                + "androidboot.debug.hwui.renderer=opengl androidboot.debug.renderengine.backend=skiagl "
                + "androidboot.config.low_ram=0 androidboot.dalvik.vm.checkjni=1 "
                + "androidboot.debug.stagefright.ccodec=4 androidboot.debug.sf.nobootanimation=1 "
                + "qemu.logcat=start androidboot.selinux=permissive selinux=0 security=selinux enforcing=0 "
                + "androidboot.dalvik.vm.heapsize=192m"
                : "console=ttyAMA0,115200 androidboot.hardware=generic");
        if (assets.ranchu) {
            // virtio-mmio enumerates devices in reverse declaration order:
            // userdata -> system -> vendor gives vda=vendor, vdb=system, vdc=userdata.
            if (assets.userdata != null) addDrive(args, "userdata", assets.userdata, false, true);
            addDrive(args, "system", assets.system, true, true);
            addDrive(args, "vendor", assets.vendor, true, true);
        } else {
            // Transitional virtio-pci is supported by the Android Linux
            // guest. The bundled firmware supplies its PCI option ROM.
            if (rom != null) {
                args.add("-L");
                args.add(work.getAbsolutePath());
            }
            addDrive(args, "system", assets.system, true, false, rom);
            if (assets.userdata != null) addDrive(args, "userdata", assets.userdata, false, false, rom);
            if (assets.cache != null) addDrive(args, "cache", assets.cache, false, false, rom);
            addDrive(args, "vendor", assets.vendor, true, false, rom);
        }
        if (assets.ranchu) {
            // Android's gfxstream launcher uses the PCI virtio GPU for the
            // virtio-gpu-asg transport, including on the ARM64 Ranchu path.
            args.add("-device");
            args.add("virtio-gpu-pci,id=gpu0");
        } else if (assets.cuttlefish) {
            // Cuttlefish uses the virt machine's PCI bus. Its guest kernel
            // loads virtio-gpu.ko from PCI, not from Ranchu's MMIO transports.
            args.add("-device");
            args.add("virtio-gpu-pci,id=gpu0"
                    + (rom == null ? "" : ",romfile=" + rom.getName()));
        }
        // The bundled QEMU is built with SDL2 but without host OpenGL. Android's SDL backend presents the
        // guest framebuffer as the VM screen while the activity remains the
        // controller/log view; generic fallback guests stay serial-only.
        args.add("-display"); args.add(assets.cuttlefish || assets.ranchu ? "sdl" : "none");
        args.add("-monitor"); args.add("none");
        args.add("-serial"); args.add("file:" + log.getAbsolutePath());
        args.add("-no-reboot");
        long handle = QemuRunner.start(engine, args);
        if (handle == 0) throw new IOException("QEMU JNI could not load or enter the engine.");
        return new QemuBootSession(work, log, handle);
    }

    private static void addDrive(List<String> args, String id, File image, boolean readOnly, boolean mmio) {
        addDrive(args, id, image, readOnly, mmio, null);
    }

    private static void addDrive(List<String> args, String id, File image, boolean readOnly,
                                 boolean mmio, File rom) {
        args.add("-drive");
        args.add("if=none,format=raw,id=" + id + ",file=" + image.getAbsolutePath()
                + (readOnly ? ",readonly=on" : ""));
        args.add("-device");
        if (mmio) {
            args.add("virtio-blk-device,drive=" + id);
        } else {
            args.add("virtio-blk-pci,scsi=off"
                    + (rom == null ? "" : ",romfile=" + rom.getName())
                    + ",drive=" + id);
        }
    }

    private static File copyBundledRom(Context context, File work) throws IOException {
        File target = new File(work, "efi-virtio.rom");
        if (target.isFile() && target.length() > 0) return target;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create QEMU firmware directory.");
        }
        try (InputStream input = context.getAssets().open("efi-virtio.rom");
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } catch (IOException error) {
            if (target.isFile() && !target.delete()) {
                throw error;
            }
            return null;
        }
        return target;
    }

    public String readConsole() {
        try {
            if (!consoleLog.isFile()) return "(waiting for QEMU console output)";
            long length = consoleLog.length();
            int size = (int) Math.min(12000L, length);
            byte[] bytes = new byte[size];
            try (RandomAccessFile input = new RandomAccessFile(consoleLog, "r")) {
                input.seek(Math.max(0L, length - size));
                input.readFully(bytes);
            }
            String value = new String(bytes, StandardCharsets.UTF_8);
            return value;
        } catch (Exception error) {
            return "(console read failed: " + error.getMessage() + ")";
        }
    }

    /**
     * Scans newly written serial output for an Android boot marker.  The
     * visible console is intentionally capped, so markers must not disappear
     * just because later boot logs pushed them out of the UI tail.
     */
    public boolean hasAndroidBootMarker() {
        if (androidBootMarkerSeen) return true;
        try {
            if (!consoleLog.isFile()) return false;
            long length = consoleLog.length();
            if (length < markerScanOffset) {
                markerScanOffset = 0L;
                markerScanCarry = "";
            }
            if (length == markerScanOffset) return false;
            byte[] bytes = new byte[8192];
            try (RandomAccessFile input = new RandomAccessFile(consoleLog, "r")) {
                input.seek(markerScanOffset);
                int read;
                while ((read = input.read(bytes)) != -1) {
                    String text = markerScanCarry
                            + new String(bytes, 0, read, StandardCharsets.UTF_8);
                    if (containsAndroidBootMarker(text)) {
                        androidBootMarkerSeen = true;
                        markerScanOffset = length;
                        markerScanCarry = "";
                        return true;
                    }
                    markerScanCarry = text.substring(Math.max(0, text.length() - 128));
                    markerScanOffset += read;
                }
            }
        } catch (Exception ignored) {
            // The serial file may be in the middle of a native write; retry
            // on the next UI poll instead of treating that as a boot failure.
        }
        return false;
    }

    private static boolean containsAndroidBootMarker(String console) {
        String lower = console.toLowerCase(java.util.Locale.US);
        return lower.contains("sys.boot_completed")
                || lower.contains("boot animation stopped")
                || lower.contains("starting service .zygote")
                || lower.contains("android runtime started")
                || (lower.contains("class_start main") && lower.contains("succeeded"));
    }

    public boolean isRunning() {
        return QemuRunner.isRunning(nativeHandle);
    }

    public void stop() {
        QemuRunner.stop(nativeHandle);
    }
}
