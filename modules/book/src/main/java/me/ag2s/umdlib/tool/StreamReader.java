package me.ag2s.umdlib.tool;


import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class StreamReader {
    private final InputStream is;

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    private long offset;
    private long size;

    private void incCount(int value) {
        offset += value;
    }

    public StreamReader(InputStream inputStream) throws IOException {
        this.is = inputStream;
        //this.size=inputStream.getChannel().size();
    }

    public short readUint8() throws IOException {
        byte[] b = new byte[1];
        readFully(b, 0, b.length);
        return (short) ((b[0] & 0xFF));

    }

    public byte readByte() throws IOException {
        byte[] b = new byte[1];
        readFully(b, 0, b.length);
        return b[0];
    }

    public byte[] readBytes(int len) throws IOException {
        if (len < 0) {
            System.out.println(len);
            throw new IllegalArgumentException("Length must > 0: " + len);
        }
        if (len == 0) {
            return null;
        }
        byte[] b = new byte[len];
        readFully(b, 0, b.length);
        return b;
    }

    public String readHex(int len) throws IOException {
        if (len < 1) {
            System.out.println(len);
            throw new IllegalArgumentException("Length must > 0: " + len);
        }
        byte[] b = new byte[len];
        readFully(b, 0, b.length);
        return UmdUtils.toHex(b);
    }

    public short readShort() throws IOException {
        byte[] b = new byte[2];
        readFully(b, 0, b.length);
        short x = (short) (((b[0] & 0xFF) << 8) | ((b[1] & 0xFF) << 0));
        return x;
    }

    public short readShortLe() throws IOException {
        byte[] b = new byte[2];
        readFully(b, 0, b.length);
        short x = (short) (((b[1] & 0xFF) << 8) | ((b[0] & 0xFF) << 0));
        return x;
    }

    public int readInt() throws IOException {
        byte[] b = new byte[4];
        readFully(b, 0, b.length);
        int x = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) |
                ((b[2] & 0xFF) << 8) | ((b[3] & 0xFF) << 0);
        return x;
    }

    public int readIntLe() throws IOException {
        byte[] b = new byte[4];
        readFully(b, 0, b.length);
        int x = ((b[3] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) |
                ((b[1] & 0xFF) << 8) | ((b[0] & 0xFF) << 0);
        return x;
    }

    public void skip(int len) throws IOException {
        readBytes(len);
    }


    public byte[] read(byte[] b) throws IOException {
        readFully(b, 0, b.length);
        return b;
    }

    public byte[] read(byte[] b, int off, int len) throws IOException {
        readFully(b, off, len);
        return b;
    }

    private void readFully(byte[] buffer, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException();
        }
        int totalRead = 0;
        while (totalRead < length) {
            int read = is.read(buffer, offset + totalRead, length - totalRead);
            if (read < 0) {
                throw new EOFException("Unexpected end of stream at offset " + this.offset);
            }
            if (read == 0) {
                int value = is.read();
                if (value < 0) {
                    throw new EOFException("Unexpected end of stream at offset " + this.offset);
                }
                buffer[offset + totalRead] = (byte) value;
                read = 1;
            }
            totalRead += read;
            incCount(read);
        }
    }

}
