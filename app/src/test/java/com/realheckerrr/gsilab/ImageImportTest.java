package com.realheckerrr.gsilab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.tukaani.xz.XZOutputStream;

import org.junit.Test;

public final class ImageImportTest {
    @Test
    public void analyzesGzipGsiImage() throws Exception {
        byte[] ext4 = ext4Image(4096);
        File image = tempFile("system.img.gz");
        writeGzip(image, ext4);

        GsiAnalysis result = GsiAnalyzer.analyze(image);

        assertEquals("raw ext4 image", result.imageFormat);
        assertTrue(result.bootCandidate);
        assertTrue(result.warnings.get(0).contains("decompressed"));
    }

    @Test
    public void analyzesXzGsiImage() throws Exception {
        byte[] ext4 = ext4Image(4096);
        File image = tempFile("system.img.xz");
        writeXz(image, ext4);

        GsiAnalysis result = GsiAnalyzer.analyze(image);

        assertEquals("raw ext4 image", result.imageFormat);
        assertTrue(result.bootCandidate);
        assertTrue(result.warnings.get(0).contains("XZ"));
    }

    @Test
    public void analyzesGzipSystemEntryInsideZip() throws Exception {
        byte[] ext4 = ext4Image(4096);
        File archive = tempFile("gsi.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("system.img.gz"));
            zip.write(gzipBytes(ext4));
            zip.closeEntry();
        }

        GsiAnalysis result = GsiAnalyzer.analyze(archive);

        assertEquals("system.img.gz", result.systemEntry);
        assertEquals("raw ext4 image", result.imageFormat);
        assertTrue(result.bootCandidate);
    }

    @Test
    public void acceptsOfficialStyleGuestArchiveAndExpandsGzipAssets() throws Exception {
        byte[] ext4 = ext4Image(4096);
        File guest = tempFile("guest.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(guest))) {
            put(zip, "kernel-ranchu", new byte[]{1, 2, 3});
            put(zip, "ramdisk.img", new byte[]{4, 5});
            put(zip, "vendor.img.gz", gzipBytes(ext4));
            put(zip, "userdata.img.gz", gzipBytes(new byte[]{6, 7, 8}));
            put(zip, "cache.img", new byte[]{10, 11});
            put(zip, "encryptionkey.img", new byte[]{12, 13});
            put(zip, "libqemu-system-aarch64.so", new byte[]{9, 10});
        }

        GuestBundleAnalysis report = GuestBundleAnalyzer.analyze(guest);
        File gsi = tempFile("gsi.img.gz");
        writeGzip(gsi, ext4);
        File output = Files.createTempDirectory("gsi-vm-").toFile();
        BootAssets assets;
        try {
            assets = BootAssets.prepare(gsi, guest, output);
        } catch (IOException error) {
            throw new AssertionError("BootAssets failed: " + error.getMessage(), error);
        }

        assertTrue(report.bootCandidate);
        assertTrue(assets.ranchu);
        assertEquals("vendor.img.gz", report.vendor);
        assertArrayEquals(ext4, Files.readAllBytes(assets.vendor.toPath()));
        assertArrayEquals(ext4, Files.readAllBytes(assets.system.toPath()));
        assertEquals("userdata.img", assets.userdata.getName());
        assertEquals("cache.img", assets.cache.getName());
        assertEquals("encryptionkey.img", assets.encryptionKey.getName());
        assertEquals("libqemu-system-aarch64.so", assets.qemu.getName());
        assertEquals("cache.img", report.cache);
        assertEquals("encryptionkey.img", report.encryptionKey);
    }

    @Test
    public void extractsCuttlefishVendorFromSuperImage() throws Exception {
        byte[] vendor = ext4Image(4096);
        File superImage = tempFile("super.img");
        writeSuperImage(superImage, vendor);
        File guest = tempFile("cuttlefish.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(guest))) {
            put(zip, "kernel_16k", new byte[]{1, 2, 3});
            put(zip, "ramdisk_16k.img", new byte[]{4, 5});
            put(zip, "super.img", Files.readAllBytes(superImage.toPath()));
        }
        File gsi = tempFile("gsi.img");
        Files.write(gsi.toPath(), ext4Image(4096));
        File output = Files.createTempDirectory("cuttlefish-vm-").toFile();

        GuestBundleAnalysis report = GuestBundleAnalyzer.analyze(guest);
        BootAssets assets = BootAssets.prepare(gsi, guest, output);

        assertTrue(report.bootCandidate);
        assertTrue(report.vendor.contains("vendor logical partition"));
        assertTrue(assets.cuttlefish);
        assertArrayEquals(vendor, Files.readAllBytes(assets.vendor.toPath()));
    }

