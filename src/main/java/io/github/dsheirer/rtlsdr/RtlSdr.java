package io.github.dsheirer.rtlsdr;

import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.dsheirer.rtlsdr.internal.Native;
import io.github.dsheirer.rtlsdr.internal.NativeLibraryLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Entry point of the library: finds and opens RTL-SDR devices.
 *
 * <pre>{@code
 * try (RtlSdrDevice device = RtlSdr.open(0)) {
 *     device.setSampleRate(2_400_000);
 *     device.setCenterFrequency(100_000_000L);
 *     device.setTunerGainMode(true);
 *     device.setTunerGain(device.tunerGains()[10]);
 *     device.startRx(buffer -> {
 *         process(buffer.copyBytes());
 *         return true;
 *     });
 *     Thread.sleep(1000);
 * }
 * }</pre>
 *
 * <p>The methods of this class run one at a time, so that enumeration does not
 * race with opening a device.
 */
public final class RtlSdr {
    private static final Object LOCK = new Object();

    private RtlSdr() {
    }

    /** Describes the bundled native libraries. Does not load them. */
    public static BuildInfo buildInfo() {
        Properties info = NativeLibraryLoader.buildInfo();
        return new BuildInfo(info.getProperty("rtlsdr.ref", "unknown"),
            info.getProperty("rtlsdr.commit", "unknown"), info.getProperty("libusb.version", "unknown"));
    }

    /**
     * Returns the number of connected devices that librtlsdr recognizes. Returns
     * 0, rather than failing, when USB is not available.
     */
    public static int deviceCount() {
        synchronized (LOCK) {
            return Native.invoke(Native.GET_DEVICE_COUNT);
        }
    }

    /** Describes the connected devices. */
    public static List<DeviceInfo> devices() {
        synchronized (LOCK) {
            int count = Native.invoke(Native.GET_DEVICE_COUNT);
            List<DeviceInfo> devices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                devices.add(new DeviceInfo(i, Native.deviceName(i), usbStrings(i)));
            }
            return List.copyOf(devices);
        }
    }

    private static UsbStrings usbStrings(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment manufacturer = arena.allocate(Native.USB_STRING_LENGTH);
            MemorySegment product = arena.allocate(Native.USB_STRING_LENGTH);
            MemorySegment serial = arena.allocate(Native.USB_STRING_LENGTH);
            // Fails when another process has the device open.
            if (Native.invoke(Native.GET_DEVICE_USB_STRINGS, index, manufacturer, product, serial) != 0) {
                return UsbStrings.EMPTY;
            }
            return new UsbStrings(manufacturer.getString(0), product.getString(0), serial.getString(0));
        }
    }

    /** Opens the device at an index from {@link #devices()}. */
    public static RtlSdrDevice open(int index) throws RtlSdrException {
        if (index < 0) {
            throw new IllegalArgumentException("Negative device index: " + index);
        }
        synchronized (LOCK) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pointer = arena.allocate(ADDRESS);
                check("rtlsdr_open", Native.invoke(Native.OPEN, pointer, index));
                return new RtlSdrDevice(pointer.get(ADDRESS, 0));
            }
        }
    }

    /** Opens the first device with a USB serial number. */
    public static RtlSdrDevice open(String serial) throws RtlSdrException {
        Objects.requireNonNull(serial, "serial");
        synchronized (LOCK) {
            int index;
            try (Arena arena = Arena.ofConfined()) {
                index = check("rtlsdr_get_index_by_serial",
                    Native.invoke(Native.GET_INDEX_BY_SERIAL, arena.allocateFrom(serial)));
            }
            return open(index);
        }
    }

    static int check(String function, int result) throws RtlSdrException {
        if (result < 0) {
            throw new RtlSdrException(function, result);
        }
        return result;
    }
}
