package io.github.dsheirer.rtlsdr;

/**
 * USB string descriptors of a device. A string the device does not provide, or
 * that could not be read, is empty.
 *
 * @param manufacturer manufacturer name, such as {@code RTLSDRBlog}
 * @param product product name, such as {@code Blog V4}
 * @param serial serial number, the value accepted by {@link RtlSdr#open(String)}
 */
public record UsbStrings(String manufacturer, String product, String serial) {
    static final UsbStrings EMPTY = new UsbStrings("", "", "");
}