    @Test
    public void extractsPixelStyleBootAndVendorBootImages() throws Exception {
        byte[] kernel = new byte[]{9, 8, 7};
        byte[] ramdisk = new byte[]{6, 5, 4, 3};
        File guest = tempFile("pixel-guest.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(guest))) {
            put(zip, "boot.img", bootV3(kernel));
            put(zip, "vendor_boot.img", vendorBootV3(ramdisk));
            put(zip, "vendor.img", ext4Image(4096));
        }
        File gsi = tempFile("pixel-gsi.img");
        Files.write(gsi.toPath(), ext4Image(4096));
        File output = Files.createTempDirectory("pixel-boot-vm-").toFile();

        GuestBundleAnalysis report = GuestBundleAnalyzer.analyze(guest);
        BootAssets assets = BootAssets.prepare(gsi, guest, output);

        assertTrue(report.bootCandidate);
        assertTrue(report.kernel.contains("embedded kernel"));
        assertArrayEquals(kernel, Files.readAllBytes(assets.kernel.toPath()));
        assertArrayEquals(ramdisk, Files.readAllBytes(assets.ramdisk.toPath()));
    }

    @Test
    public void detectsErofsSystemImagesForRanchuFstab() throws Exception {
        File image = tempFile("erofs.img");
        try (RandomAccessFile output = new RandomAccessFile(image, "rw")) {
            output.setLength(4096);
            output.seek(1024);
            output.writeInt(Integer.reverseBytes(0xE0F5E1E2));
        }
        assertEquals("erofs", RanchuVendorPatcher.detectFilesystem(image));
    }

    @Test
    public void preservesRamdiskMetadataWhenPatchingFstab() throws Exception {
        ByteArrayOutputStream cpio = new ByteArrayOutputStream();
        appendCpio(cpio, "etc", 0040755, new byte[0], 1);
        appendCpio(cpio, "bin/sh", 0120777, "toybox".getBytes(StandardCharsets.UTF_8), 2);
        appendCpio(cpio, "fstab.ranchu", 0100644,
                "/dev/block/by-name/system /system ext4 ro,first_stage_mount\n"
                        .getBytes(StandardCharsets.UTF_8), 3);
        appendCpio(cpio, "TRAILER!!!", 0, new byte[0], 0);

        File source = tempFile("ramdisk.img");
        File target = tempFile("ramdisk.patched.img");
        Files.write(source.toPath(), cpio.toByteArray());
        RanchuRamdiskPatcher.patch(source, target);
        byte[] patched = gunzip(Files.readAllBytes(target.toPath()));

        assertEquals(0040755, cpioMode(patched, "etc"));
        assertEquals(0120777, cpioMode(patched, "bin/sh"));
        assertArrayEquals("toybox".getBytes(StandardCharsets.UTF_8), cpioContent(patched, "bin/sh"));
        assertTrue(new String(cpioContent(patched, "fstab.ranchu"), StandardCharsets.UTF_8)
                .contains("/dev/block/vdb /system"));
    }

    private static byte[] ext4Image(int size) {
        byte[] image = new byte[size];
        image[1080] = 0x53;
        image[1081] = (byte) 0xEF;
        return image;
    }

    private static File tempFile(String suffix) throws IOException {
        return Files.createTempFile("gsi-test-", "-" + suffix).toFile();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] bootV3(byte[] kernel) {
        int page = 4096;
        byte[] image = new byte[page + kernel.length];
        writeAscii(image, 0, "ANDROID!");
        writeInt(image, 8, kernel.length);
        writeInt(image, 12, 0);
        writeInt(image, 40, 3);
        System.arraycopy(kernel, 0, image, page, kernel.length);
        return image;
    }

    private static byte[] vendorBootV3(byte[] ramdisk) {
        int page = 4096;
        byte[] image = new byte[page + ramdisk.length];
        writeAscii(image, 0, "VNDRBOOT");
        writeInt(image, 8, 3);
        writeInt(image, 12, page);
        writeInt(image, 24, ramdisk.length);
        System.arraycopy(ramdisk, 0, image, page, ramdisk.length);
        return image;
    }

