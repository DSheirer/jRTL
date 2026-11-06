package io.github.dsheirer.rtlsdr;

/**
 * Describes the native libraries bundled in the jar. librtlsdr has no version
 * function, so this is recorded when the libraries are built. Each value is
 * {@code unknown} if the jar has no record, as when the libraries come from
 * {@code jrtl.library.path}.
 *
 * @param rtlSdrRef the rtl-sdr-blog git tag or branch, such as {@code V1.4.0}
 * @param rtlSdrCommit the rtl-sdr-blog git commit
 * @param libusbVersion the libusb release
 */
public record BuildInfo(String rtlSdrRef, String rtlSdrCommit, String libusbVersion) {
}
