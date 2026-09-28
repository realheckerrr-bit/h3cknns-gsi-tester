package com.realheckerrr.gsilab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Extracts kernel and ramdisk payloads from common Android boot image headers. */
final class AndroidBootImage {
    private static final byte[] BOOT_MAGIC = "ANDROID!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] VENDOR_BOOT_MAGIC = "VNDRBOOT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final long DEFAULT_PAGE_SIZE = 4096L;

    private AndroidBootImage() {}

    static File extractKernel(File image, File output) throws IOException {
        Header header = readHeader(image);
        if (header.kernelSize == 0) return null;
        return copyRange(image, header.kernelOffset(), header.kernelSize, output);
    }

    static File extractRamdisk(File image, File output) throws IOException {
        Header header = readHeader(image);
        if (header.ramdiskSize == 0) return null;
        return copyRange(image, header.ramdiskOffset(), header.ramdiskSize, output);
    }

    static File extractVendorRamdisk(File image, File output) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(image, "r")) {
            byte[] magic = new byte[VENDOR_BOOT_MAGIC.length];
            input.readFully(magic);
            if (!matches(magic, VENDOR_BOOT_MAGIC)) throw new IOException("Not an Android vendor_boot image.");
            long pageSize = uint32(input, 12);
            long ramdiskSize = uint32(input, 24);
            if (pageSize == 0) pageSize = DEFAULT_PAGE_SIZE;
            if (ramdiskSize == 0) return null;
            return copyRange(image, pageSize, ramdiskSize, output);
        }
    }

    private static Header readHeader(File image) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(image, "r")) {
            byte[] magic = new byte[BOOT_MAGIC.length];
            input.readFully(magic);
            if (!matches(magic, BOOT_MAGIC)) throw new IOException("Not an Android boot image.");
            long headerVersion = uint32(input, 40);
            if (headerVersion >= 3) {
                return new Header(uint32(input, 8), uint32(input, 12), DEFAULT_PAGE_SIZE);
            }
            long pageSize = uint32(input, 36);
            if (pageSize == 0) pageSize = 2048L;
            return new Header(uint32(input, 8), uint32(input, 16), pageSize);
        }
    }

    private static File copyRange(File image, long offset, long size, File output) throws IOException {
        if (size < 0 || offset < 0 || offset > image.length() || size > image.length() - offset) {
            throw new IOException("Android boot image payload exceeds the file boundary.");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create boot image output directory.");
        }
        try (RandomAccessFile input = new RandomAccessFile(image, "r");
             FileOutputStream stream = new FileOutputStream(output)) {
            input.seek(offset);
            byte[] buffer = new byte[1024 * 1024];
            long remaining = size;
            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, wanted);
                if (read < 0) throw new IOException("Unexpected end of Android boot image.");
                stream.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return output;
    }

    private static long uint32(RandomAccessFile input, long offset) throws IOException {
        input.seek(offset);
        return (input.readUnsignedByte())
                | ((long) input.readUnsignedByte() << 8)
                | ((long) input.readUnsignedByte() << 16)
                | ((long) input.readUnsignedByte() << 24);
    }

    private static boolean matches(byte[] actual, byte[] expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) if (actual[i] != expected[i]) return false;
        return true;
    }

    private static final class Header {
        final long kernelSize;
        final long ramdiskSize;
        final long pageSize;

        Header(long kernelSize, long ramdiskSize, long pageSize) {
            this.kernelSize = kernelSize;
            this.ramdiskSize = ramdiskSize;
            this.pageSize = pageSize;
        }

        long kernelOffset() {
            return pageSize;
        }

        long ramdiskOffset() {
            return align(kernelOffset() + kernelSize, pageSize);
        }

        private static long align(long value, long alignment) {
            return ((value + alignment - 1) / alignment) * alignment;
        }
    }
}
