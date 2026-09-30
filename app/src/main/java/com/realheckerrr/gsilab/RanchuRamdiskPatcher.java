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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Rewrites the Ranchu first-stage fstab and keeps the kernel modules in the ramdisk. */
public final class RanchuRamdiskPatcher {
    private static final int LZ4_MAGIC = 0x184C2102;

    private RanchuRamdiskPatcher() {}

    public static File patch(File source, File target) throws IOException {
        return patchDirect(source, target, "/dev/block/vdb", "/dev/block/vda", "/dev/block/vdc", false);
    }

    public static File patchCuttlefish(File source, File target) throws IOException {
        return patchDirect(source, target, "/dev/block/vda", "/dev/block/vdc", "/dev/block/vdb", true);
    }

    private static File patchDirect(File source, File target, String systemDevice, String vendorDevice,
                                    String dataDevice, boolean allFstabEntries) throws IOException {
        byte[] encoded = readAll(source);
        if (!looksLikeRamdisk(encoded)) return source;
        byte[] raw = unpack(encoded, source.getName());
        List<Entry> entries = parseCpio(raw);
        boolean hasSystem = false;
        boolean hasVendor = false;
        List<Entry> patched = new ArrayList<>();
        for (Entry entry : entries) {
            String base = entry.name.substring(entry.name.lastIndexOf('/') + 1);
            boolean isFstab = allFstabEntries
                    ? base.startsWith("fstab")
                    : ("fstab.ranchu".equals(base) || "fstab.ranchu.initrd".equals(base));
            if (isFstab) {
                PatchResult result = patchFstab(entry.content, systemDevice, vendorDevice, dataDevice,
                        allFstabEntries);
                entry = entry.withContent(result.content);
                hasSystem |= result.hasSystem;
                hasVendor |= result.hasVendor;
            }
            patched.add(entry);
        }
        if (!hasSystem || !hasVendor) {
            StringBuilder fallback = new StringBuilder();
            if (!hasSystem) fallback.append(systemDevice).append(" /system ext4 ro wait,first_stage_mount\n");
            if (!hasVendor) fallback.append(vendorDevice).append(" /vendor ext4 ro wait,first_stage_mount\n");
            patched.add(Entry.regular(allFstabEntries ? "fstab.cf.arm64" : "fstab.ranchu",
                    fallback.toString().getBytes(StandardCharsets.UTF_8)));
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

    /**
     * Combines Android's generic init_boot ramdisk with its vendor_boot
     * ramdisk.  Newer Android guests keep these layers separate; passing only
     * one layer to QEMU leaves first-stage init without the other layer's
     * mounts and services.
     */
    public static File merge(File generic, File vendor, File target) throws IOException {
        List<Entry> genericEntries = parseCpio(unpack(readAll(generic), generic.getName()));
        List<Entry> vendorEntries = parseCpio(unpack(readAll(vendor), vendor.getName()));
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : genericEntries) merged.put(entry.name, entry);
        for (Entry entry : vendorEntries) merged.put(entry.name, entry);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create merged ramdisk output directory.");
        }
        try (FileOutputStream output = new FileOutputStream(target);
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(buildCpio(new ArrayList<>(merged.values())));
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
                long ino = hexLong(data, position + 6, 8);
                long mode = hexLong(data, position + 14, 8);
                long uid = hexLong(data, position + 22, 8);
                long gid = hexLong(data, position + 30, 8);
                long nlink = hexLong(data, position + 38, 8);
                long mtime = hexLong(data, position + 46, 8);
                long sizeLong = hexLong(data, position + 54, 8);
                long devMajor = hexLong(data, position + 62, 8);
                long devMinor = hexLong(data, position + 70, 8);
                long rdevMajor = hexLong(data, position + 78, 8);
                long rdevMinor = hexLong(data, position + 86, 8);
                int nameSize = hex(data, position + 94, 8);
                long check = hexLong(data, position + 102, 8);
                if (ino < 0 || mode < 0 || uid < 0 || gid < 0 || nlink < 0 || mtime < 0
                        || sizeLong < 0 || sizeLong > Integer.MAX_VALUE || devMajor < 0 || devMinor < 0
                        || rdevMajor < 0 || rdevMinor < 0 || check < 0 || nameSize < 1
                        || position + 110L + nameSize > data.length) break;
                int size = (int) sizeLong;
                int nameStart = position + 110;
                String name = new String(data, nameStart, nameSize - 1, StandardCharsets.UTF_8);
                int contentStart = align4(nameStart + nameSize);
                long contentEndLong = contentStart + (long) size;
                if (contentEndLong < contentStart || contentEndLong > data.length) break;
                int contentEnd = (int) contentEndLong;
                if ("TRAILER!!!".equals(name)) {
                    cursor = align4(contentEnd);
                    break;
                }
                byte[] content = new byte[size];
                System.arraycopy(data, contentStart, content, 0, size);
                entries.add(new Entry(name, magic, ino, mode, uid, gid, nlink, mtime,
                        devMajor, devMinor, rdevMajor, rdevMinor, content));
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

    private static PatchResult patchFstab(byte[] content, String systemDevice, String vendorDevice,
                                          String dataDevice, boolean allFstabEntries) {
        String text = new String(content, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        boolean changed = false;
        boolean hasSystem = false;
        boolean hasVendor = false;
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
                result.append("tmpfs /metadata tmpfs mode=0755 wait");
                if (line.endsWith("\n")) result.append('\n');
                changed = true;
                continue;
            }
            String desiredFs = null;
            if ("/system".equals(mountpoint)) desiredFs = "ext4";
            else if ("/vendor".equals(mountpoint)) desiredFs = "erofs";
            else if ("/data".equals(mountpoint)) desiredFs = "ext4";
            if (allFstabEntries && desiredFs != null
                    && columns.length > 2 && !desiredFs.equals(columns[2])) {
                result.append('#').append(line.startsWith("#") ? line.substring(1) : line);
                changed = true;
                continue;
            }
            if (mountEntry && "/data".equals(mountpoint)) {
                columns[0] = dataDevice;
                result.append(String.join(" ", columns));
                if (line.endsWith("\n")) result.append('\n');
                changed = true;
                continue;
            }
            if (allFstabEntries && device.startsWith("/dev/block/by-name/")
                    && !"/system".equals(mountpoint) && !"/vendor".equals(mountpoint)) {
                result.append('#').append(line.startsWith("#") ? line.substring(1) : line);
                changed = true;
                continue;
            }
            if (!stripped.contains("first_stage_mount")) {
                result.append(line);
                continue;
            }
            if ("/system".equals(mountpoint)) columns[0] = systemDevice;
            else if ("/vendor".equals(mountpoint)) columns[0] = vendorDevice;
            else {
                result.append('#').append(line.startsWith("#") ? line.substring(1) : line);
                changed = true;
                continue;
            }
            if ("/system".equals(mountpoint)) hasSystem = true;
            else hasVendor = true;
            for (int i = 0; i < columns.length; i++) {
                StringBuilder flags = new StringBuilder();
                for (String flag : columns[i].split(",")) {
                    if ("logical".equals(flag) || "slotselect".equals(flag)
                            || flag.startsWith("avb=") || flag.startsWith("avb_keys=")) continue;
                    if (flags.length() > 0) flags.append(',');
                    flags.append(flag);
                }
                columns[i] = flags.toString();
            }
            result.append(String.join(" ", columns));
            if (line.endsWith("\n")) result.append('\n');
            changed = true;
        }
        return new PatchResult(result.toString().getBytes(StandardCharsets.UTF_8), changed, hasSystem, hasVendor);
    }

    private static byte[] buildCpio(List<Entry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            byte[] nameWithNull = new byte[name.length + 1];
            System.arraycopy(name, 0, nameWithNull, 0, name.length);
            long check = "070702".equals(entry.magic) ? checksum(entry.content) : 0;
            writeHeader(output, entry, entry.content.length, nameWithNull.length, check);
            output.write(nameWithNull);
            pad4(output);
            output.write(entry.content);
            pad4(output);
        }
        byte[] trailer = "TRAILER!!!\0".getBytes(StandardCharsets.UTF_8);
        writeHeader(output, Entry.trailer(), 0, trailer.length, 0);
        output.write(trailer);
        pad4(output);
        return output.toByteArray();
    }

