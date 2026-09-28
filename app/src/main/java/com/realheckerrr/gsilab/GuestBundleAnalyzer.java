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
        String qemu = null;
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
                if (kernel == null && (base.equals("kernel") || base.equals("kernel.gz")
                        || base.equals("kernel-ranchu") || base.equals("kernel-ranchu.gz")
                        || base.equals("kernel-ranchu-64") || base.equals("kernel-ranchu-64.gz"))) kernel = name;
                if (ramdisk == null && (base.equals("ramdisk.img") || base.equals("ramdisk.img.gz"))) ramdisk = name;
                if (vendor == null && (base.equals("vendor.img") || base.equals("vendor.img.gz")
                        || base.equals("vendor_a.img") || base.equals("vendor_a.img.gz"))) vendor = name;
                if (userdata == null && (base.equals("userdata.img") || base.equals("userdata.img.gz")
                        || base.equals("userdata-qemu.img") || base.equals("userdata-qemu.img.gz"))) userdata = name;
                if (qemu == null && (base.equals("qemu-system-aarch64") || base.equals("libqemu-system-aarch64.so"))) qemu = name;
            }
        }
        if (kernel == null) errors.add("The bundle has no ARM64 kernel file (kernel or kernel-ranchu).");
        if (ramdisk == null) errors.add("The bundle has no ramdisk.img.");
        if (vendor == null) errors.add("The bundle has no vendor.img; a GSI cannot provide the hardware interface.");
        if (qemu == null) warnings.add("The bundle has no engine override; normal APK builds provide the QEMU runtime separately.");
        boolean candidate = errors.isEmpty();
        return new GuestBundleAnalysis(input.getName(), entries, kernel, ramdisk, vendor, userdata, qemu,
                GsiAnalyzer.sha256(input), candidate, warnings, errors);
    }
}
