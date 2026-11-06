package io.github.dsheirer.rtlsdr;

/**
 * Crystal oscillator frequencies of a device.
 *
 * @param rtl2832Hertz frequency clocking the RTL2832 demodulator
 * @param tunerHertz frequency clocking the tuner chip
 */
public record CrystalFrequencies(long rtl2832Hertz, long tunerHertz) {
}