    private static void writeHeader(ByteArrayOutputStream output, Entry entry, int size, int nameSize,
                                    long check) throws IOException {
        StringBuilder header = new StringBuilder(entry.magic);
        long[] fields = {entry.ino, entry.mode, entry.uid, entry.gid, entry.nlink, entry.mtime, size,
                entry.devMajor, entry.devMinor, entry.rdevMajor, entry.rdevMinor, nameSize, check};
        for (long field : fields) header.append(String.format(Locale.US, "%08x", field));
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static long checksum(byte[] content) {
        long sum = 0;
        for (byte value : content) sum = (sum + (value & 0xffL)) & 0xffffffffL;
        return sum;
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
        long value = hexLong(data, offset, length);
        return value < 0 || value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static long hexLong(byte[] data, int offset, int length) {
        try { return Long.parseUnsignedLong(ascii(data, offset, length), 16); }
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
        final String magic;
        final long ino;
        final long mode;
        final long uid;
        final long gid;
        final long nlink;
        final long mtime;
        final long devMajor;
        final long devMinor;
        final long rdevMajor;
        final long rdevMinor;
        final byte[] content;

        Entry(String name, String magic, long ino, long mode, long uid, long gid, long nlink, long mtime,
              long devMajor, long devMinor, long rdevMajor, long rdevMinor, byte[] content) {
            this.name = name;
            this.magic = magic;
            this.ino = ino;
            this.mode = mode;
            this.uid = uid;
            this.gid = gid;
            this.nlink = nlink;
            this.mtime = mtime;
            this.devMajor = devMajor;
            this.devMinor = devMinor;
            this.rdevMajor = rdevMajor;
            this.rdevMinor = rdevMinor;
            this.content = content;
        }

        static Entry regular(String name, byte[] content) {
            return new Entry(name, "070701", 0, 0100644, 0, 0, 1, 0,
                    0, 0, 0, 0, content);
        }

        static Entry trailer() {
            return new Entry("TRAILER!!!", "070701", 0, 0, 0, 0, 1, 0,
                    0, 0, 0, 0, new byte[0]);
        }

        Entry withContent(byte[] replacement) {
            return new Entry(name, magic, ino, mode, uid, gid, nlink, mtime,
                    devMajor, devMinor, rdevMajor, rdevMinor, replacement);
        }
    }

    private static final class PatchResult {
        final byte[] content;
        final boolean changed;
        final boolean hasSystem;
        final boolean hasVendor;
        PatchResult(byte[] content, boolean changed, boolean hasSystem, boolean hasVendor) {
            this.content = content;
            this.changed = changed;
            this.hasSystem = hasSystem;
            this.hasVendor = hasVendor;
        }
    }
}
