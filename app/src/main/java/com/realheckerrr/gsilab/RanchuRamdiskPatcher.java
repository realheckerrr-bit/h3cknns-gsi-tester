package com.realheckerrr.gsilab;

import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Rewrites the Ranchu first-stage fstab and keeps the kernel modules in the ramdisk. */
public final class RanchuRamdiskPatcher {
    private static final int LZ4_MAGIC = 0x184C2102;

    private RanchuRamdiskPatcher() {}

    public static File patch(File source, File target) throws IOException {
        byte[] encoded = readAll(source);
        if (!looksLikeRamdisk(encoded)) return source;
        byte[] raw = unpack(encoded, source.getName());
        List<Entry> entries = parseCpio(raw);
        boolean changed = false;
        List<Entry> patched = new ArrayList<>();
        for (Entry entry : entries) {
            String base = entry.name.substring(entry.name.lastIndexOf('/') + 1);
            if ("fstab.ranchu".equals(base) || "fstab.ranchu.initrd".equals(base)) {
                PatchResult result = patchFstab(entry.content);
                entry = new Entry(entry.name, result.content);
                changed |= result.changed;
            }
            patched.add(entry);
        }
        if (!changed) {
            patched.add(new Entry("fstab.ranchu", (
                    "/dev/block/vdb /system ext4 ro wait,first_stage_mount\n"
                            + "/dev/block/vda /vendor ext4 ro wait,first_stage_mount\n")
                    .getBytes(StandardCharsets.UTF_8)));
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create ramdisk output directory.");
        }
        try (FileOutputStream output = new FileOutputStream(target);
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(buildCpio(patched));
        }
        return target;
    }

    private static boolean looksLikeRamdisk(byte[] data) {
        if (data.length >= 6) {
            String magic = ascii(data, 0, 6);
            if ("070701".equals(magic) || "070702".equals(magic)) return true;
        }
        return (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b)
                || (data.length >= 6 && data[0] == (byte) 0xfd && data[1] == '7'
                && data[2] == 'z' && data[3] == 'X' && data[4] == 'Z' && data[5] == 0)
                || (data.length >= 4 && littleInt(data, 0) == LZ4_MAGIC);
    }

    private static byte[] unpack(byte[] encoded, String name) throws IOException {
        String lower = name.toLowerCase(Locale.US);
        if (encoded.length >= 2 && (encoded[0] & 0xff) == 0x1f && (encoded[1] & 0xff) == 0x8b) {
            return readAll(new GZIPInputStream(new ByteArrayInputStream(encoded)));
        }
        if (encoded.length >= 6 && encoded[0] == (byte) 0xfd && encoded[1] == '7'
                && encoded[2] == 'z' && encoded[3] == 'X' && encoded[4] == 'Z' && encoded[5] == 0) {
            return readAll(new XZInputStream(new ByteArrayInputStream(encoded)));
        }
        if (encoded.length >= 4 && littleInt(encoded, 0) == LZ4_MAGIC) return decodeLz4(encoded);
        if (lower.endsWith(".gz")) return readAll(new GZIPInputStream(new ByteArrayInputStream(encoded)));
        if (lower.endsWith(".xz")) return readAll(new XZInputStream(new ByteArrayInputStream(encoded)));
        return encoded;
    }

