package com.realheckerrr.gsilab;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts only the known boot files and converts Android sparse images to raw ext4. */
public final class BootAssets {
    public final File system;
    public final File kernel;
    public final File ramdisk;
    public final File vendor;
    public final File userdata;
    public final File cache;
    public final File encryptionKey;
    public final File qemu;
    public final File rom;
    public final boolean cuttlefish;
    public final boolean ranchu;

    private BootAssets(File system, File kernel, File ramdisk, File vendor, File userdata, File cache,
                       File encryptionKey, File qemu, File rom,
                       boolean cuttlefish, boolean ranchu) {
        this.system = system;
        this.kernel = kernel;
        this.ramdisk = ramdisk;
        this.vendor = vendor;
        this.userdata = userdata;
        this.cache = cache;
        this.encryptionKey = encryptionKey;
        this.qemu = qemu;
        this.rom = rom;
        this.cuttlefish = cuttlefish;
        this.ranchu = ranchu;
    }

    public static BootAssets prepare(File gsi, File guestBundle, File output) throws IOException {
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create VM working directory.");
        File systemSource = new File(output, "system.source.img");
        if (gsi.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            copyEntry(gsi, systemSource, "system.img", "system.img.gz", "system.img.xz");
        } else {
            copyMaybeGzip(gsi, systemSource);
        }
        File system = materializeImage(systemSource, new File(output, "system.img"));

        File kernel = copyBundleEntry(guestBundle, output, "kernel", "kernel.gz", "kernel.xz", "kernel-ranchu",
                "kernel-ranchu.gz", "kernel-ranchu.xz", "kernel-ranchu-64", "kernel-ranchu-64.gz",
                "kernel-ranchu-64.xz", "kernel_16k", "kernel_16k.gz", "kernel_16k.xz");
        File ramdisk = copyBundleEntry(guestBundle, output, "ramdisk.img", "ramdisk.img.gz", "ramdisk.img.xz",
                "ramdisk_16k.img", "ramdisk_16k.img.gz", "ramdisk_16k.img.xz");
        File bootImage = copyBundleEntry(guestBundle, output, "boot.img", "boot.img.gz", "boot.img.xz");
        File initBootImage = copyBundleEntry(guestBundle, output, "init_boot.img", "init_boot.img.gz", "init_boot.img.xz");
        File vendorBootImage = copyBundleEntry(guestBundle, output, "vendor_boot.img", "vendor_boot.img.gz", "vendor_boot.img.xz");
        if (kernel == null && bootImage != null) {
            kernel = AndroidBootImage.extractKernel(bootImage, new File(output, "kernel.from-boot.img"));
        }
        if (ramdisk == null && bootImage != null) {
            ramdisk = AndroidBootImage.extractRamdisk(bootImage, new File(output, "ramdisk.from-boot.img"));
        }
        if (ramdisk == null && initBootImage != null) {
            ramdisk = AndroidBootImage.extractRamdisk(initBootImage, new File(output, "ramdisk.from-init_boot.img"));
        }
        if (ramdisk == null && vendorBootImage != null) {
            ramdisk = AndroidBootImage.extractVendorRamdisk(vendorBootImage,
                    new File(output, "ramdisk.from-vendor_boot.img"));
        }
        File vendor = copyBundleEntry(guestBundle, output, "vendor.img", "vendor.img.gz", "vendor.img.xz", "vendor_a.img",
                "vendor_a.img.gz", "vendor_a.img.xz", "vendor-qemu.img", "vendor-qemu.img.gz", "vendor-qemu.img.xz");
        File userdata = copyBundleEntry(guestBundle, output, "userdata.img", "userdata.img.gz", "userdata.img.xz",
                "userdata-qemu.img", "userdata-qemu.img.gz", "userdata-qemu.img.xz");
        File cache = copyBundleEntry(guestBundle, output, "cache.img", "cache.img.gz", "cache.img.xz");
        File encryptionKey = copyBundleEntry(guestBundle, output, "encryptionkey.img", "encryptionkey.img.gz", "encryptionkey.img.xz");
        File qemu = copyBundleEntry(guestBundle, output, "libqemu-system-aarch64.so", "qemu-system-aarch64");
        File rom = copyBundleEntry(guestBundle, output, "efi-virtio.rom");
        File superImage = copyBundleEntry(guestBundle, output, "super.img", "super.img.gz", "super.img.xz");
        boolean cuttlefish = superImage != null || (kernel != null && kernel.getName().startsWith("kernel_16k"));
        boolean ranchu = !cuttlefish && kernel != null && kernel.getName().startsWith("kernel-ranchu");
        if (vendor == null && superImage != null) {
            File rawSuper = materializeImage(superImage, new File(output, "super.raw.img"));
            vendor = LogicalPartitionExtractor.extract(rawSuper, "vendor", new File(output, "vendor.from-super.img"));
        }
        if (kernel == null || ramdisk == null || vendor == null) {
            throw new IOException("Guest bundle is missing kernel, ramdisk.img, or vendor.img.");
        }
        if (vendor != null && isSparse(vendor)) vendor = materializeImage(vendor, new File(output, "vendor.raw.img"));
        if (ranchu) {
            vendor = RanchuVendorPatcher.extractFirstGptPartition(vendor, new File(output, "vendor.ranchu.raw.img"));
            RanchuVendorPatcher.patch(system, RanchuVendorPatcher.detectFilesystem(system));
            RanchuVendorPatcher.patch(vendor, RanchuVendorPatcher.detectFilesystem(vendor));
            ramdisk = RanchuRamdiskPatcher.patch(ramdisk, new File(output, "ramdisk.ranchu.img"));
        }
        return new BootAssets(system, kernel, ramdisk, vendor, userdata, cache, encryptionKey, qemu, rom, cuttlefish, ranchu);
    }

