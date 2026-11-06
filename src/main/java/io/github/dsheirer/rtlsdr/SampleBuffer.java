package io.github.dsheirer.rtlsdr;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Samples delivered to a {@link SampleListener}: interleaved I and Q bytes,
 * each an unsigned 8-bit value centred on 127.5.
 *
 * <p>{@link #samples()} points at memory owned by the native library. It may be
 * used only on the callback's thread and only until the callback returns; after
 * that, access throws {@link IllegalStateException}. Use one of the copying
 * methods, or {@link MemorySegment#copy}, to keep the data.
 *
 * @param samples sample memory
 */
public record SampleBuffer(MemorySegment samples) {

    /** Returns the number of bytes, which is twice the number of I/Q pairs. */
    public int length() {
        return (int) samples.byteSize();
    }

    /** Copies the samples to a new array. */
    public byte[] copyBytes() {
        return samples.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Copies the samples into an existing array.
     *
     * @throws IndexOutOfBoundsException if the samples do not fit
     */
    public void copyTo(byte[] destination, int offset) {
        MemorySegment.copy(samples, ValueLayout.JAVA_BYTE, 0, destination, offset, length());
    }

    /**
     * Converts the samples to floats in the range -1 to 1, the form most signal
     * processing code expects.
     */
    public float[] toFloats() {
        int length = length();
        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
            result[i] = (Byte.toUnsignedInt(samples.get(ValueLayout.JAVA_BYTE, i)) - 127.5f) / 127.5f;
        }
        return result;
    }
}
