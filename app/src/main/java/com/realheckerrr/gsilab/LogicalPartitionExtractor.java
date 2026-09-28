package com.realheckerrr.gsilab;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Reads a linear logical partition from an Android dynamic-partition super image. */
public final class LogicalPartitionExtractor {
    private static final long GEOMETRY_MAGIC = 0x616C4467L;
    private static final long HEADER_MAGIC = 0x414C5030L;
    private static final int GEOMETRY_SIZE = 4096;
    private static final int RESERVED_BYTES = 4096;
    private static final int SECTOR_SIZE = 512;
    private static final int LINEAR_EXTENT = 0;
    private static final int ZERO_EXTENT = 1;

    private LogicalPartitionExtractor() {}

    public static File extract(File superImage, String requestedName, File output) throws IOException {
        if (superImage == null || !superImage.isFile()) throw new IOException("The super image is not readable.");
        try (RandomAccessFile input = new RandomAccessFile(superImage, "r")) {
            Geometry geometry = readGeometry(input, 0L);
            long metadataBase = RESERVED_BYTES + GEOMETRY_SIZE;
            Metadata metadata = readMetadata(input, metadataBase, geometry.metadataMaxSize, geometry.slotCount, 0);
            Partition partition = findPartition(metadata, requestedName);
            if (partition == null && geometry.slotCount > 1) {
                metadata = readMetadata(input, metadataBase, geometry.metadataMaxSize, geometry.slotCount, 1);
                partition = findPartition(metadata, requestedName);
            }
            if (partition == null) {
                throw new IOException("The super image has no " + requestedName + " logical partition.");
            }
            File parent = output.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create logical partition output directory.");
            }
            writePartition(input, metadata, partition, output);
            return output;
        }
    }

    private static Geometry readGeometry(RandomAccessFile input, long offset) throws IOException {
        input.seek(offset);
        byte[] geometry = new byte[52];
        input.readFully(geometry);
        if (littleInt(geometry, 0) != GEOMETRY_MAGIC) throw new IOException("Invalid super image geometry magic.");
        int structSize = (int) littleInt(geometry, 4);
        int metadataMaxSize = (int) littleInt(geometry, 40);
        int slotCount = (int) littleInt(geometry, 44);
        if (structSize < 52 || metadataMaxSize <= 0 || slotCount <= 0) {
            throw new IOException("Invalid super image geometry.");
        }
        return new Geometry(metadataMaxSize, slotCount);
    }

    private static Metadata readMetadata(
            RandomAccessFile input, long base, int metadataMaxSize, int slotCount, int slot) throws IOException {
        if (slot < 0 || slot >= slotCount) throw new IOException("Invalid super metadata slot.");
        long offset = base + (long) slot * metadataMaxSize;
        input.seek(offset);
        byte[] header = new byte[256];
        input.readFully(header);
        if (littleInt(header, 0) != HEADER_MAGIC) throw new IOException("Invalid super image metadata magic.");
        int headerSize = (int) littleInt(header, 8);
        int tablesSize = (int) littleInt(header, 44);
        if (headerSize < 128 || tablesSize < 0 || headerSize + tablesSize > metadataMaxSize) {
            throw new IOException("Invalid super image metadata header.");
        }
        Table partitions = table(header, 80);
        Table extents = table(header, 92);
        if (partitions.entrySize < 52 || extents.entrySize < 24) {
            throw new IOException("Unsupported super image metadata table size.");
        }
        return new Metadata(input, offset + headerSize, partitions, extents);
    }

    private static Table table(byte[] header, int offset) {
        return new Table((int) littleInt(header, offset), (int) littleInt(header, offset + 4),
                (int) littleInt(header, offset + 8));
    }

    private static Partition findPartition(Metadata metadata, String requestedName) throws IOException {
        Partition fallback = null;
        for (int index = 0; index < metadata.partitions.count; index++) {
            long offset = metadata.tableOffset(metadata.partitions, index);
            byte[] entry = readAt(metadata.input, offset, metadata.partitions.entrySize);
            String name = cString(entry, 0, 36);
            int firstExtent = (int) littleInt(entry, 40);
            int extentCount = (int) littleInt(entry, 44);
            if (name.equals(requestedName)) return new Partition(name, firstExtent, extentCount);
            if (requestedName.equals("vendor") && (name.equals("vendor_a") || name.equals("vendor_b"))) {
                fallback = new Partition(name, firstExtent, extentCount);
            }
        }
        return fallback;
    }

    private static void writePartition(RandomAccessFile input, Metadata metadata, Partition partition, File output)
            throws IOException {
        if (partition.extentCount <= 0) throw new IOException("Logical partition has no extents.");
        long outputBytes = 0;
        for (int index = 0; index < partition.extentCount; index++) {
            byte[] extent = readAt(input, metadata.tableOffset(metadata.extents, partition.firstExtent + index),
                    metadata.extents.entrySize);
            outputBytes = Math.addExact(outputBytes, littleLong(extent, 0) * SECTOR_SIZE);
        }
        try (RandomAccessFile target = new RandomAccessFile(output, "rw")) {
            target.setLength(outputBytes);
            byte[] buffer = new byte[1024 * 1024];
            for (int index = 0; index < partition.extentCount; index++) {
                byte[] extent = readAt(input, metadata.tableOffset(metadata.extents, partition.firstExtent + index),
                        metadata.extents.entrySize);
                long sectors = littleLong(extent, 0);
                int type = (int) littleInt(extent, 8);
                long bytes = Math.multiplyExact(sectors, (long) SECTOR_SIZE);
                if (type == LINEAR_EXTENT) {
                    long sourceOffset = Math.multiplyExact(littleLong(extent, 12), (long) SECTOR_SIZE);
                    if (sourceOffset < 0 || sourceOffset + bytes > input.length()) {
                        throw new IOException("Logical partition extent exceeds super image.");
                    }
                    input.seek(sourceOffset);
                    copy(input, target, bytes, buffer);
                } else if (type == ZERO_EXTENT) {
                    writeZeros(target, bytes, buffer);
                } else {
                    throw new IOException("Unsupported logical partition extent type " + type + ".");
                }
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("Logical partition size is too large.", overflow);
        }
    }

    private static void copy(RandomAccessFile input, RandomAccessFile output, long bytes, byte[] buffer)
            throws IOException {
        long copied = 0;
        while (copied < bytes) {
            int want = (int) Math.min(buffer.length, bytes - copied);
            int read = input.read(buffer, 0, want);
            if (read < 0) throw new IOException("Unexpected end of super image.");
            output.write(buffer, 0, read);
            copied += read;
        }
    }

    private static void writeZeros(RandomAccessFile output, long bytes, byte[] buffer) throws IOException {
        java.util.Arrays.fill(buffer, (byte) 0);
        for (long written = 0; written < bytes;) {
            int count = (int) Math.min(buffer.length, bytes - written);
            output.write(buffer, 0, count);
            written += count;
        }
    }

    private static byte[] readAt(RandomAccessFile input, long offset, int size) throws IOException {
        if (offset < 0 || size < 0 || offset + size > input.length()) throw new IOException("Super metadata is truncated.");
        byte[] data = new byte[size];
        input.seek(offset);
        input.readFully(data);
        return data;
    }

    private static long littleInt(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static long littleLong(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24)
                | ((data[offset + 4] & 0xFFL) << 32) | ((data[offset + 5] & 0xFFL) << 40)
                | ((data[offset + 6] & 0xFFL) << 48) | ((data[offset + 7] & 0xFFL) << 56);
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static final class Geometry {
        final int metadataMaxSize;
        final int slotCount;

        Geometry(int metadataMaxSize, int slotCount) {
            this.metadataMaxSize = metadataMaxSize;
            this.slotCount = slotCount;
        }
    }

    private static final class Table {
        final int offset;
        final int count;
        final int entrySize;

        Table(int offset, int count, int entrySize) {
            this.offset = offset;
            this.count = count;
            this.entrySize = entrySize;
        }
    }

    private static final class Metadata {
        final long base;
        final Table partitions;
        final Table extents;
        final RandomAccessFile input;

        Metadata(RandomAccessFile input, long base, Table partitions, Table extents) {
            this.base = base;
            this.partitions = partitions;
            this.extents = extents;
            this.input = input;
        }

        long tableOffset(Table table, int index) {
            return base + table.offset + (long) index * table.entrySize;
        }
    }

    private static final class Partition {
        final String name;
        final int firstExtent;
        final int extentCount;

        Partition(String name, int firstExtent, int extentCount) {
            this.name = name;
            this.firstExtent = firstExtent;
            this.extentCount = extentCount;
        }
    }
}
