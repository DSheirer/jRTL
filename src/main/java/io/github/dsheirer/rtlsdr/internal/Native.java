package io.github.dsheirer.rtlsdr.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Downcall handles for the public functions of librtlsdr.
 *
 * <p>Every C type in {@code rtl-sdr.h} has a fixed width ({@code uint32_t},
 * {@code int}, enums and pointers), so one set of descriptors is correct on all
 * supported platforms. The deprecated {@code rtlsdr_wait_async} is
 * intentionally absent: it is {@code rtlsdr_read_async} with default buffers.
 * {@code native/check-bindings.sh} compares this list against {@code rtl-sdr.h}.
 */
public final class Native {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = NativeLibraryLoader.load();

    /** Size of each buffer that the USB string functions fill. */
    public static final int USB_STRING_LENGTH = 256;

    public static final MethodHandle GET_DEVICE_COUNT = call("rtlsdr_get_device_count");
    public static final MethodHandle GET_DEVICE_NAME = LINKER.downcallHandle(symbol("rtlsdr_get_device_name"),
        FunctionDescriptor.of(ADDRESS, JAVA_INT));
    public static final MethodHandle GET_DEVICE_USB_STRINGS =
        call("rtlsdr_get_device_usb_strings", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle GET_INDEX_BY_SERIAL = call("rtlsdr_get_index_by_serial", ADDRESS);
    public static final MethodHandle OPEN = call("rtlsdr_open", ADDRESS, JAVA_INT);
    public static final MethodHandle CLOSE = call("rtlsdr_close", ADDRESS);

    public static final MethodHandle SET_XTAL_FREQ = call("rtlsdr_set_xtal_freq", ADDRESS, JAVA_INT, JAVA_INT);
    public static final MethodHandle GET_XTAL_FREQ = call("rtlsdr_get_xtal_freq", ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle GET_USB_STRINGS =
        call("rtlsdr_get_usb_strings", ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle WRITE_EEPROM =
        call("rtlsdr_write_eeprom", ADDRESS, ADDRESS, JAVA_BYTE, JAVA_SHORT);
    public static final MethodHandle READ_EEPROM =
        call("rtlsdr_read_eeprom", ADDRESS, ADDRESS, JAVA_BYTE, JAVA_SHORT);
    public static final MethodHandle CHECK_DONGLE_MODEL =
        call("rtlsdr_check_dongle_model", ADDRESS, ADDRESS, ADDRESS);

    public static final MethodHandle SET_CENTER_FREQ = call("rtlsdr_set_center_freq", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_CENTER_FREQ = call("rtlsdr_get_center_freq", ADDRESS);
    public static final MethodHandle SET_FREQ_CORRECTION = call("rtlsdr_set_freq_correction", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_FREQ_CORRECTION = call("rtlsdr_get_freq_correction", ADDRESS);

    public static final MethodHandle GET_TUNER_TYPE = call("rtlsdr_get_tuner_type", ADDRESS);
    public static final MethodHandle GET_TUNER_GAINS = call("rtlsdr_get_tuner_gains", ADDRESS, ADDRESS);
    public static final MethodHandle SET_TUNER_GAIN = call("rtlsdr_set_tuner_gain", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_TUNER_GAIN = call("rtlsdr_get_tuner_gain", ADDRESS);
    public static final MethodHandle SET_TUNER_BANDWIDTH = call("rtlsdr_set_tuner_bandwidth", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_TUNER_IF_GAIN =
        call("rtlsdr_set_tuner_if_gain", ADDRESS, JAVA_INT, JAVA_INT);
    public static final MethodHandle SET_TUNER_GAIN_MODE = call("rtlsdr_set_tuner_gain_mode", ADDRESS, JAVA_INT);

    public static final MethodHandle SET_SAMPLE_RATE = call("rtlsdr_set_sample_rate", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_SAMPLE_RATE = call("rtlsdr_get_sample_rate", ADDRESS);
    public static final MethodHandle SET_TESTMODE = call("rtlsdr_set_testmode", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_AGC_MODE = call("rtlsdr_set_agc_mode", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_DIRECT_SAMPLING = call("rtlsdr_set_direct_sampling", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_DIRECT_SAMPLING = call("rtlsdr_get_direct_sampling", ADDRESS);
    public static final MethodHandle SET_OFFSET_TUNING = call("rtlsdr_set_offset_tuning", ADDRESS, JAVA_INT);
    public static final MethodHandle GET_OFFSET_TUNING = call("rtlsdr_get_offset_tuning", ADDRESS);
    public static final MethodHandle SET_BIAS_TEE = call("rtlsdr_set_bias_tee", ADDRESS, JAVA_INT);
    public static final MethodHandle SET_BIAS_TEE_GPIO =
        call("rtlsdr_set_bias_tee_gpio", ADDRESS, JAVA_INT, JAVA_INT);

    public static final MethodHandle RESET_BUFFER = call("rtlsdr_reset_buffer", ADDRESS);
    public static final MethodHandle READ_SYNC = call("rtlsdr_read_sync", ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    public static final MethodHandle READ_ASYNC =
        call("rtlsdr_read_async", ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);
    public static final MethodHandle CANCEL_ASYNC = call("rtlsdr_cancel_async", ADDRESS);

    private Native() {
    }

    private static MemorySegment symbol(String name) {
        return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("librtlsdr has no symbol " + name));
    }

    private static MethodHandle call(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.of(JAVA_INT, arguments));
    }

    /**
     * Calls a native function returning {@code int}. This boxes its arguments,
     * which is fine for the control paths using it; samples arrive by upcall.
     */
    public static int invoke(MethodHandle function, Object... arguments) {
        try {
            return (int) function.invokeWithArguments(arguments);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Returns the name of the device at an index, or an empty string if there is none. */
    public static String deviceName(int index) {
        try {
            String name = cString((MemorySegment) GET_DEVICE_NAME.invokeExact(index));
            return name == null ? "" : name;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /** Reads a NUL-terminated string that the library owns. */
    public static String cString(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Creates an upcall stub for {@code rtlsdr_read_async_cb_t}. */
    public static MemorySegment readCallback(MethodHandle target, Arena arena) {
        return LINKER.upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS), arena);
    }
}
