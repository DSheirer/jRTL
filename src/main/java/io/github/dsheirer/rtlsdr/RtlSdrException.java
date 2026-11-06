package io.github.dsheirer.rtlsdr;

/**
 * Thrown when a librtlsdr function reports an error.
 *
 * <p>librtlsdr has no error enumeration: most functions return -1 for an invalid
 * device and pass other failures through from libusb, whose codes are listed in
 * {@code libusb.h}.
 */
public class RtlSdrException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String function;
    private final int code;

    RtlSdrException(String function, int code) {
        super(function + " failed (" + code + ")" + hint(function, code));
        this.function = function;
        this.code = code;
    }

    /** Returns the name of the native function that failed. */
    public String function() {
        return function;
    }

    /** Returns the value the native function returned. */
    public int code() {
        return code;
    }

    private static String hint(String function, int code) {
        return switch (function) {
            case "rtlsdr_open" -> switch (code) {
                case -3 -> ": access denied; on Linux, install the udev rules";
                case -4 -> ": the device was disconnected";
                case -6 -> ": the device is in use by another program";
                case -12 -> ": on Windows, the device needs the WinUSB driver";
                default -> "";
            };
            case "rtlsdr_get_index_by_serial" -> switch (code) {
                case -2 -> ": no devices found";
                case -3 -> ": no device has that serial number";
                default -> "";
            };
            default -> "";
        };
    }
}
