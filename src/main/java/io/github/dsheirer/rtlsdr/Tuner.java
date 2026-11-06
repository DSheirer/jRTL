package io.github.dsheirer.rtlsdr;

/** Tuner chips, from {@code enum rtlsdr_tuner}. */
public enum Tuner {
    UNKNOWN,
    E4000,
    FC0012,
    FC0013,
    FC2580,
    R820T,
    R828D;

    /** Returns the tuner for a native code, or {@link #UNKNOWN} if it is not known. */
    public static Tuner fromCode(int code) {
        Tuner[] values = values();
        return code >= 0 && code < values.length ? values[code] : UNKNOWN;
    }
}
