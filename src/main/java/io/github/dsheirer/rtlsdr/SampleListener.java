package io.github.dsheirer.rtlsdr;

/**
 * Receives samples while a device is streaming.
 *
 * <p>Runs on the device's streaming thread, which also services USB transfers,
 * so it should return quickly: copy the samples and hand them to another thread
 * for slow work. It must not call {@link RtlSdrDevice#stopRx()} or
 * {@link RtlSdrDevice#close()}; return false to stop instead. If it throws,
 * streaming stops and the exception is available from
 * {@link RtlSdrDevice#streamingFailure()}.
 */
@FunctionalInterface
public interface SampleListener {
    /**
     * Handles a block of samples.
     *
     * @return true to keep streaming, false to stop
     */
    boolean onSamples(SampleBuffer buffer);
}
