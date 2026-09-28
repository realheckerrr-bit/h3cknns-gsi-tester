package com.realheckerrr.gsilab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
}
