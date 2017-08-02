package ch.blinkenlights.android.vanilla;

/**
 * Created on 31.7.2017.
 */

public class DoubleBuffer {
    public static final int MEGA = 1024 * 1024;
    public static final int MAX_SIZE = 4 * MEGA;

    private byte[] bufferA = new byte[MAX_SIZE];
    private byte[] bufferB = new byte[MAX_SIZE];

    public byte[] bytes = bufferA;
    public int size = 0;

    private byte[] writeBytes = bufferB;
    private int writeBytesSize = 0;

    private boolean bytesIsBufferA = true;

    public void switchBuffers() {
        size = writeBytesSize;
        writeBytesSize = 0;
        if (bytesIsBufferA) {
            bytes = bufferB;
            writeBytes = bufferA;
            bytesIsBufferA = false;
        } else {
            bytes = bufferA;
            writeBytes = bufferB;
            bytesIsBufferA = true;
        }
    }

    public void write(byte[] array, int length) {
        final int freeBytes = getFreeBytes();
        if (length > freeBytes) {
            throw new RuntimeException("Not enought free space. Expected "
                    + length + ", but was " + freeBytes);
        }

        System.arraycopy(array, 0, writeBytes, writeBytesSize, length);
        writeBytesSize += length;
    }

    public int getFreeBytes() {
        return MAX_SIZE - writeBytesSize;
    }
}
