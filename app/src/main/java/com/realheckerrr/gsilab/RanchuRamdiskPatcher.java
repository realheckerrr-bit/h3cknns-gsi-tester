package com.realheckerrr.gsilab;

import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;
import org.tukaani.xz.LZMA2Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Makes the stock Ranchu initramfs mount the supplied direct test disks. */
public final class RanchuRamdiskPatcher {
    private RanchuRamdiskPatcher() {}

    public static File patch(File source, File target) throws IOException {
        byte[] encoded = readFile(source);
        Compression compression = compression(encoded);
        List<Entry> entries = parse(decompress(encoded, compression));
        boolean changed = false;
        for (Entry entry : entries) {
            String base = entry.name.substring(entry.name.lastIndexOf('/') + 1);
            if (base.equals("fstab.ranchu.initrd") || base.equals("fstab.ranchu")) {
                PatchResult result = patchFstab(entry.content);
                entry.content = result.bytes;
                changed |= result.changed;
            }
        }
        if (!changed) return source;
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(compress(build(entries), compression));
        }
        return target;
    }

    private static PatchResult patchFstab(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\n", -1);
        StringBuilder result = new StringBuilder(text.length() + 64);
        boolean changed = false;
        for (String line : lines) {
            String lineWithoutCr = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            String trimmed = lineWithoutCr.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.append(line).append('\n');
                continue;
            }
            String[] columns = trimmed.split("\\s+");
            if (columns.length < 2) {
                result.append(line).append('\n');
                continue;
            }
            String device = columns[0];
            String mountpoint = columns[1];
            if ("/metadata".equals(mountpoint) || device.endsWith("/metadata")) {
                changed = true;
                continue;
            }
            if (trimmed.contains("first_stage_mount") && trimmed.contains("logical")) {
                if ("/system".equals(mountpoint)) {
                    columns[0] = "/dev/block/vda";
                } else if ("/vendor".equals(mountpoint)) {
                    columns[0] = "/dev/block/vdb";
                } else {
                    changed = true;
                    continue;
                }
                for (int i = 0; i < columns.length; i++) {
                    String[] flags = columns[i].split(",");
                    StringBuilder cleaned = new StringBuilder();
                    for (String flag : flags) {
                        if ("logical".equals(flag) || "avb=vbmeta".equals(flag)) continue;
                        if (cleaned.length() > 0) cleaned.append(',');
                        cleaned.append(flag);
                    }
                    columns[i] = cleaned.toString();
                }
                changed = true;
                lineWithoutCr = String.join(" ", columns);
            }
            result.append(lineWithoutCr).append('\n');
        }
        return new PatchResult(result.toString().getBytes(StandardCharsets.UTF_8), changed);
    }

    private static List<Entry> parse(byte[] data) throws IOException {
        List<Entry> entries = new ArrayList<>();
        int offset = 0;
        while (offset + 110 <= data.length) {
            byte[] header = slice(data, offset, 110);
            String magic = new String(header, 0, 6, StandardCharsets.US_ASCII);
            if (!"070701".equals(magic) && !"070702".equals(magic)) {
                throw new IOException("Ranchu ramdisk is not a newc cpio archive.");
            }
            int size = hex(header, 54);
            int nameSize = hex(header, 94);
            int nameStart = offset + 110;
            int nameEnd = nameStart + nameSize;
            String name = new String(data, nameStart, nameSize - 1, StandardCharsets.UTF_8);
            int contentStart = align4(nameEnd);
            int contentEnd = contentStart + size;
            if (contentEnd > data.length) throw new IOException("Truncated Ranchu ramdisk entry.");
            if ("TRAILER!!!".equals(name)) break;
            entries.add(new Entry(header, name, slice(data, contentStart, size)));
            offset = align4(contentEnd);
        }
        if (entries.isEmpty()) throw new IOException("Empty Ranchu ramdisk.");
        return entries;
    }

    private static byte[] build(List<Entry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] header = entry.header.clone();
            byte[] name = (entry.name + "\0").getBytes(StandardCharsets.UTF_8);
            putHex(header, 54, entry.content.length);
            putHex(header, 94, name.length);
            putHex(header, 102, 0);
            System.arraycopy("070701".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 6);
            output.write(header);
            output.write(name);
            pad(output);
            output.write(entry.content);
            pad(output);
        }
        byte[] trailerHeader = new byte[110];
        System.arraycopy("070701".getBytes(StandardCharsets.US_ASCII), 0, trailerHeader, 0, 6);
        for (int offset = 6; offset < trailerHeader.length; offset += 8) {
            byte[] zeros = "00000000".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(zeros, 0, trailerHeader, offset, zeros.length);
        }
        byte[] trailerName = "TRAILER!!!\0".getBytes(StandardCharsets.US_ASCII);
        putHex(trailerHeader, 94, trailerName.length);
        output.write(trailerHeader);
        output.write(trailerName);
        pad(output);
        return output.toByteArray();
    }

    private static void pad(ByteArrayOutputStream output) {
        while ((output.size() & 3) != 0) output.write(0);
    }

    private static Compression compression(byte[] data) {
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) return Compression.GZIP;
        if (data.length >= 6 && data[0] == (byte) 0xfd && data[1] == 0x37 && data[2] == 0x7a
                && data[3] == 0x58 && data[4] == 0x5a && data[5] == 0x00) return Compression.XZ;
        if (data.length >= 4 && data[0] == 0x02 && data[1] == 0x21 && data[2] == 0x4c && data[3] == 0x18) return Compression.LZ4;
        return Compression.RAW;
    }

    private static byte[] decompress(byte[] data, Compression compression) throws IOException {
        if (compression == Compression.RAW) return data;
        InputStream input = new ByteArrayInputStream(data);
        if (compression == Compression.GZIP) input = new GZIPInputStream(input);
        else if (compression == Compression.XZ) input = new XZInputStream(input);
        else return decompressLegacyLz4(data);
        return readAll(input);
    }

    private static byte[] compress(byte[] data, Compression compression) throws IOException {
        if (compression == Compression.RAW) return data;
        if (compression == Compression.LZ4) compression = Compression.GZIP;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream output;
        if (compression == Compression.GZIP) output = new GZIPOutputStream(bytes);
        else if (compression == Compression.XZ) output = new XZOutputStream(bytes, new LZMA2Options());
        else output = new GZIPOutputStream(bytes);
        output.write(data);
        output.close();
        return bytes.toByteArray();
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) { return readAll(input); }
    }

    private static byte[] decompressLegacyLz4(byte[] data) throws IOException {
        byte[] output = new byte[Math.max(1024 * 1024, data.length * 2)];
        int outputLength = 0;
        int offset = 4;
        while (offset + 4 <= data.length) {
            int blockSize = littleInt(data, offset);
            offset += 4;
            if (blockSize == 0) break;
            if (blockSize < 0 || offset + blockSize > data.length) throw new IOException("Truncated legacy LZ4 block.");
            int position = offset;
            int end = offset + blockSize;
            offset = end;
            while (position < end) {
                int token = data[position++] & 0xff;
                int literalLength = token >>> 4;
                if (literalLength == 15) {
                    int value;
                    do {
                        if (position >= end) throw new IOException("Truncated legacy LZ4 literal.");
                        value = data[position++] & 0xff;
                        literalLength += value;
                    } while (value == 255);
                }
                output = ensureCapacity(output, outputLength + literalLength);
                if (position + literalLength > end) throw new IOException("Truncated legacy LZ4 literal data.");
                System.arraycopy(data, position, output, outputLength, literalLength);
                position += literalLength;
                outputLength += literalLength;
                if (position == end) break;
                if (position + 2 > end) throw new IOException("Truncated legacy LZ4 match.");
                int matchOffset = (data[position] & 0xff) | ((data[position + 1] & 0xff) << 8);
                position += 2;
                int matchLength = token & 15;
                if (matchLength == 15) {
                    int value;
                    do {
                        if (position >= end) throw new IOException("Truncated legacy LZ4 match length.");
                        value = data[position++] & 0xff;
                        matchLength += value;
                    } while (value == 255);
                }
                matchLength += 4;
                if (matchOffset == 0 || matchOffset > outputLength) throw new IOException("Invalid legacy LZ4 match offset.");
                output = ensureCapacity(output, outputLength + matchLength);
                for (int i = 0; i < matchLength; i++) {
                    output[outputLength] = output[outputLength - matchOffset];
                    outputLength++;
                }
            }
        }
        return Arrays.copyOf(output, outputLength);
    }

    private static byte[] ensureCapacity(byte[] data, int required) {
        if (required <= data.length) return data;
        int size = data.length;
        while (size < required) size *= 2;
        return Arrays.copyOf(data, size);
    }

    private static int littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        byte[] value = new byte[length];
        System.arraycopy(source, offset, value, 0, length);
        return value;
    }

    private static int hex(byte[] bytes, int offset) {
        return Integer.parseInt(new String(bytes, offset, 8, StandardCharsets.US_ASCII), 16);
    }

    private static void putHex(byte[] bytes, int offset, int value) {
        byte[] text = String.format(java.util.Locale.US, "%08x", value).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, bytes, offset, 8);
    }

    private static int align4(int value) { return (value + 3) & ~3; }

    private enum Compression { RAW, GZIP, XZ, LZ4 }

    private static final class Entry {
        final byte[] header;
        final String name;
        byte[] content;
        Entry(byte[] header, String name, byte[] content) {
            this.header = header;
            this.name = name;
            this.content = content;
        }
    }

    private static final class PatchResult {
        final byte[] bytes;
        final boolean changed;
        PatchResult(byte[] bytes, boolean changed) {
            this.bytes = bytes;
            this.changed = changed;
        }
    }
}
