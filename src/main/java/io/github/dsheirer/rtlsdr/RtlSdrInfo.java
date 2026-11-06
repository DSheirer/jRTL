package io.github.dsheirer.rtlsdr;

import java.util.Arrays;
import java.util.List;

/** Command line tool that prints the bundled library versions and the connected devices. */
public final class RtlSdrInfo {
    private RtlSdrInfo() {
    }

    /** Runs the tool. */
    public static void main(String[] args) {
        // Listing first loads the native libraries, so that the version line
        // below is only printed when they work on this machine.
        List<DeviceInfo> devices = RtlSdr.devices();
        BuildInfo build = RtlSdr.buildInfo();
        System.out.println("librtlsdr " + build.rtlSdrRef() + " (rtl-sdr-blog " + build.rtlSdrCommit()
            + "), libusb " + build.libusbVersion());
        System.out.println("Devices found: " + devices.size());
        for (DeviceInfo info : devices) {
            UsbStrings usb = info.usbStrings();
            System.out.println("[" + info.index() + "] " + info.name());
            System.out.println("  USB:          " + usb.manufacturer() + " / " + usb.product()
                + " / serial " + usb.serial());
            try (RtlSdrDevice device = RtlSdr.open(info.index())) {
                System.out.println("  Tuner:        " + device.tunerType());
                System.out.println("  Gains:        " + Arrays.toString(device.tunerGains()) + " (0.1 dB)");
            } catch (RtlSdrException e) {
                System.out.println("  Unable to open: " + e.getMessage());
            }
        }
    }
}
