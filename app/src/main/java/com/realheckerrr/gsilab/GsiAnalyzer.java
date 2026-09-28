package com.realheckerrr.gsilab;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.GZIPInputStream;

/** Small, dependency-free GSI preflight analyzer. It never mounts or modifies an image. */
public final class GsiAnalyzer {
    private static final int SPARSE_MAGIC = 0xED26FF3A;

    private GsiAnalyzer() {}

    public static GsiAnalysis analyze(File input) throws IOException {
        if (input == null || !input.isFile()) {
            throw new IOException("The selected input is not a readable file.");
        }
        if (looksLikeZip(input)) {
            return analyzeZip(input);
        }
        return analyzeImage(input, "raw input");
    }

    private static GsiAnalysis analyzeZip(File input) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ZipEntry system = null;
        int entries = 0;
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                entries++;
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.equals("system.img") || name.endsWith("/system.img")
                        || name.equals("system.img.gz") || name.endsWith("/system.img.gz")) {
                    if (system == null || name.equals("system.img")) system = entry;
                }
            }
            if (system == null) {
                errors.add("ZIP does not contain system.img.");
                return new GsiAnalysis(input.getName(), "ZIP", "missing", 0, "unknown", sha256(input), false, warnings, errors);
            }
            ImageScan scan;
            String systemName = system.getName();
            try (InputStream raw = zip.getInputStream(system);
                 InputStream stream = maybeGzip(raw, systemName)) {
                scan = scan(stream);
            }
            if (systemName.toLowerCase(Locale.US).endsWith(".gz")) {
                warnings.add("system.img.gz was decompressed before header analysis.");
            }
            if (scan.format.equals("unknown")) {
                warnings.add("system.img is not identified as Android sparse or raw ext4 from its header.");
            }
            warnings.add("ZIP contains " + entries + " entries; only system.img was analyzed.");
            boolean candidate = errors.isEmpty() && scan.bytes > 0 && !scan.format.equals("unknown");
            return new GsiAnalysis(input.getName(), "ZIP", system.getName(), scan.bytes, scan.format,
                    scan.sha256, candidate, warnings, errors);
        }
    }

    private static GsiAnalysis analyzeImage(File input, String container) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ImageScan scan;
        try (InputStream raw = new FileInputStream(input);
             InputStream stream = maybeGzip(raw, input.getName())) {
            scan = scan(stream);
        }
        if (input.getName().toLowerCase(Locale.US).endsWith(".gz")) {
            warnings.add("gzip input was decompressed before header analysis.");
        }
        if (scan.format.equals("unknown")) {
            warnings.add("Image header is not recognized as Android sparse or raw ext4.");
        }
        boolean candidate = scan.bytes > 0 && !scan.format.equals("unknown");
        return new GsiAnalysis(input.getName(), container, input.getName(), scan.bytes, scan.format,
                scan.sha256, candidate, warnings, errors);
    }

    private static ImageScan scan(InputStream source) throws IOException {
        MessageDigest digest = sha256Digest();
        BufferedInputStream input = new BufferedInputStream(source);
        byte[] header = new byte[4096];
        int headerBytes = 0;
        int read;
        while (headerBytes < header.length && (read = input.read(header, headerBytes, header.length - headerBytes)) != -1) {
            headerBytes += read;
        }
        if (headerBytes > 0) digest.update(header, 0, headerBytes);
        long bytes = headerBytes;
        byte[] buffer = new byte[1024 * 1024];
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            bytes += read;
        }
        String format = detectFormat(header, headerBytes);
        return new ImageScan(bytes, format, hex(digest.digest()));
    }

    private static String detectFormat(byte[] header, int length) {
        if (length >= 4 && littleEndianInt(header, 0) == SPARSE_MAGIC) {
            return "Android sparse image";
        }
        if (length >= 1082 && (header[1080] & 0xFF) == 0x53 && (header[1081] & 0xFF) == 0xEF) {
            return "raw ext4 image";
        }
        return "unknown";
    }

    private static boolean looksLikeZip(File input) throws IOException {
        if (input.getName().toLowerCase(Locale.US).endsWith(".zip")) return true;
        try (InputStream stream = new FileInputStream(input)) {
            return stream.read() == 'P' && stream.read() == 'K';
        }
    }

    private static InputStream maybeGzip(InputStream input, String name) throws IOException {
        return name.toLowerCase(Locale.US).endsWith(".gz") ? new GZIPInputStream(input) : input;
    }

    private static long littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    public static String sha256(File input) throws IOException {
        try (InputStream stream = new FileInputStream(input)) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
            return hex(digest.digest());
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value & 0xFF));
        return out.toString();
    }

    private static final class ImageScan {
        final long bytes;
        final String format;
        final String sha256;

        ImageScan(long bytes, String format, String sha256) {
            this.bytes = bytes;
            this.format = format;
            this.sha256 = sha256;
        }
    }
}
