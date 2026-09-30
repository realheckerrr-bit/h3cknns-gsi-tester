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

import org.tukaani.xz.XZInputStream;

/** Small GSI preflight analyzer. It never mounts or modifies an image. */
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
        ZipEntry systemVariant = null;
        ZipEntry superImage = null;
        int entries = 0;
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                entries++;
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1);
                String imageBase = stripCompressionSuffix(base).toLowerCase(Locale.US);
                if (imageBase.equals("system.img")) {
                    if (system == null || base.equalsIgnoreCase("system.img")) system = entry;
                }
                if (systemVariant == null && (imageBase.equals("system_a.img")
                        || imageBase.equals("system_b.img")
                        || (imageBase.startsWith("system-") && imageBase.endsWith(".img")))) {
                    systemVariant = entry;
                }
                if (superImage == null && imageBase.equals("super.img")) superImage = entry;
            }
            ZipEntry selected = system != null ? system : (systemVariant != null ? systemVariant : superImage);
            if (selected == null) {
                errors.add("ZIP does not contain system.img or super.img.");
                return new GsiAnalysis(input.getName(), "ZIP", "missing", 0, "unknown", sha256(input), false, warnings, errors);
            }
            ImageScan scan;
            String systemName = selected.getName();
            try (InputStream raw = zip.getInputStream(selected);
                 InputStream stream = maybeCompressed(raw, systemName)) {
                scan = scan(stream);
            }
            if (systemName.toLowerCase(Locale.US).endsWith(".gz")) {
                warnings.add("system.img.gz was decompressed before header analysis.");
            }
            if (systemName.toLowerCase(Locale.US).endsWith(".xz")) {
                warnings.add("system.img.xz was decompressed before header analysis.");
            }
            if (systemName.toLowerCase(Locale.US).endsWith(".lz4")) {
                warnings.add("system.img.lz4 was decompressed before header analysis.");
            }
            if (scan.format.equals("unknown")) {
                warnings.add("system.img is not identified as Android sparse, ext4, EROFS, or F2FS from its header.");
            }
            if (system == null && systemVariant == null) {
                warnings.add("ZIP contains a dynamic-partition super image; the system logical partition will be extracted during boot preparation.");
            } else {
                warnings.add("ZIP contains " + entries + " entries; only the selected system image was analyzed.");
            }
            boolean candidate = errors.isEmpty() && scan.bytes > 0 && !scan.format.equals("unknown");
            String selectedName = system == null && systemVariant == null
                    ? systemName + " (system logical partition)" : systemName;
            return new GsiAnalysis(input.getName(), "ZIP", selectedName, scan.bytes, scan.format,
                    scan.sha256, candidate, warnings, errors);
        }
    }

    private static GsiAnalysis analyzeImage(File input, String container) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ImageScan scan;
        try (InputStream raw = new FileInputStream(input);
             InputStream stream = maybeCompressed(raw, input.getName())) {
            scan = scan(stream);
        }
        if (input.getName().toLowerCase(Locale.US).endsWith(".gz")) {
            warnings.add("gzip input was decompressed before header analysis.");
        }
        if (input.getName().toLowerCase(Locale.US).endsWith(".xz")) {
            warnings.add("XZ input was decompressed before header analysis.");
        }
        if (input.getName().toLowerCase(Locale.US).endsWith(".lz4")) {
            warnings.add("LZ4 input was decompressed before header analysis.");
        }
        if (scan.format.equals("unknown")) {
            warnings.add("Image header is not recognized as Android sparse, ext4, EROFS, or F2FS.");
        }
        boolean candidate = scan.bytes > 0 && !scan.format.equals("unknown");
        return new GsiAnalysis(input.getName(), container, input.getName(), scan.bytes, scan.format,
                scan.sha256, candidate, warnings, errors);
    }

    private static ImageScan scan(InputStream source) throws IOException {
        MessageDigest digest = sha256Digest();
        BufferedInputStream input = new BufferedInputStream(source);
        // Real super images place their geometry at byte 4096, so the
        // preflight window must include that offset as well as filesystem
        // signatures near the beginning of the image.
        byte[] header = new byte[8192];
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
        if ((length >= 4 && littleEndianInt(header, 0) == 0x616C4467L)
                || (length >= 4100 && littleEndianInt(header, 4096) == 0x616C4467L)) {
            return "Android dynamic-partition super image";
        }
        if (length >= 1028 && littleEndianInt(header, 1024) == 0xE0F5E1E2L) {
            return "raw EROFS image";
        }
        if (length >= 1028 && littleEndianInt(header, 1024) == 0xF2F52010L) {
            return "raw F2FS image";
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
