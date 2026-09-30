package com.realheckerrr.gsilab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import org.tukaani.xz.XZInputStream;

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
        boolean bootImageHasKernel = false;
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                entries++;
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String rawBase = name.substring(name.lastIndexOf('/') + 1);
                String base = stripCompressionSuffix(rawBase).toLowerCase(Locale.US);
                if (kernel == null && (base.equals("kernel") || base.equals("kernel-ranchu")
                        || base.equals("kernel-ranchu-64") || base.equals("kernel_16k")
                        || base.equals("image") || base.equals("bzimage"))) kernel = name;
                if (ramdisk == null && (base.equals("ramdisk.img") || base.equals("ramdisk_16k.img")
                        || base.equals("initramfs.img") || base.equals("initramfs"))) ramdisk = name;
                if (vendor == null && (base.equals("vendor.img") || base.equals("vendor_a.img"))) vendor = name;
                if (userdata == null && (base.equals("userdata.img") || base.equals("userdata-qemu.img"))) userdata = name;
                if (cache == null && base.equals("cache.img")) cache = name;
                if (encryptionKey == null && base.equals("encryptionkey.img")) encryptionKey = name;
                if (qemu == null && (base.equals("qemu-system-aarch64") || base.equals("libqemu-system-aarch64.so"))) qemu = name;
                if (superImage == null && base.equals("super.img")) superImage = name;
                if (bootImage == null && base.equals("boot.img")) bootImage = name;
                if (initBootImage == null && base.equals("init_boot.img")) initBootImage = name;
                if (vendorBootImage == null && base.equals("vendor_boot.img")) vendorBootImage = name;
            }
            if (kernel == null && bootImage != null) bootImageHasKernel = hasKernelPayload(zip, bootImage);
        }
        if (kernel == null && bootImage != null) {
            if (bootImageHasKernel) {
                kernel = bootImage + " (embedded kernel)";
                warnings.add("kernel will be extracted from boot.img during boot preparation.");
            } else {
                warnings.add("boot.img has no embedded kernel; this image-only Cuttlefish bundle needs a direct ARM64 kernel from its matching cvd-host_package.");
            }
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
        if (qemu == null) warnings.add("The bundle has no engine override; this APK uses its bundled ARM64 QEMU runtime.");
        boolean candidate = errors.isEmpty();
        return new GuestBundleAnalysis(input.getName(), entries, kernel, ramdisk, vendor, userdata, cache, encryptionKey, qemu,
                GsiAnalyzer.sha256(input), candidate, warnings, errors);
    }

    private static boolean hasKernelPayload(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return false;
        try (InputStream raw = zip.getInputStream(entry);
             InputStream input = maybeCompressed(raw, name)) {
            byte[] header = new byte[64];
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset < 12 || !"ANDROID!".equals(new String(header, 0, 8, java.nio.charset.StandardCharsets.US_ASCII))) {
                return false;
            }
            return littleInt(header, 8) > 0;
        }
    }

    private static InputStream maybeCompressed(InputStream input, String name) throws IOException {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".gz")) return new GZIPInputStream(input);
        if (lower.endsWith(".xz")) return new XZInputStream(input);
        if (lower.endsWith(".lz4")) return new LegacyLz4InputStream(input);
        return input;
    }

    private static String stripCompressionSuffix(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".gz") || lower.endsWith(".xz")) return name.substring(0, name.length() - 3);
        if (lower.endsWith(".lz4")) return name.substring(0, name.length() - 4);
        return name;
    }

    private static long littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
