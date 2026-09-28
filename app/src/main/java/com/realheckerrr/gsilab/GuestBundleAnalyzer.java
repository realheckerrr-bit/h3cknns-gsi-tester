package com.realheckerrr.gsilab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates a reproducible guest bundle without extracting or executing anything. */
public final class GuestBundleAnalyzer {
    private GuestBundleAnalyzer() {}

    public static GuestBundleAnalysis analyze(File input) throws IOException {
        if (input == null || !input.isFile()) throw new IOException("The guest bundle is not readable.");
        if (!input.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            throw new IOException("Guest bundles must be ZIP archives.");
        }
        String kernel = null;
        String ramdisk = null;
        String vendor = null;
        String userdata = null;
        String cache = null;
        String encryptionKey = null;
        String qemu = null;
        String superImage = null;
        String bootImage = null;
        String initBootImage = null;
        String vendorBootImage = null;
        int entries = 0;
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                entries++;
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.US);
                if (kernel == null && (base.equals("kernel") || base.equals("kernel.gz") || base.equals("kernel.xz")
                        || base.equals("kernel-ranchu") || base.equals("kernel-ranchu.gz") || base.equals("kernel-ranchu.xz")
                        || base.equals("kernel-ranchu-64") || base.equals("kernel-ranchu-64.gz") || base.equals("kernel-ranchu-64.xz")
                        || base.equals("kernel_16k") || base.equals("kernel_16k.gz") || base.equals("kernel_16k.xz"))) kernel = name;
                if (ramdisk == null && (base.equals("ramdisk.img") || base.equals("ramdisk.img.gz") || base.equals("ramdisk.img.xz")
                        || base.equals("ramdisk_16k.img") || base.equals("ramdisk_16k.img.gz") || base.equals("ramdisk_16k.img.xz"))) ramdisk = name;
                if (vendor == null && (base.equals("vendor.img") || base.equals("vendor.img.gz") || base.equals("vendor.img.xz")
                        || base.equals("vendor_a.img") || base.equals("vendor_a.img.gz") || base.equals("vendor_a.img.xz"))) vendor = name;
                if (userdata == null && (base.equals("userdata.img") || base.equals("userdata.img.gz") || base.equals("userdata.img.xz")
                        || base.equals("userdata-qemu.img") || base.equals("userdata-qemu.img.gz") || base.equals("userdata-qemu.img.xz"))) userdata = name;
                if (cache == null && (base.equals("cache.img") || base.equals("cache.img.gz") || base.equals("cache.img.xz"))) cache = name;
                if (encryptionKey == null && (base.equals("encryptionkey.img") || base.equals("encryptionkey.img.gz") || base.equals("encryptionkey.img.xz"))) encryptionKey = name;
                if (qemu == null && (base.equals("qemu-system-aarch64") || base.equals("libqemu-system-aarch64.so"))) qemu = name;
                if (superImage == null && (base.equals("super.img") || base.equals("super.img.gz") || base.equals("super.img.xz"))) superImage = name;
                if (bootImage == null && (base.equals("boot.img") || base.equals("boot.img.gz") || base.equals("boot.img.xz"))) bootImage = name;
                if (initBootImage == null && (base.equals("init_boot.img") || base.equals("init_boot.img.gz") || base.equals("init_boot.img.xz"))) initBootImage = name;
                if (vendorBootImage == null && (base.equals("vendor_boot.img") || base.equals("vendor_boot.img.gz") || base.equals("vendor_boot.img.xz"))) vendorBootImage = name;
            }
        }
        if (kernel == null && bootImage != null) {
            kernel = bootImage + " (embedded kernel)";
            warnings.add("kernel will be extracted from boot.img during boot preparation.");
        }
        if (ramdisk == null && (bootImage != null || initBootImage != null || vendorBootImage != null)) {
            String source = vendorBootImage != null ? vendorBootImage
                    : (initBootImage != null ? initBootImage : bootImage);
            ramdisk = source + " (embedded ramdisk)";
            warnings.add("ramdisk will be extracted from the Android boot image during boot preparation.");
        }
        if (kernel == null) errors.add("The bundle has no ARM64 kernel file (kernel or kernel-ranchu).");
        if (ramdisk == null) errors.add("The bundle has no ramdisk.img.");
        if (vendor == null && superImage != null) {
            vendor = superImage + " (vendor logical partition)";
            warnings.add("vendor will be extracted from the Cuttlefish super image at boot preparation time.");
        }
        if (vendor == null) errors.add("The bundle has no vendor.img or super.img; a GSI cannot provide the hardware interface.");
        if (qemu == null) warnings.add("The bundle has no engine override; normal APK builds provide the QEMU runtime separately.");
        boolean candidate = errors.isEmpty();
        return new GuestBundleAnalysis(input.getName(), entries, kernel, ramdisk, vendor, userdata, cache, encryptionKey, qemu,
                GsiAnalyzer.sha256(input), candidate, warnings, errors);
    }
}