    private static File copyBundleEntry(File bundle, File output, String... names) throws IOException {
        try (ZipFile zip = new ZipFile(bundle)) {
            ZipEntry entry = findEntry(zip, names);
            if (entry == null) return null;
            String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
            File target = new File(output, safeName(stripCompressionSuffix(base)));
            try (InputStream raw = zip.getInputStream(entry);
                 InputStream input = maybeCompressed(raw, base);
                 FileOutputStream out = new FileOutputStream(target)) {
                copy(input, out);
            }
            return target;
        }
    }

    private static void copyEntry(File archive, File target, String... wantedBases) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = findEntry(zip, wantedBases);
            if (entry == null) throw new IOException("GSI ZIP does not contain system.img, system.img.gz, or system.img.xz.");
            String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
            try (InputStream raw = zip.getInputStream(entry);
                 InputStream input = maybeCompressed(raw, base);
                 FileOutputStream out = new FileOutputStream(target)) {
                copy(input, out);
            }
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String... wantedNames) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
            for (String wanted : wantedNames) if (base.equalsIgnoreCase(wanted)) return entry;
        }
        return null;
    }

    private static File materializeImage(File source, File rawTarget) throws IOException {
        if (!isSparse(source)) return source;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             RandomAccessFile output = new RandomAccessFile(rawTarget, "rw")) {
            byte[] header = readBytes(input, 28);
            if (littleInt(header, 0) != 0xED26FF3AL) throw new IOException("Invalid sparse image magic.");
            int fileHeaderSize = littleShort(header, 8);
            int chunkHeaderSize = littleShort(header, 10);
            long blockSize = littleInt(header, 12);
            long totalBlocks = littleInt(header, 16);
            int totalChunks = (int) littleInt(header, 20);
            if (fileHeaderSize < 28 || chunkHeaderSize < 12 || blockSize == 0) throw new IOException("Invalid sparse image header.");
            skipFully(input, fileHeaderSize - 28L);
            output.setLength(totalBlocks * blockSize);
            long outputPosition = 0;
            byte[] buffer = new byte[1024 * 1024];
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = readBytes(input, chunkHeaderSize);
                int type = littleShort(chunk, 0);
                long chunkBlocks = littleInt(chunk, 4);
                long totalSize = littleInt(chunk, 8);
                long outputBytes = chunkBlocks * blockSize;
                if (totalSize < chunkHeaderSize) throw new IOException("Sparse chunk has an invalid size.");
                if (type == 0xCAC1) {
                    long payload = totalSize - chunkHeaderSize;
                    if (payload != outputBytes) throw new IOException("Sparse RAW chunk size mismatch.");
                    output.seek(outputPosition);
                    copy(input, output, outputBytes, buffer);
                } else if (type == 0xCAC2) {
                    if (totalSize - chunkHeaderSize != 4) throw new IOException("Sparse FILL chunk size mismatch.");
                    byte[] fill = readBytes(input, 4);
                    output.seek(outputPosition);
                    for (long written = 0; written < outputBytes;) {
                        int count = (int) Math.min(buffer.length, outputBytes - written);
                        for (int p = 0; p < count; p++) buffer[p] = fill[p % 4];
                        output.write(buffer, 0, count);
                        written += count;
                    }
                } else if (type == 0xCAC3) {
                    skipFully(input, totalSize - chunkHeaderSize);
                } else if (type == 0xCAC4) {
                    skipFully(input, totalSize - chunkHeaderSize);
                } else {
                    throw new IOException(String.format(Locale.US, "Unknown sparse chunk type 0x%04x", type));
                }
                outputPosition += outputBytes;
            }
        }
        return rawTarget;
    }

    private static boolean isSparse(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] magic = readBytes(input, 4);
            return littleInt(magic, 0) == 0xED26FF3AL;
        }
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static void copyMaybeGzip(File source, File target) throws IOException {
        try (InputStream raw = new FileInputStream(source);
             InputStream input = maybeCompressed(raw, source.getName());
             FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static InputStream maybeCompressed(InputStream input, String name) throws IOException {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".gz")) return new GZIPInputStream(input);
        if (lower.endsWith(".xz")) return new org.tukaani.xz.XZInputStream(input);
        return input;
    }

    private static void copy(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void copy(InputStream input, RandomAccessFile output, long bytes, byte[] buffer) throws IOException {
        long copied = 0;
        while (copied < bytes) {
            int want = (int) Math.min(buffer.length, bytes - copied);
            int read = input.read(buffer, 0, want);
            if (read < 0) throw new IOException("Unexpected end of sparse image.");
            output.write(buffer, 0, read);
            copied += read;
        }
    }

    private static byte[] readBytes(InputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = input.read(bytes, offset, size - offset);
            if (read < 0) throw new IOException("Unexpected end of image.");
            offset += read;
        }
        return bytes;
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long skipped = 0;
        while (skipped < bytes) {
            long value = input.skip(bytes - skipped);
            if (value > 0) {
                skipped += value;
                continue;
            }
            if (input.read() == -1) throw new IOException("Unexpected end of sparse image.");
            skipped++;
        }
    }

    private static long littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL) | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16) | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static int littleShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stripCompressionSuffix(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".gz")) return name.substring(0, name.length() - 3);
        if (lower.endsWith(".xz")) return name.substring(0, name.length() - 3);
        return name;
    }
}
