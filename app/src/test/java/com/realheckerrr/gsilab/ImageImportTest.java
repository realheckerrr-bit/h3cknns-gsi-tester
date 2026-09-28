package com.realheckerrr.gsilab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        assertEquals("vendor.img.gz", report.vendor);
        assertArrayEquals(ext4, Files.readAllBytes(assets.vendor.toPath()));
        assertArrayEquals(ext4, Files.readAllBytes(assets.system.toPath()));
        assertEquals("userdata.img", assets.userdata.getName());
        assertEquals("libqemu-system-aarch64.so", assets.qemu.getName());
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

    private static void writeGzip(File target, byte[] bytes) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(new FileOutputStream(target))) {
            gzip.write(bytes);
        }
    }

    private static byte[] gzipBytes(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
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
