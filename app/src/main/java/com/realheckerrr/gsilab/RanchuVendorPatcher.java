package com.realheckerrr.gsilab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Patches the Ranchu vendor fstab without rebuilding the ext4 filesystem. */
public final class RanchuVendorPatcher {
    private static final long SECTOR_SIZE = 512L;

    private RanchuVendorPatcher() {}

    public static void patch(File vendor) throws IOException {
        long length = vendor.length();
        if (length > Integer.MAX_VALUE) throw new IOException("vendor image is too large to scan safely");
        try (RandomAccessFile file = new RandomAccessFile(vendor, "rw");
             FileChannel channel = file.getChannel()) {
            MappedByteBuffer image = channel.map(FileChannel.MapMode.READ_WRITE, 0, length);
            int size = (int) length;
            int start = 0;
            int changed = 0;
            for (int end = 0; end <= size; end++) {
                if (end != size && image.get(end) != '\n') continue;
                byte[] original = new byte[end - start];
                image.position(start);
                image.get(original);
                byte[] updated = transform(original);
                if (!Arrays.equals(original, updated)) {
                    image.position(start);
                    image.put(updated);
                    changed++;
                }
                start = end + 1;
            }
            changed += disableSensorService(image, size);
            image.force();
        }
    }

    private static int disableSensorService(MappedByteBuffer image, int size) {
        byte[] needle = "service vendor.sensors-hal-multihal".getBytes(StandardCharsets.US_ASCII);
        int cursor = 0;
        int changed = 0;
        while (true) {
            int match = find(image, size, needle, cursor);
            if (match < 0) return changed;
            int start = lineStart(image, match);
            int end = lineEnd(image, size, start);
            if (image.get(start) != '#') {
                image.put(start, (byte) '#');
                changed++;
            }
            int position = end + 1;
            while (position < size) {
                int nextEnd = lineEnd(image, size, position);
                if (nextEnd > position && image.get(position) != ' ' && image.get(position) != '\t') break;
                if (nextEnd > position) {
                    int first = position;
                    while (first < nextEnd && (image.get(first) == ' ' || image.get(first) == '\t')) first++;
                    if (first < nextEnd && image.get(first) != '#') {
                        image.put(first, (byte) '#');
                        changed++;
                    }
                }
                position = nextEnd + 1;
            }
            cursor = position;
        }
    }

    private static int find(MappedByteBuffer image, int size, byte[] needle, int from) {
        outer: for (int i = from; i + needle.length <= size; i++) {
            for (int j = 0; j < needle.length; j++) if (image.get(i + j) != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static int lineStart(MappedByteBuffer image, int position) {
        while (position > 0 && image.get(position - 1) != '\n') position--;
        return position;
    }

    private static int lineEnd(MappedByteBuffer image, int size, int position) {
        while (position < size && image.get(position) != '\n') position++;
        return position;
    }

    /** Extracts the first non-empty GPT partition used by the official Ranchu vendor.img wrapper. */
    public static File extractFirstGptPartition(File source, File target) throws IOException {
        if (source.length() < 1024) return source;
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            input.seek(SECTOR_SIZE);
            byte[] header = new byte[92];
            input.readFully(header);
            if (!"EFI PART".equals(new String(header, 0, 8, StandardCharsets.US_ASCII))) return source;
            long entriesLba = littleLong(header, 72);
            long entryCount = littleInt(header, 80);
            long entrySize = littleInt(header, 84);
            if (entriesLba <= 0 || entryCount <= 0 || entrySize < 48 || entrySize > 4096) return source;
            long tableOffset = entriesLba * SECTOR_SIZE;
            if (tableOffset < 0 || tableOffset > source.length() - entrySize) return source;
            input.seek(tableOffset);
            byte[] entry = new byte[(int) entrySize];
            input.readFully(entry);
            long firstLba = littleLong(entry, 32);
            long lastLba = littleLong(entry, 40);
            if (firstLba <= 0 || lastLba < firstLba) return source;
            long offset = firstLba * SECTOR_SIZE;
            long length = (lastLba - firstLba + 1) * SECTOR_SIZE;
            if (offset < 0 || length <= 0 || offset > source.length() || length > source.length() - offset) {
                return source;
            }
            if (target.getParentFile() != null && !target.getParentFile().isDirectory()
                    && !target.getParentFile().mkdirs()) throw new IOException("Cannot create vendor output directory.");
            input.seek(offset);
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[1024 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Unexpected end of GPT vendor partition.");
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            return target;
        }
    }

    private static byte[] transform(byte[] line) throws IOException {
        String text = new String(line, StandardCharsets.UTF_8);
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return line;
        String[] columns = trimmed.split("\\s+");
        if (columns.length < 2) return line;
        String device = columns[0];
        String mountpoint = columns[1];
        if ("/metadata".equals(mountpoint) || device.endsWith("/metadata")) {
            columns = new String[]{"tmpfs", "/metadata", "tmpfs", "mode=0755,uid=0,gid=0", "wait,first_stage_mount"};
            return fit(String.join(" ", columns).getBytes(StandardCharsets.UTF_8), line.length);
        }
        if ("/data".equals(mountpoint)) {
            columns[0] = "/dev/block/vdc";
            if (columns.length > 2 && ("f2fs".equals(columns[2]) || "erofs".equals(columns[2]))) {
                columns[2] = "ext4";
            }
            if (columns.length > 3) {
                StringBuilder flags = new StringBuilder();
                for (String flag : columns[3].split(",")) {
                    if (!("noatime".equals(flag) || "nosuid".equals(flag) || "nodev".equals(flag)
                            || "errors=panic".equals(flag))) continue;
                    if (flags.length() > 0) flags.append(',');
                    flags.append(flag);
                }
                columns[3] = flags.length() == 0 ? "defaults" : flags.toString();
            }
            if (columns.length > 4) {
                columns[4] = "wait";
                columns = Arrays.copyOf(columns, 5);
            }
            return fit(String.join(" ", columns).getBytes(StandardCharsets.UTF_8), line.length);
        }
        if (!trimmed.contains("first_stage_mount") || !trimmed.contains("logical")) return line;
        if ("/system".equals(mountpoint)) columns[0] = "/dev/block/vdb";
        else if ("/vendor".equals(mountpoint)) columns[0] = "/dev/block/vda";
        else return comment(line);
        for (int i = 0; i < columns.length; i++) {
            StringBuilder cleaned = new StringBuilder();
            for (String flag : columns[i].split(",")) {
                if ("logical".equals(flag) || "avb=vbmeta".equals(flag)) continue;
                if (cleaned.length() > 0) cleaned.append(',');
                cleaned.append(flag);
            }
            columns[i] = cleaned.toString();
        }
        return fit(String.join(" ", columns).getBytes(StandardCharsets.UTF_8), line.length);
    }

    private static byte[] comment(byte[] line) {
        if (line.length == 0 || line[0] == '#') return line;
        byte[] updated = line.clone();
        updated[0] = '#';
        return updated;
    }

    private static byte[] fit(byte[] line, int length) throws IOException {
        if (line.length > length) throw new IOException("Patched fstab line is longer than its ext4 slot.");
        byte[] result = Arrays.copyOf(line, length);
        Arrays.fill(result, line.length, length, (byte) ' ');
        return result;
    }

    private static long littleLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) value |= (bytes[offset + i] & 0xffL) << (8 * i);
        return value;
    }

    private static long littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
