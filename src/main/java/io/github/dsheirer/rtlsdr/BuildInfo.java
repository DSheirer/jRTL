package io.github.dsheirer.rtlsdr;

import java.util.List;

/**
 * Describes the native libraries bundled in the jar. librtlsdr has no version
 * function, so this is recorded when the libraries are built. Each string is
 * {@code unknown}, and the patch list empty, if the jar has no record, as when
 * the libraries come from {@code jrtl.library.path}.
 *
 * @param rtlSdrRef the rtl-sdr-blog git tag or branch, such as {@code V1.4.0}
 * @param rtlSdrCommit the rtl-sdr-blog git commit
 * @param libusbVersion the libusb release
 * @param rtlSdrPatches the jRTL patches applied to rtl-sdr-blog, by file name
 *     in {@code native/patches}, in the order applied
 */
public record BuildInfo(String rtlSdrRef, String rtlSdrCommit, String libusbVersion,
                        List<String> rtlSdrPatches) {

    /** Copies the patch list, so the record cannot change. */
    public BuildInfo {
        rtlSdrPatches = List.copyOf(rtlSdrPatches);
    }

    /**
     * Creates a record with no patches, as before jRTL recorded them.
     *
     * @param rtlSdrRef the rtl-sdr-blog git tag or branch
     * @param rtlSdrCommit the rtl-sdr-blog git commit
     * @param libusbVersion the libusb release
     */
    public BuildInfo(String rtlSdrRef, String rtlSdrCommit, String libusbVersion) {
        this(rtlSdrRef, rtlSdrCommit, libusbVersion, List.of());
    }
}
