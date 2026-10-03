package com.realheckerrr.gsilab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

/** Extracts the selected system image from a 7z-published GSI archive. */
final class SevenZImageExtractor {
    private static final byte[] MAGIC = {
            0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c
    };

    private SevenZImageExtractor() {}

    static boolean looksLike7z(File file) throws IOException {
        if (file == null || file.length() < MAGIC.length) return false;
        try (InputStream input = new java.io.FileInputStream(file)) {
            for (byte expected : MAGIC) {
                if (input.read() != (expected & 0xff)) return false;
            }
            return true;
        }
    }

    static File extractSystemImage(File archive, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create the 7z extraction directory.");
        }
        try (SevenZFile sevenZ = new SevenZFile(archive)) {
            SevenZArchiveEntry selected = null;
            int selectedRank = Integer.MAX_VALUE;
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName() == null) continue;
                int rank = imageRank(entry.getName());
                if (rank < selectedRank) {
                    selected = entry;
                    selectedRank = rank;
                }
            }
            if (selected == null) {
                throw new IOException("7z GSI does not contain system.img, a system variant, or super.img.");
            }
            try (InputStream input = sevenZ.getInputStream(selected);
                 FileOutputStream outputStream = new FileOutputStream(output)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) outputStream.write(buffer, 0, read);
            }
        }
        return output;
    }

    private static int imageRank(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.US);
        if (base.equals("system.img")) return 0;
        if (base.equals("system_a.img") || base.equals("system_b.img")
                || (base.startsWith("system-") && base.endsWith(".img"))) return 1;
        if (base.equals("super.img")) return 2;
        return Integer.MAX_VALUE;
    }
}
