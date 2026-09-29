package com.realheckerrr.gsilab;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
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

    private QemuBootSession(File workDirectory, File consoleLog, long nativeHandle) {
        this.workDirectory = workDirectory;
        this.consoleLog = consoleLog;
        this.nativeHandle = nativeHandle;
    }

    public static QemuBootSession start(Context context, File gsi, File guestBundle) throws IOException {
        File work = new File(context.getFilesDir(), "vm-session");
        BootAssets assets = BootAssets.prepare(gsi, guestBundle, work);
        File rom = assets.rom != null ? assets.rom : copyBundledRom(context, work);
        File engine = assets.qemu != null
                ? assets.qemu
                : new File(context.getApplicationInfo().nativeLibraryDir, "libqemu-system-aarch64.so");
        if (!engine.isFile()) throw new IOException("No usable libqemu-system-aarch64.so was found.");
        File log = new File(work, "console.log");
        if (log.exists() && !log.delete()) throw new IOException("Cannot reset console log.");
        List<String> args = new ArrayList<>();
        args.add("-M");
        args.add(assets.cuttlefish
                ? "virt,gic-version=2,mte=on,usb=off,dump-guest-core=off"
                : "virt,gic-version=3");
        args.add("-cpu"); args.add("max");
        args.add("-m"); args.add("2048");
        args.add("-smp"); args.add("4");
        args.add("-rtc"); args.add("base=utc");
        args.add("-kernel"); args.add(assets.kernel.getAbsolutePath());
        args.add("-initrd"); args.add(assets.ramdisk.getAbsolutePath());
        args.add("-append");
        args.add(assets.cuttlefish
                ? "loop.max_part=7 init=/init console=ttyS0,115200 androidboot.console=ttyS1 "
                + "androidboot.hardware=vsoc androidboot.boot_devices=4010000000.pcie "
                + "androidboot.slot_suffix=_a androidboot.verifiedbootstate=orange "
                + "mac80211_hwsim.radios=0 androidboot.lcd_density=160 "
                + "androidboot.setupwizard_mode=DISABLED security=selinux enforcing=0 "
                + "androidboot.selinux=permissive audit=1 buildvariant=userdebug"
                : assets.ranchu
                ? "console=ttyAMA0,115200 androidboot.console=ttyAMA1 androidboot.hardware=ranchu "
                + "androidboot.verifiedbootstate=orange androidboot.qemu=1 "
                + "androidboot.qemu.vsync=60 androidboot.qemu.gltransport.name=virtio-gpu-pipe "
                + "androidboot.hardware.gltransport=virtio-gpu-pipe androidboot.hardwareegl=emulation "
                + "androidboot.hardware.egl=emulation androidboot.hardware.gralloc=minigbm "
                + "androidboot.hardware.hwcomposer=ranchu androidboot.hardware.vulkan=ranchu "
                + "androidboot.qemu.cpuvulkan.version=0 androidboot.opengles.version=196609 "
                + "androidboot.debug.hwui.renderer=opengl androidboot.debug.renderengine.backend=skiagl "
                + "androidboot.config.low_ram=0 androidboot.dalvik.vm.checkjni=1 "
                + "androidboot.debug.stagefright.ccodec=4 androidboot.debug.sf.nobootanimation=1 "
                + "androidboot.dalvik.vm.heapsize=192m"
                : "console=ttyAMA0,115200 androidboot.hardware=generic");
        if (assets.ranchu) {
            // virtio-mmio enumerates devices in reverse declaration order:
            // userdata -> system -> vendor gives vda=vendor, vdb=system, vdc=userdata.
            if (assets.userdata != null) addDrive(args, "userdata", assets.userdata, false, false, rom, true);
            addDrive(args, "system", assets.system, true, false, rom, true);
            addDrive(args, "vendor", assets.vendor, true, false, rom, true);
        } else {
            addDrive(args, "system", assets.system, true, assets.cuttlefish, rom, false);
            if (assets.userdata != null) addDrive(args, "userdata", assets.userdata, false, assets.cuttlefish, rom, false);
            if (assets.cache != null) addDrive(args, "cache", assets.cache, false, assets.cuttlefish, rom, false);
            addDrive(args, "vendor", assets.vendor, true, assets.cuttlefish, rom, false);
        }
        if (assets.cuttlefish || assets.ranchu) {
            args.add("-device"); args.add("virtio-gpu-pci,id=gpu0,virgl=on");
            args.add("-object"); args.add("rng-random,id=objrng0,filename=/dev/urandom");
            args.add("-device"); args.add("virtio-rng-pci,rng=objrng0,max-bytes=1024,period=2000");
        }
        args.add("-display"); args.add(assets.cuttlefish || assets.ranchu ? "egl-headless" : "none");
        args.add("-monitor"); args.add("none");
        args.add("-serial"); args.add("file:" + log.getAbsolutePath());
        args.add("-no-reboot");
        long handle = QemuRunner.start(engine, args);
        if (handle == 0) throw new IOException("QEMU JNI could not load or enter the engine.");
        return new QemuBootSession(work, log, handle);
    }

    private static void addDrive(List<String> args, String id, File image, boolean readOnly,
                                 boolean nonTransitional, File rom, boolean mmio) {
        args.add("-drive");
        args.add("if=none,format=raw,id=" + id + ",file=" + image.getAbsolutePath()
                + (readOnly ? ",readonly=on" : ""));
        args.add("-device");
        if (mmio) {
            args.add("virtio-blk-device,drive=" + id);
        } else {
            args.add((nonTransitional ? "virtio-blk-pci-non-transitional" : "virtio-blk-pci")
                    + (rom == null ? "" : ",romfile=" + rom.getAbsolutePath())
                    + ",scsi=off,drive=" + id);
        }
    }

    private static File copyBundledRom(Context context, File work) {
        File rom = new File(work, "efi-virtio.rom");
        try (InputStream input = context.getAssets().open("efi-virtio.rom");
             FileOutputStream output = new FileOutputStream(rom)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return rom.isFile() && rom.length() > 0 ? rom : null;
        } catch (IOException ignored) {
            return null;
        }
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

    public boolean isRunning() {
        return QemuRunner.isRunning(nativeHandle);
    }

    public void stop() {
        QemuRunner.stop(nativeHandle);
    }
}
