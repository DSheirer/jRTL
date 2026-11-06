package io.github.dsheirer.rtlsdr;

import static io.github.dsheirer.rtlsdr.RtlSdr.check;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.dsheirer.rtlsdr.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * An open RTL-SDR device. Close it when finished, which also stops streaming.
 *
 * <p>Gains are in tenths of a dB throughout, as in librtlsdr: 297 means 29.7 dB.
 *
 * <p>Control methods may be called from any thread, including from a
 * {@link SampleListener}, except {@link #stopRx()} and {@link #close()}. Do not
 * close a device while other threads are still using it.
 */
public final class RtlSdrDevice implements AutoCloseable {
    /** Size of the EEPROM that {@link #readEeprom} and {@link #writeEeprom} address. */
    public static final int EEPROM_SIZE = 256;

    private static final MethodHandle ON_SAMPLES;

    static {
        try {
            ON_SAMPLES = MethodHandles.lookup().findVirtual(RtlSdrDevice.class, "onSamples",
                MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MemorySegment handle;
    private final Object lifecycleLock = new Object();
    private boolean closed;

    // Streaming state: written under lifecycleLock before the streaming thread
    // starts, read by that thread.
    private Arena streamArena;
    private volatile Thread streamThread;
    private volatile SampleListener listener;
    private volatile boolean stopRequested;
    private volatile Throwable streamingFailure;

    RtlSdrDevice(MemorySegment handle) {
        this.handle = handle;
    }

    // ---- Information ----

    /** Returns the tuner chip. */
    public Tuner tunerType() {
        return Tuner.fromCode(Native.invoke(Native.GET_TUNER_TYPE, handle()));
    }

    /** Returns the USB strings of the device. */
    public UsbStrings usbStrings() throws RtlSdrException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment manufacturer = arena.allocate(Native.USB_STRING_LENGTH);
            MemorySegment product = arena.allocate(Native.USB_STRING_LENGTH);
            MemorySegment serial = arena.allocate(Native.USB_STRING_LENGTH);
            check("rtlsdr_get_usb_strings",
                Native.invoke(Native.GET_USB_STRINGS, handle(), manufacturer, product, serial));
            return new UsbStrings(manufacturer.getString(0), product.getString(0), serial.getString(0));
        }
    }

    /**
     * Returns true if the device's USB manufacturer and product strings are
     * exactly these. The RTL-SDR Blog V4, for example, is
     * {@code ("RTLSDRBlog", "Blog V4")}.
     */
    public boolean isModel(String manufacturer, String product) {
        try (Arena arena = Arena.ofConfined()) {
            return Native.invoke(Native.CHECK_DONGLE_MODEL, handle(),
                arena.allocateFrom(manufacturer), arena.allocateFrom(product)) != 0;
        }
    }

    // ---- Tuning ----

    /** Tunes to a frequency in Hz. */
    public void setCenterFrequency(long hertz) throws RtlSdrException {
        check("rtlsdr_set_center_freq", Native.invoke(Native.SET_CENTER_FREQ, handle(), u32(hertz)));
    }

    /** Returns the frequency in Hz the device is tuned to, or 0 if it is unknown. */
    public long centerFrequency() {
        return Integer.toUnsignedLong(Native.invoke(Native.GET_CENTER_FREQ, handle()));
    }

    /** Sets the frequency correction in parts per million. */
    public void setFrequencyCorrection(int ppm) throws RtlSdrException {
        int result = Native.invoke(Native.SET_FREQ_CORRECTION, handle(), ppm);
        // -2 means the correction is already set to this value.
        if (result != -2) {
            check("rtlsdr_set_freq_correction", result);
        }
    }

    /** Returns the frequency correction in parts per million. */
    public int frequencyCorrection() {
        return Native.invoke(Native.GET_FREQ_CORRECTION, handle());
    }

    /**
     * Selects a sample rate in Hz: 225001 to 300000, or 900001 to 3200000.
     * Rates above 2400000 are likely to lose samples.
     */
    public void setSampleRate(long hertz) throws RtlSdrException {
        check("rtlsdr_set_sample_rate", Native.invoke(Native.SET_SAMPLE_RATE, handle(), u32(hertz)));
    }

    /** Returns the sample rate in Hz, or 0 if it is unknown. */
    public long sampleRate() {
        return Integer.toUnsignedLong(Native.invoke(Native.GET_SAMPLE_RATE, handle()));
    }

    /** Sets the tuner's bandwidth in Hz, or 0 to select it from the sample rate. */
    public void setTunerBandwidth(long hertz) throws RtlSdrException {
        check("rtlsdr_set_tuner_bandwidth", Native.invoke(Native.SET_TUNER_BANDWIDTH, handle(), u32(hertz)));
    }

    /**
     * Sets the frequencies of the crystals clocking the RTL2832 and the tuner.
     * Use this only to compensate a known crystal error or for an external
     * clock. An RTL2832 frequency of 0 leaves it unchanged, and a tuner
     * frequency of 0 makes the tuner use the RTL2832's.
     */
    public void setCrystalFrequencies(long rtl2832Hertz, long tunerHertz) throws RtlSdrException {
        check("rtlsdr_set_xtal_freq",
            Native.invoke(Native.SET_XTAL_FREQ, handle(), u32(rtl2832Hertz), u32(tunerHertz)));
    }

    /** Returns the frequencies of the crystals clocking the RTL2832 and the tuner. */
    public CrystalFrequencies crystalFrequencies() throws RtlSdrException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rtl = arena.allocate(JAVA_INT);
            MemorySegment tuner = arena.allocate(JAVA_INT);
            check("rtlsdr_get_xtal_freq", Native.invoke(Native.GET_XTAL_FREQ, handle(), rtl, tuner));
            return new CrystalFrequencies(Integer.toUnsignedLong(rtl.get(JAVA_INT, 0)),
                Integer.toUnsignedLong(tuner.get(JAVA_INT, 0)));
        }
    }

    /**
     * Selects direct sampling, which bypasses the tuner. While it is enabled,
     * {@link #setCenterFrequency} tunes within 0 to 28.8 MHz.
     */
    public void setDirectSampling(DirectSampling mode) throws RtlSdrException {
        check("rtlsdr_set_direct_sampling", Native.invoke(Native.SET_DIRECT_SAMPLING, handle(), mode.code()));
    }

    /** Returns the direct sampling mode. */
    public DirectSampling directSampling() throws RtlSdrException {
        return DirectSampling.fromCode(
            check("rtlsdr_get_direct_sampling", Native.invoke(Native.GET_DIRECT_SAMPLING, handle())));
    }

    /**
     * Enables offset tuning, which avoids the DC spike of zero-IF tuners such as
     * the E4000. Other tuners report an error.
     */
    public void setOffsetTuning(boolean enabled) throws RtlSdrException {
        check("rtlsdr_set_offset_tuning", Native.invoke(Native.SET_OFFSET_TUNING, handle(), flag(enabled)));
    }

    /** Returns true if offset tuning is enabled. */
    public boolean isOffsetTuning() throws RtlSdrException {
        return check("rtlsdr_get_offset_tuning", Native.invoke(Native.GET_OFFSET_TUNING, handle())) != 0;
    }

    // ---- Gain ----

    /** Returns the gains the tuner supports, in tenths of a dB, from lowest to highest. */
    public int[] tunerGains() throws RtlSdrException {
        int count = check("rtlsdr_get_tuner_gains",
            Native.invoke(Native.GET_TUNER_GAINS, handle(), MemorySegment.NULL));
        if (count == 0) {
            return new int[0];
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment gains = arena.allocate(JAVA_INT, count);
            int found = check("rtlsdr_get_tuner_gains", Native.invoke(Native.GET_TUNER_GAINS, handle(), gains));
            return gains.asSlice(0, Math.min(found, count) * JAVA_INT.byteSize()).toArray(JAVA_INT);
        }
    }

    /**
     * Selects manual gain, which {@link #setTunerGain} needs, or the tuner's
     * automatic gain control.
     */
    public void setTunerGainMode(boolean manual) throws RtlSdrException {
        check("rtlsdr_set_tuner_gain_mode", Native.invoke(Native.SET_TUNER_GAIN_MODE, handle(), flag(manual)));
    }

    /**
     * Sets the tuner gain in tenths of a dB, which the tuner rounds to one of
     * {@link #tunerGains()}. Requires manual gain mode.
     */
    public void setTunerGain(int tenthsDb) throws RtlSdrException {
        check("rtlsdr_set_tuner_gain", Native.invoke(Native.SET_TUNER_GAIN, handle(), tenthsDb));
    }

    /** Returns the tuner gain in tenths of a dB, or 0 if it is unknown. */
    public int tunerGain() {
        return Native.invoke(Native.GET_TUNER_GAIN, handle());
    }

    /** Sets the gain of one intermediate frequency stage, in tenths of a dB. E4000 only. */
    public void setTunerIfGain(int stage, int tenthsDb) throws RtlSdrException {
        check("rtlsdr_set_tuner_if_gain", Native.invoke(Native.SET_TUNER_IF_GAIN, handle(), stage, tenthsDb));
    }

    /** Enables the digital automatic gain control of the RTL2832. */
    public void setDigitalAgc(boolean enabled) throws RtlSdrException {
        check("rtlsdr_set_agc_mode", Native.invoke(Native.SET_AGC_MODE, handle(), flag(enabled)));
    }

    // ---- Hardware ----

    /**
     * Switches the bias tee on GPIO 0. This puts DC power on the antenna
     * connector, which can damage equipment not built for it.
     */
    public void setBiasTee(boolean enabled) throws RtlSdrException {
        check("rtlsdr_set_bias_tee", Native.invoke(Native.SET_BIAS_TEE, handle(), flag(enabled)));
    }

    /** Switches a bias tee controlled by another GPIO pin. */
    public void setBiasTee(int gpio, boolean enabled) throws RtlSdrException {
        check("rtlsdr_set_bias_tee_gpio", Native.invoke(Native.SET_BIAS_TEE_GPIO, handle(), gpio, flag(enabled)));
    }

    /** Replaces the samples with an 8-bit counter generated by the RTL2832, for testing. */
    public void setTestMode(boolean enabled) throws RtlSdrException {
        check("rtlsdr_set_testmode", Native.invoke(Native.SET_TESTMODE, handle(), flag(enabled)));
    }

    /** Reads bytes from the EEPROM, which holds the USB strings and configuration. */
    public byte[] readEeprom(int offset, int length) throws RtlSdrException {
        checkEepromRange(offset, length);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(length, 1));
            check("rtlsdr_read_eeprom",
                Native.invoke(Native.READ_EEPROM, handle(), data, (byte) offset, (short) length));
            return data.asSlice(0, length).toArray(JAVA_BYTE);
        }
    }

    /**
     * Writes bytes to the EEPROM. A bad write can leave the device unrecognized
     * by drivers until the EEPROM is restored.
     */
    public void writeEeprom(int offset, byte[] data) throws RtlSdrException {
        checkEepromRange(offset, data.length);
        try (Arena arena = Arena.ofConfined()) {
            check("rtlsdr_write_eeprom", Native.invoke(Native.WRITE_EEPROM, handle(),
                arena.allocateFrom(JAVA_BYTE, data), (byte) offset, (short) data.length));
        }
    }

    private static void checkEepromRange(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > EEPROM_SIZE) {
            throw new IllegalArgumentException("EEPROM range out of bounds: offset " + offset + ", length " + length);
        }
    }

    // ---- Synchronous reading ----

    /**
     * Discards the samples buffered in the device. Call before
     * {@link #readSync}; {@link #startRx} does this itself.
     */
    public void resetBuffer() throws RtlSdrException {
        check("rtlsdr_reset_buffer", Native.invoke(Native.RESET_BUFFER, handle()));
    }

    /**
     * Reads samples into a buffer, blocking until it is full or the transfer
     * fails. The length should be a multiple of 512. Do not use while streaming.
     *
     * @return the number of bytes read
     */
    public int readSync(byte[] buffer) throws RtlSdrException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuffer = arena.allocate(Math.max(buffer.length, 1));
            int read = readSync(nativeBuffer.asSlice(0, buffer.length));
            MemorySegment.copy(nativeBuffer, JAVA_BYTE, 0, buffer, 0, read);
            return read;
        }
    }

    /**
     * Reads samples into native memory without copying; otherwise the same as
     * {@link #readSync(byte[])}.
     *
     * @return the number of bytes read
     */
    public int readSync(MemorySegment buffer) throws RtlSdrException {
        if (buffer.byteSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffer too large: " + buffer.byteSize());
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment read = arena.allocate(JAVA_INT);
            check("rtlsdr_read_sync",
                Native.invoke(Native.READ_SYNC, handle(), buffer, (int) buffer.byteSize(), read));
            return read.get(JAVA_INT, 0);
        }
    }

    // ---- Streaming ----

    /** Starts streaming samples to a listener with the library's default buffers. */
    public void startRx(SampleListener sampleListener) throws RtlSdrException {
        startRx(sampleListener, 0, 0);
    }

    /**
     * Starts streaming samples to a listener on a new thread.
     *
     * @param bufferCount number of USB transfers in flight, or 0 for the default of 15
     * @param bufferLength bytes per transfer and per {@link SampleBuffer}: a
     *     multiple of 512, ideally of 16384, or 0 for the default of 262144
     * @throws IllegalStateException if the device is already streaming
     */
    public void startRx(SampleListener sampleListener, int bufferCount, int bufferLength) throws RtlSdrException {
        Objects.requireNonNull(sampleListener, "sampleListener");
        if (bufferCount < 0) {
            throw new IllegalArgumentException("Negative buffer count: " + bufferCount);
        }
        if (bufferLength < 0 || bufferLength % 512 != 0) {
            throw new IllegalArgumentException("Buffer length must be a multiple of 512: " + bufferLength);
        }
        synchronized (lifecycleLock) {
            MemorySegment device = handle();
            if (isStreaming()) {
                throw new IllegalStateException("Device is already streaming");
            }
            // A listener may have ended the previous stream without stopRx.
            releaseStream();
            resetBuffer();

            streamingFailure = null;
            stopRequested = false;
            listener = sampleListener;
            Arena arena = Arena.ofShared();
            MemorySegment callback;
            try {
                callback = Native.readCallback(ON_SAMPLES.bindTo(this), arena);
            } catch (RuntimeException e) {
                arena.close();
                throw e;
            }
            streamArena = arena;
            // rtlsdr_read_async blocks until the stream is cancelled, and makes
            // the callbacks on the thread that called it.
            Thread thread = new Thread(() -> stream(device, callback, bufferCount, bufferLength), "rtlsdr-rx");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            streamThread = thread;
            thread.start();
        }
    }

    private void stream(MemorySegment device, MemorySegment callback, int bufferCount, int bufferLength) {
        try {
            int result = Native.invoke(Native.READ_ASYNC, device, callback, MemorySegment.NULL,
                bufferCount, bufferLength);
            if (result < 0 && !stopRequested && streamingFailure == null) {
                streamingFailure = new RtlSdrException("rtlsdr_read_async", result);
            }
        } catch (Throwable failure) {
            if (streamingFailure == null) {
                streamingFailure = failure;
            }
        }
    }

    /**
     * Stops streaming and waits for the streaming thread to finish. Does nothing
     * if the device is not streaming.
     *
     * @throws IllegalStateException if called from a {@link SampleListener}
     */
    public void stopRx() {
        rejectCallbackThread("stopRx");
        synchronized (lifecycleLock) {
            Thread thread = streamThread;
            if (thread == null) {
                return;
            }
            stopRequested = true;
            boolean interrupted = false;
            // Cancelling fails while rtlsdr_read_async is still starting, so keep
            // trying until the thread has finished. The upcall stub must outlive it.
            while (thread.isAlive()) {
                Native.invoke(Native.CANCEL_ASYNC, handle());
                try {
                    thread.join(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            releaseStream();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Returns true while the streaming thread is running. */
    public boolean isStreaming() {
        Thread thread = streamThread;
        return thread != null && thread.isAlive();
    }

    /** Returns the exception that stopped streaming, or null if there was none. */
    public Throwable streamingFailure() {
        return streamingFailure;
    }

    /**
     * Called by the streaming thread through the upcall stub. An exception that
     * escaped from here would terminate the JVM, so all of them are caught.
     */
    @SuppressWarnings("unused")
    private void onSamples(MemorySegment buffer, int length, MemorySegment context) {
        // Transfers that complete while the stream is cancelled still arrive here.
        if (stopRequested) {
            return;
        }
        // Closing this arena ends the samples segment's lifetime, so that a
        // listener that keeps it gets an exception and not freed native memory.
        try (Arena callbackArena = Arena.ofConfined()) {
            MemorySegment samples = buffer.reinterpret(Integer.toUnsignedLong(length), callbackArena, null);
            if (!listener.onSamples(new SampleBuffer(samples))) {
                cancelFromCallback();
            }
        } catch (Throwable failure) {
            if (streamingFailure == null) {
                streamingFailure = failure;
            }
            cancelFromCallback();
        }
    }

    private void cancelFromCallback() {
        stopRequested = true;
        try {
            Native.invoke(Native.CANCEL_ASYNC, handle);
        } catch (Throwable ignored) {
            // rtlsdr_read_async also stops when stopRequested is noticed by stopRx.
        }
    }

    private void releaseStream() {
        if (streamArena != null) {
            streamArena.close();
            streamArena = null;
        }
        streamThread = null;
        listener = null;
    }

    private void rejectCallbackThread(String method) {
        if (Thread.currentThread() == streamThread) {
            throw new IllegalStateException(method + " cannot be called from a SampleListener");
        }
    }

    // ---- Lifecycle ----

    /**
     * Stops streaming and releases the device. Closing again does nothing.
     *
     * @throws IllegalStateException if called from a {@link SampleListener}
     */
    @Override
    public void close() throws RtlSdrException {
        rejectCallbackThread("close");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            try {
                stopRx();
            } finally {
                closed = true;
                check("rtlsdr_close", Native.invoke(Native.CLOSE, handle));
            }
        }
    }

    private MemorySegment handle() {
        if (closed) {
            throw new IllegalStateException("Device is closed");
        }
        return handle;
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static int u32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Value out of 32-bit range: " + value);
        }
        return (int) value;
    }
}
