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

    private BootAssets(File system, File kernel, File ramdisk, File vendor, File userdata) {
        this.system = system;
        this.kernel = kernel;
        this.ramdisk = ramdisk;
        this.vendor = vendor;
        this.userdata = userdata;
    }

    public static BootAssets prepare(File gsi, File guestBundle, File output) throws IOException {
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create VM working directory.");
        File systemSource = new File(output, "system.source.img");
        if (gsi.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            copyEntry(gsi, systemSource, "system.img", "system.img.gz");
        } else {
            copyMaybeGzip(gsi, systemSource);
        }
        File system = materializeImage(systemSource, new File(output, "system.img"));

        File kernel = copyBundleEntry(guestBundle, output, "kernel", "kernel.gz", "kernel-ranchu", "kernel-ranchu.gz",
                "kernel-ranchu-64", "kernel-ranchu-64.gz");
        File ramdisk = copyBundleEntry(guestBundle, output, "ramdisk.img", "ramdisk.img.gz");
        File vendor = copyBundleEntry(guestBundle, output, "vendor.img", "vendor.img.gz", "vendor_a.img", "vendor_a.img.gz");
        File userdata = copyBundleEntry(guestBundle, output, "userdata.img", "userdata.img.gz",
                "userdata-qemu.img", "userdata-qemu.img.gz");
        if (kernel == null || ramdisk == null || vendor == null) {
            throw new IOException("Guest bundle is missing kernel, ramdisk.img, or vendor.img.");
        }
        if (vendor != null && isSparse(vendor)) vendor = materializeImage(vendor, new File(output, "vendor.raw.img"));
        return new BootAssets(system, kernel, ramdisk, vendor, userdata);
    }

    private static File copyBundleEntry(File bundle, File output, String... names) throws IOException {
        try (ZipFile zip = new ZipFile(bundle)) {
            ZipEntry entry = findEntry(zip, names);
            if (entry == null) return null;
            String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
            File target = new File(output, safeName(stripGzipSuffix(base)));
            try (InputStream raw = zip.getInputStream(entry);
                 InputStream input = maybeGzip(raw, base);
                 FileOutputStream out = new FileOutputStream(target)) {
                copy(input, out);
            }
            return target;
        }
    }

    private static void copyEntry(File archive, File target, String... wantedBases) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = findEntry(zip, wantedBases);
            if (entry == null) throw new IOException("GSI ZIP does not contain system.img or system.img.gz.");
            String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
            try (InputStream raw = zip.getInputStream(entry);
                 InputStream input = maybeGzip(raw, base);
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
             InputStream input = maybeGzip(raw, source.getName());
             FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static InputStream maybeGzip(InputStream input, String name) throws IOException {
        return name.toLowerCase(Locale.US).endsWith(".gz") ? new GZIPInputStream(input) : input;
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

    private static String stripGzipSuffix(String name) {
        return name.toLowerCase(Locale.US).endsWith(".gz")
                ? name.substring(0, name.length() - 3)
                : name;
    }
}