    private static void writeAscii(byte[] output, int offset, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, output, offset, bytes.length);
    }

    private static void writeInt(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeGzip(File target, byte[] bytes) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(new FileOutputStream(target))) {
            gzip.write(bytes);
        }
    }

    private static void writeXz(File target, byte[] bytes) throws IOException {
        try (XZOutputStream xz = new XZOutputStream(new FileOutputStream(target), new org.tukaani.xz.LZMA2Options())) {
            xz.write(bytes);
        }
    }

    private static byte[] gzipBytes(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private static void appendCpio(ByteArrayOutputStream output, String name, int mode,
                                   byte[] content, int inode) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] nameWithNull = new byte[nameBytes.length + 1];
        System.arraycopy(nameBytes, 0, nameWithNull, 0, nameBytes.length);
        StringBuilder header = new StringBuilder("070701");
        long[] fields = {inode, mode, 0, 0, 1, 0, content.length, 0, 0, 0, 0, nameWithNull.length, 0};
        for (long field : fields) header.append(String.format(Locale.US, "%08x", field));
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(nameWithNull);
        pad4(output);
        output.write(content);
        pad4(output);
    }

    private static int cpioMode(byte[] data, String wanted) {
        int position = cpioPosition(data, wanted);
        return position < 0 ? -1 : readHex(data, position + 14);
    }

    private static byte[] cpioContent(byte[] data, String wanted) {
        int position = cpioPosition(data, wanted);
        if (position < 0) throw new AssertionError("Missing CPIO entry: " + wanted);
        int nameSize = readHex(data, position + 94);
        int size = readHex(data, position + 54);
        int start = align4(position + 110 + nameSize);
        return java.util.Arrays.copyOfRange(data, start, start + size);
    }

    private static int cpioPosition(byte[] data, String wanted) {
        for (int position = 0; position + 110 <= data.length; ) {
            String magic = new String(data, position, 6, StandardCharsets.US_ASCII);
            if (!("070701".equals(magic) || "070702".equals(magic))) return -1;
            int nameSize = readHex(data, position + 94);
            int size = readHex(data, position + 54);
            String name = new String(data, position + 110, nameSize - 1, StandardCharsets.UTF_8);
            if (wanted.equals(name)) return position;
            int contentEnd = align4(position + 110 + nameSize) + size;
            position = align4(contentEnd);
        }
        return -1;
    }

    private static int readHex(byte[] data, int offset) {
        return Integer.parseUnsignedInt(new String(data, offset, 8, StandardCharsets.US_ASCII), 16);
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    private static void pad4(ByteArrayOutputStream output) {
        while ((output.size() & 3) != 0) output.write(0);
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static void writeSuperImage(File target, byte[] vendor) throws IOException {
        long metadataOffset = 8192L;
        int metadataHeaderSize = 128;
        int partitionsOffset = 0;
        int extentsOffset = 52;
        int blockDevicesOffset = 76;
        long vendorOffset = 128L * 512L;
        try (RandomAccessFile output = new RandomAccessFile(target, "rw")) {
            output.setLength(131072L);
            output.seek(0);
            writeInt(output, 0x616C4467L);
            writeInt(output, 52);
            output.seek(40);
            writeInt(output, 4096);
            writeInt(output, 1);
            writeInt(output, 4096);

            output.seek(metadataOffset);
            writeInt(output, 0x414C5030L);
            writeShort(output, 10);
            writeShort(output, 0);
            writeInt(output, metadataHeaderSize);
            output.seek(metadataOffset + 44);
            writeInt(output, 52 + 24 + 68);
            output.seek(metadataOffset + 80);
            writeInt(output, partitionsOffset);
            writeInt(output, 1);
            writeInt(output, 52);
            writeInt(output, extentsOffset);
            writeInt(output, 1);
            writeInt(output, 24);
            writeInt(output, 76);
            writeInt(output, 0);
            writeInt(output, 0);
            writeInt(output, blockDevicesOffset);
            writeInt(output, 1);
            writeInt(output, 68);

            long tableBase = metadataOffset + metadataHeaderSize;
            output.seek(tableBase);
            writeAscii(output, "vendor_a", 36);
            writeInt(output, 0);
            writeInt(output, 0);
            writeInt(output, 1);
            writeInt(output, 0);
            writeLong(output, 8);
            writeInt(output, 0);
            writeLong(output, 128);
            writeInt(output, 0);
            output.seek(tableBase + blockDevicesOffset);
            writeLong(output, 128);
            writeInt(output, 4096);
            writeInt(output, 0);
            writeLong(output, 131072);
            writeAscii(output, "super", 36);
            writeInt(output, 0);
            output.seek(vendorOffset);
            output.write(vendor);
        }
    }

    private static void writeAscii(RandomAccessFile output, String value, int size) throws IOException {
        byte[] bytes = new byte[size];
        byte[] source = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(source, 0, bytes, 0, Math.min(source.length, bytes.length));
        output.write(bytes);
    }

    private static void writeShort(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(RandomAccessFile output, long value) throws IOException {
        for (int shift = 0; shift < 32; shift += 8) output.write((int) (value >>> shift) & 0xFF);
    }

    private static void writeLong(RandomAccessFile output, long value) throws IOException {
        for (int shift = 0; shift < 64; shift += 8) output.write((int) (value >>> shift) & 0xFF);
    }
}
