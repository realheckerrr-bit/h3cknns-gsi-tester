package com.realheckerrr.gsilab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads Android's legacy block-based .lz4 stream format (magic 0x184C2102). */
final class LegacyLz4InputStream extends InputStream {
    private static final int MAGIC = 0x184C2102;
    private static final int RAW_BLOCK_MASK = 0x80000000;
    private static final int MAX_BLOCK_SIZE = 64 * 1024 * 1024;
    private static final int MAX_DECODED_BLOCK_SIZE = 256 * 1024 * 1024;

    private final InputStream source;
    private InputStream block = new ByteArrayInputStream(new byte[0]);
    private boolean initialized;
    private boolean finished;

    LegacyLz4InputStream(InputStream source) {
        this.source = source;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        while (true) {
            int read = block.read(bytes, offset, length);
            if (read != -1) return read;
            if (finished || !readNextBlock()) return -1;
        }
    }

    @Override
    public void close() throws IOException {
        finished = true;
        source.close();
    }

    private boolean readNextBlock() throws IOException {
        if (finished) return false;
        if (!initialized) {
            int magic = readIntRequired("legacy LZ4 magic");
            if (magic != MAGIC) throw new IOException("Invalid legacy LZ4 magic.");
            initialized = true;
        }
        int encodedSize = readIntAllowEnd();
        if (encodedSize == Integer.MIN_VALUE) throw new IOException("Truncated legacy LZ4 block header.");
        if (encodedSize == 0) {
            finished = true;
            return false;
        }
        boolean raw = (encodedSize & RAW_BLOCK_MASK) != 0;
        int size = encodedSize & 0x7fffffff;
        if (size <= 0 || size > MAX_BLOCK_SIZE) throw new IOException("Invalid legacy LZ4 block size.");
        byte[] encoded = readFully(size);
        if (raw) {
            block = new ByteArrayInputStream(encoded);
        } else {
            block = new ByteArrayInputStream(decodeBlock(encoded));
        }
        return true;
    }

    private byte[] decodeBlock(byte[] input) throws IOException {
        DecodedBuffer output = new DecodedBuffer();
        int position = 0;
        while (position < input.length) {
            int token = input[position++] & 0xff;
            int literalLength = token >>> 4;
            if (literalLength == 15) {
                Position cursor = Position.of(position);
                literalLength += readLength(input, input.length, cursor);
                position = cursor.value;
            }
            if (literalLength > input.length - position) throw new IOException("Truncated legacy LZ4 literals.");
            ensureDecodedSize(output.size(), literalLength);
            output.write(input, position, literalLength);
            position += literalLength;
            if (position == input.length) break;
            if (position + 2 > input.length) throw new IOException("Truncated legacy LZ4 match.");
            int matchOffset = (input[position] & 0xff) | ((input[position + 1] & 0xff) << 8);
            position += 2;
            if (matchOffset == 0 || matchOffset > output.size()) throw new IOException("Invalid legacy LZ4 match offset.");
            int matchLength = (token & 0x0f) + 4;
            if ((token & 0x0f) == 15) {
                Position cursor = Position.of(position);
                matchLength += readLength(input, input.length, cursor);
                position = cursor.value;
            }
            ensureDecodedSize(output.size(), matchLength);
            int start = output.size() - matchOffset;
            for (int i = 0; i < matchLength; i++) output.write(output.byteAt(start + (i % matchOffset)));
        }
        return output.toByteArray();
    }

    private static int readLength(byte[] input, int end, Position cursor) throws IOException {
        int length = 0;
        while (cursor.value < end) {
            int value = input[cursor.value++] & 0xff;
            if (Integer.MAX_VALUE - length < value) throw new IOException("Legacy LZ4 length overflow.");
            length += value;
            if (value != 255) return length;
        }
        throw new IOException("Truncated legacy LZ4 length.");
    }

    private static void ensureDecodedSize(int current, int addition) throws IOException {
        if (addition < 0 || current > MAX_DECODED_BLOCK_SIZE - addition) {
            throw new IOException("Legacy LZ4 block expands beyond the supported size.");
        }
    }

    private int readIntRequired(String what) throws IOException {
        int value = readIntAllowEnd();
        if (value == Integer.MIN_VALUE) throw new IOException("Truncated " + what + ".");
        return value;
    }

    private int readIntAllowEnd() throws IOException {
        int first = source.read();
        if (first == -1) return Integer.MIN_VALUE;
        int b1 = source.read();
        int b2 = source.read();
        int b3 = source.read();
        if ((b1 | b2 | b3) < 0) throw new IOException("Truncated legacy LZ4 integer.");
        return first | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private byte[] readFully(int length) throws IOException {
        byte[] output = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = source.read(output, offset, length - offset);
            if (read < 0) throw new IOException("Truncated legacy LZ4 block.");
            if (read == 0) continue;
            offset += read;
        }
        return output;
    }

    private static final class Position {
        int value;

        static Position of(int value) {
            Position position = new Position();
            position.value = value;
            return position;
        }
    }

    private static final class DecodedBuffer extends ByteArrayOutputStream {
        byte byteAt(int index) {
            return buf[index];
        }
    }
}
