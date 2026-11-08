package io.github.dsheirer.rtlsdr.internal;

import io.github.dsheirer.libusb.NativeLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.SymbolLookup;
import java.util.List;
import java.util.Properties;

/**
 * Loads librtlsdr through jlibusb, which supplies the libusb it depends on.
 *
 * <p>By default librtlsdr is extracted from the jar, next to jlibusb's copy of
 * libusb. Set the {@value #PATH_PROPERTY} system property to a directory to
 * load librtlsdr from there instead; set {@code jlibusb.library.path} to
 * replace libusb.
 */
public final class NativeLibraryLoader {
    /** System property naming a directory that overrides the bundled librtlsdr. */
    public static final String PATH_PROPERTY = "jrtl.library.path";

    private static final String RESOURCE_ROOT = "/jrtl/native/";

    private NativeLibraryLoader() {
    }

    /** File name of librtlsdr on a platform, such as {@code linux-x86_64}. */
    public static String libraryName(String platform) {
        if (platform.startsWith("linux")) {
            return "librtlsdr.so";
        }
        if (platform.startsWith("macos")) {
            return "librtlsdr.dylib";
        }
        return "rtlsdr.dll";
    }

    /**
     * Returns the {@code build-info.properties} that describes the bundled
     * librtlsdr for the current platform, which is empty if the jar has none.
     * librtlsdr has no version function, so this is the only record of the
     * upstream release it was built from.
     */
    public static Properties buildInfo() {
        Properties info = new Properties();
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(
                RESOURCE_ROOT + NativeLoader.platform() + "/build-info.properties")) {
            if (in != null) {
                info.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read build-info.properties", e);
        }
        return info;
    }

    /** Loads libusb and librtlsdr and returns a lookup for the symbols of librtlsdr. */
    static SymbolLookup load() {
        String name = libraryName(NativeLoader.platform());
        return NativeLoader.load(NativeLibraryLoader.class, RESOURCE_ROOT, List.of(name), PATH_PROPERTY);
    }
}
