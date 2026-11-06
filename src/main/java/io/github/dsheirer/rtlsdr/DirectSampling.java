package io.github.dsheirer.rtlsdr;

/** Direct sampling modes, from {@code enum rtlsdr_ds_mode}. */
public enum DirectSampling {
    /** Normal I/Q sampling of the tuner output. */
    DISABLED,
    /** Direct sampling on the I branch, which is usually not connected. */
    I_BRANCH,
    /** Direct sampling on the Q branch, the HF input of RTL-SDR Blog dongles. */
    Q_BRANCH;

    /** Returns the native value. */
    public int code() {
        return ordinal();
    }

    /** Returns the mode for a native value. */
    public static DirectSampling fromCode(int code) {
        DirectSampling[] values = values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("Unknown direct sampling mode: " + code);
        }
        return values[code];
    }
}
