package io.github.dsheirer.rtlsdr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Receive-only tests against a real device. Enable with {@code -PhardwareTest=true}. */
@EnabledIfSystemProperty(named = "rtlsdr.hardware.test", matches = "true")
class HardwareTest {
    private static final long FREQUENCY = 100_000_000L;
    private static final long SAMPLE_RATE = 2_048_000L;

    private static RtlSdrDevice openConfigured() throws RtlSdrException {
        RtlSdrDevice device = RtlSdr.open(0);
        device.setSampleRate(SAMPLE_RATE);
        device.setCenterFrequency(FREQUENCY);
        device.setTunerGainMode(false);
        return device;
    }

    @Test
    void describesDevice() throws RtlSdrException {
        List<DeviceInfo> devices = RtlSdr.devices();
        assertFalse(devices.isEmpty(), "No device found");
        try (RtlSdrDevice device = RtlSdr.open(0)) {
            assertNotEquals(Tuner.UNKNOWN, device.tunerType());
            UsbStrings usb = device.usbStrings();
            assertFalse(usb.product().isEmpty());
            assertTrue(device.isModel(usb.manufacturer(), usb.product()));
            assertFalse(device.isModel("jRTL", "no such model"));
            assertTrue(device.crystalFrequencies().rtl2832Hertz() > 0);
            assertEquals(DirectSampling.DISABLED, device.directSampling());
            assertEquals(RtlSdrDevice.EEPROM_SIZE, device.readEeprom(0, RtlSdrDevice.EEPROM_SIZE).length);
        }
    }

    @Test
    void tunesAndSetsGain() throws RtlSdrException {
        try (RtlSdrDevice device = openConfigured()) {
            assertEquals(FREQUENCY, device.centerFrequency());
            assertEquals(SAMPLE_RATE, device.sampleRate());
            device.setFrequencyCorrection(3);
            device.setFrequencyCorrection(3);
            assertEquals(3, device.frequencyCorrection());
            int[] gains = device.tunerGains();
            assertTrue(gains.length > 0);
            device.setTunerGainMode(true);
            device.setTunerGain(gains[gains.length / 2]);
            assertEquals(gains[gains.length / 2], device.tunerGain());
        }
    }

    @Test
    void readsSynchronously() throws RtlSdrException {
        try (RtlSdrDevice device = openConfigured()) {
            device.resetBuffer();
            byte[] buffer = new byte[16 * 16384];
            assertEquals(buffer.length, device.readSync(buffer));
        }
    }

    @Test
    void streamsSamplesAndStops() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            CountDownLatch received = new CountDownLatch(20);
            AtomicReference<byte[]> last = new AtomicReference<>();
            device.startRx(buffer -> {
                last.set(buffer.copyBytes());
                received.countDown();
                return true;
            }, 8, 16384);
            assertTrue(received.await(10, TimeUnit.SECONDS), "Samples did not arrive");
            assertTrue(device.isStreaming());
            device.stopRx();
            assertFalse(device.isStreaming());
            assertNull(device.streamingFailure());
            byte[] samples = last.get();
            assertEquals(16384, samples.length);
            boolean varies = false;
            for (byte sample : samples) {
                varies |= sample != samples[0];
            }
            assertTrue(varies, "Samples were constant");
        }
    }

    @Test
    void stopsImmediatelyAfterStarting() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            for (int run = 0; run < 5; run++) {
                device.startRx(buffer -> true);
                device.stopRx();
                assertFalse(device.isStreaming());
            }
        }
    }

    /**
     * Stops the stream while another thread makes control transfers. That thread
     * then handles the libusb events, including the completions of the cancelled
     * bulk transfers, so librtlsdr has to wait for them before freeing the
     * transfers.
     */
    @Test
    void stopsWhileAnotherThreadMakesControlTransfers() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            AtomicReference<Throwable> tuningFailure = new AtomicReference<>();
            AtomicInteger retunes = new AtomicInteger();
            Thread tuner = new Thread(() -> {
                long[] frequencies = {FREQUENCY, FREQUENCY + 1_000_000L};
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        device.setCenterFrequency(frequencies[retunes.getAndIncrement() % 2]);
                    } catch (Throwable t) {
                        tuningFailure.compareAndSet(null, t);
                        return;
                    }
                }
            }, "tuner");
            tuner.start();
            try {
                for (int run = 0; run < 300; run++) {
                    CountDownLatch received = new CountDownLatch(1 + run % 4);
                    device.startRx(buffer -> {
                        received.countDown();
                        return true;
                    }, 8, 16384);
                    assertTrue(received.await(10, TimeUnit.SECONDS), "No samples in run " + run);
                    device.stopRx();
                    assertFalse(device.isStreaming());
                    assertNull(device.streamingFailure(), "Stream failed in run " + run);
                }
            } finally {
                tuner.interrupt();
                tuner.join();
            }
            assertNull(tuningFailure.get());
            assertTrue(retunes.get() > 20, "Only " + retunes.get() + " retunes");
        }
    }

    @Test
    void canRestartAfterListenerEndedStream() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            for (int run = 0; run < 3; run++) {
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch received = new CountDownLatch(1);
                device.startRx(buffer -> {
                    calls.incrementAndGet();
                    received.countDown();
                    return false;
                });
                assertTrue(received.await(10, TimeUnit.SECONDS), "No samples in run " + run);
                for (int i = 0; i < 400 && device.isStreaming(); i++) {
                    Thread.sleep(5);
                }
                assertFalse(device.isStreaming());
                assertEquals(1, calls.get());
            }
            assertNull(device.streamingFailure());
        }
    }

    @Test
    void listenerExceptionStopsStreamInsteadOfCrashing() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                called.countDown();
                throw new IllegalArgumentException("expected");
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 100 && device.streamingFailure() == null; i++) {
                Thread.sleep(10);
            }
            assertInstanceOf(IllegalArgumentException.class, device.streamingFailure());
            device.stopRx();
        }
    }

    @Test
    void samplesCannotBeUsedAfterCallbackReturns() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            AtomicReference<MemorySegment> kept = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                kept.set(buffer.samples());
                called.countDown();
                return false;
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            device.stopRx();
            assertThrows(RuntimeException.class, () -> kept.get().get(ValueLayout.JAVA_BYTE, 0));
        }
    }

    @Test
    @SuppressWarnings("try")
    void stoppingFromListenerIsRejected() throws Exception {
        try (RtlSdrDevice device = openConfigured()) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch called = new CountDownLatch(1);
            device.startRx(buffer -> {
                try {
                    device.close();
                } catch (Throwable t) {
                    thrown.set(t);
                }
                called.countDown();
                return false;
            });
            assertTrue(called.await(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, thrown.get());
        }
    }
}