    private static byte[] decodeLz4(byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length * 2);
        int offset = 0;
        while (offset + 8 <= input.length) {
            int magic = littleInt(input, offset);
            if (magic != LZ4_MAGIC) {
                int next = findMagic(input, offset + 1);
                if (next < 0) break;
                offset = next;
                continue;
            }
            offset += 4;
            while (offset + 4 <= input.length) {
                int blockSize = littleInt(input, offset);
                offset += 4;
                if (blockSize == 0) break;
                boolean raw = (blockSize & 0x80000000) != 0;
                int size = blockSize & 0x7fffffff;
                if (size < 0 || size > input.length - offset) throw new IOException("Truncated legacy LZ4 block.");
                if (raw) {
                    output.write(input, offset, size);
                } else {
                    decodeBlock(input, offset, size, output);
                }
                offset += size;
            }
        }
        if (output.size() == 0) throw new IOException("The Ranchu ramdisk has no decodable LZ4 payload.");
        return output.toByteArray();
    }

    private static void decodeBlock(byte[] input, int offset, int length, ByteArrayOutputStream output)
            throws IOException {
        int end = offset + length;
        int position = offset;
        while (position < end) {
            int token = input[position++] & 0xff;
            int literalLength = token >>> 4;
            if (literalLength == 15) {
                int[] cursor = {position};
                literalLength += readLz4Length(input, end, cursor);
                position = cursor[0];
            }
            if (literalLength > end - position) throw new IOException("Truncated legacy LZ4 literals.");
            output.write(input, position, literalLength);
            position += literalLength;
            if (position == end) break;
            if (position + 2 > end) throw new IOException("Truncated legacy LZ4 match.");
            int matchOffset = (input[position] & 0xff) | ((input[position + 1] & 0xff) << 8);
            position += 2;
            if (matchOffset == 0 || matchOffset > output.size()) throw new IOException("Invalid legacy LZ4 match offset.");
            int matchLength = token & 15;
            if (matchLength == 15) {
                int[] cursor = {position};
                matchLength += readLz4Length(input, end, cursor);
                position = cursor[0];
            }
            matchLength += 4;
            byte[] current = output.toByteArray();
            int start = current.length - matchOffset;
            for (int i = 0; i < matchLength; i++) output.write(current[start + i % matchOffset]);
        }
    }

    private static int readLz4Length(byte[] input, int end, int[] cursor) throws IOException {
        int length = 0;
        while (cursor[0] < end) {
            int value = input[cursor[0]++] & 0xff;
            length += value;
            if (value != 255) return length;
        }
        throw new IOException("Truncated legacy LZ4 length.");
    }

    private static int findMagic(byte[] input, int start) {
        for (int i = start; i + 4 <= input.length; i++) if (littleInt(input, i) == LZ4_MAGIC) return i;
        return -1;
    }

    private static List<Entry> parseCpio(byte[] data) throws IOException {
        List<Entry> entries = new ArrayList<>();
        int cursor = 0;
        while (cursor < data.length) {
            int start = findCpio(data, cursor);
            if (start < 0) break;
            int position = start;
            while (position + 110 <= data.length) {
                String magic = ascii(data, position, 6);
                if (!("070701".equals(magic) || "070702".equals(magic))) break;
                int size = hex(data, position + 54, 8);
                int nameSize = hex(data, position + 94, 8);
                if (size < 0 || nameSize < 1 || position + 110 + nameSize > data.length) break;
                int nameStart = position + 110;
                String name = new String(data, nameStart, nameSize - 1, StandardCharsets.UTF_8);
                int contentStart = align4(nameStart + nameSize);
                int contentEnd = contentStart + size;
                if (contentEnd < contentStart || contentEnd > data.length) break;
                if ("TRAILER!!!".equals(name)) {
                    cursor = align4(contentEnd);
                    break;
                }
                byte[] content = new byte[size];
                System.arraycopy(data, contentStart, content, 0, size);
                entries.add(new Entry(name, content));
                position = align4(contentEnd);
                cursor = position;
            }
            if (cursor <= start) cursor = start + 6;
        }
        if (entries.isEmpty()) throw new IOException("The Ranchu ramdisk contains no newc CPIO entries.");
        return entries;
    }

    private static int findCpio(byte[] data, int start) {
        for (int i = start; i + 6 <= data.length; i++) {
            String magic = ascii(data, i, 6);
            if ("070701".equals(magic) || "070702".equals(magic)) return i;
        }
        return -1;
    }

    private static PatchResult patchFstab(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        boolean changed = false;
        String[] lines = text.split("(?<=\\n)", -1);
        for (String line : lines) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                result.append(line);
                continue;
            }
            String[] columns = stripped.split("\\s+");
            if (columns.length < 2) {
                result.append(line);
                continue;
            }
            String device = columns[0];
            String mountpoint = columns[1];
            boolean mountEntry = device.startsWith("/") || "none".equals(device) || "tmpfs".equals(device);
            if (mountEntry && ("/metadata".equals(mountpoint) || device.endsWith("/metadata"))) {
                result.append("tmpfs /metadata tmpfs mode=0755 wait,first_stage_mount");
                if (line.endsWith("\n")) result.append('\n');
                changed = true;
                continue;
            }
            if (!stripped.contains("first_stage_mount") || !stripped.contains("logical")) {
                result.append(line);
                continue;
            }
            if (("/system".equals(mountpoint) || "/vendor".equals(mountpoint))
                    && columns.length > 2 && "erofs".equals(columns[2])) {
                result.append('#').append(line.startsWith("#") ? line.substring(1) : line);
                changed = true;
                continue;
            }
            if ("/system".equals(mountpoint)) columns[0] = "/dev/block/vdb";
            else if ("/vendor".equals(mountpoint)) columns[0] = "/dev/block/vda";
            else {
                result.append('#').append(line.startsWith("#") ? line.substring(1) : line);
                changed = true;
                continue;
            }
            for (int i = 0; i < columns.length; i++) {
                StringBuilder flags = new StringBuilder();
                for (String flag : columns[i].split(",")) {
                    if ("logical".equals(flag) || "avb=vbmeta".equals(flag)) continue;
                    if (flags.length() > 0) flags.append(',');
                    flags.append(flag);
                }
                columns[i] = flags.toString();
            }
            result.append(String.join(" ", columns));
            if (line.endsWith("\n")) result.append('\n');
            changed = true;
        }
        return new PatchResult(result.toString().getBytes(StandardCharsets.UTF_8), changed);
    }

    private static byte[] buildCpio(List<Entry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            byte[] nameWithNull = new byte[name.length + 1];
            System.arraycopy(name, 0, nameWithNull, 0, name.length);
            writeHeader(output, entry.content.length, nameWithNull.length);
            output.write(nameWithNull);
            pad4(output);
            output.write(entry.content);
            pad4(output);
        }
        byte[] trailer = "TRAILER!!!\0".getBytes(StandardCharsets.UTF_8);
        writeHeader(output, 0, trailer.length);
        output.write(trailer);
        pad4(output);
        return output.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream output, int size, int nameSize) throws IOException {
        StringBuilder header = new StringBuilder("070701");
        int[] fields = {0, 0100644, 0, 0, 1, 0, size, 0, 0, 0, 0, nameSize, 0};
        for (int field : fields) header.append(String.format(Locale.US, "%08x", field));
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void pad4(ByteArrayOutputStream output) {
        while ((output.size() & 3) != 0) output.write(0);
    }

    private static int align4(int value) { return (value + 3) & ~3; }

    private static int littleInt(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16) | ((value[offset + 3] & 0xff) << 24);
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static int hex(byte[] data, int offset, int length) {
        try { return Integer.parseUnsignedInt(ascii(data, offset, length), 16); }
        catch (NumberFormatException error) { return -1; }
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) { return readAll(input); }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = source.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static final class Entry {
        final String name;
        final byte[] content;
        Entry(String name, byte[] content) { this.name = name; this.content = content; }
    }

    private static final class PatchResult {
        final byte[] content;
        final boolean changed;
        PatchResult(byte[] content, boolean changed) { this.content = content; this.changed = changed; }
    }
}
