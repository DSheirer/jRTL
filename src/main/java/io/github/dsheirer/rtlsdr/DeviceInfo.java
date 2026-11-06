package io.github.dsheirer.rtlsdr;

/**
 * A connected device, as found by {@link RtlSdr#devices()}.
 *
 * @param index the index accepted by {@link RtlSdr#open(int)}
 * @param name the library's name for the device's USB vendor and product IDs
 * @param usbStrings the USB strings, which are empty if the device is in use
 */
public record DeviceInfo(int index, String name, UsbStrings usbStrings) {
}
