# jRTL

Java bindings for librtlsdr from [rtl-sdr-blog](https://github.com/rtlsdrblog/rtl-sdr-blog),
the driver for RTL2832U-based software-defined radios such as the RTL-SDR Blog
V3 and V4, built on the Java Foreign Function & Memory API. The jar contains
librtlsdr for every supported platform and needs one other jar,
[jlibusb](https://github.com/DSheirer/jlibusb), which supplies libusb. There is
nothing to install. The bundled librtlsdr
carries a small number of jRTL fixes; see [Patches](#patches).

| OS | x86-64 | aarch64 |
|---------|----|----|
| Linux (glibc 2.34+, such as Ubuntu 22.04, Debian 12, RHEL 9) | ✓ | ✓ |
| macOS 11+ | ✓ | ✓ |
| Windows | ✓ | ✓ |

Requires Java 23 or later.

## Getting it

Download `jrtl-<version>.jar` from
[Releases](https://github.com/DSheirer/jRTL/releases), and the
`jlibusb-<version>.jar` it was built with from
[jlibusb releases](https://github.com/DSheirer/jlibusb/releases). The rolling
[`nightly`](https://github.com/DSheirer/jRTL/releases/tag/nightly)
pre-release tracks `main` of this project and `master` of upstream.

Check that your machine can see a device:

```
java -jar jrtl-<version>.jar
```

## Using it

```java
try (RtlSdrDevice device = RtlSdr.open(0)) {
    device.setSampleRate(2_400_000);
    device.setCenterFrequency(100_000_000L);
    device.setTunerGainMode(true);                  // manual gain
    device.setTunerGain(device.tunerGains()[10]);   // tenths of a dB

    device.startRx(buffer -> {
        byte[] iq = buffer.copyBytes();             // interleaved unsigned I, Q
        queue.offer(iq);
        return true;                                // false stops the stream
    });
    Thread.sleep(10_000);
}
```

`RtlSdr.devices()` lists the connected devices with their USB strings, and
`RtlSdr.open(String serial)` opens one by serial number.

`startRx` runs the driver's blocking `rtlsdr_read_async` on its own thread, which
also calls the `SampleListener`, so hand slow work to another thread.
`buffer.samples()` gives zero-copy access, valid only until the listener
returns; later use throws an exception rather than reading freed memory.
`buffer.toFloats()` converts to floats from -1 to 1. An exception thrown by a
listener stops the stream and is available from `streamingFailure()`. The
buffer count and size can be set with `startRx(listener, count, length)`.

For HF, the rtl-sdr-blog driver switches by itself when tuned below 24 MHz: to
direct sampling on R820T dongles such as the V3, and to the upconverter on the
V4. `setDirectSampling` forces a mode instead. `setBiasTee(true)` powers an
active antenna or LNA.

### Native access

Java prints a warning, which future versions will turn into an error, unless
you allow native access:

```
java --enable-native-access=ALL-UNNAMED -cp app.jar:jrtl.jar:jlibusb.jar my.App
```

On the module path, use `--enable-native-access=io.github.dsheirer.rtlsdr,io.github.dsheirer.libusb`. In
your own executable jar, add `Enable-Native-Access: ALL-UNNAMED` to the manifest.

### USB access

- **Linux:** install upstream's
  [udev rules](https://github.com/rtlsdrblog/rtl-sdr-blog/blob/master/rtl-sdr.rules)
  to use a device without root. The bundled library detaches the kernel's DVB-T
  driver when it opens a device, so blacklisting `dvb_usb_rtl28xxu` is optional.
- **Windows:** the device needs the WinUSB driver, which
  [Zadig](https://zadig.akeo.ie/) installs.

### Native library location

librtlsdr is extracted at startup into the private temporary directory that
jlibusb extracts libusb to, so the process holds one copy of libusb shared with
any other library built on jlibusb. Set `-Djrtl.library.path=<dir>` to load
librtlsdr from a directory instead, and `-Djlibusb.library.path=<dir>` to
supply your own build of libusb. If the temporary directory is mounted
`noexec`, point `-Djava.io.tmpdir` elsewhere.

## Building

You need JDK 23+, CMake, a C compiler, make and git. On Windows use Visual Studio
and 7-Zip.

```
./native/build-unix.sh                                # Linux and macOS
pwsh native/build-windows.ps1 -Arch x64               # Windows; or -Arch ARM64
./gradlew build                                       # jar in build/libs
./gradlew test -PhardwareTest=true                    # also test a connected device
```

A local jar contains only your platform. The hardware tests are receive-only.

### Patches

The build applies the patches in [`native/patches`](native/patches) to the
upstream source, in name order, and records the ones it applied as
`rtlsdr.patches` in `build-info.properties` and in `RtlSdr.buildInfo()`. A patch
that no longer applies fails the build of the pinned version. The nightly build
of upstream `master` warns and builds without it, since upstream may have taken
the fix.

| Patch | Fixes |
|-------|-------|
| `0001-pre-read-before-control-writes.patch` | `rtlsdr_demod_write_reg failed with -9` when a streaming device is changed after a pause, such as a retune, and `read_eeprom` failing while streaming. The RTL2832U stalls the first control write after about 100–300 ms without control traffic; a register read just before the write prevents it. |

To add a patch, commit the change on top of the pinned tag in a clone of
upstream and export it with `git format-patch -1 --zero-commit -o native/patches`,
then rename it so that the names sort in the order to apply.

## Releases

jRTL versions follow upstream: jRTL 1.4.0 wraps rtl-sdr-blog V1.4.0. The
upstream version is pinned in [`native/versions.env`](native/versions.env), which
is the only place the version is set.

| Trigger | What happens |
|---------|--------------|
| Pull request | Builds and tests all six platforms; publishes nothing |
| Merge to `main` | Replaces the `nightly` pre-release, built against upstream `master` |
| Daily schedule | The same, if this project or upstream has changed |
| Tag `vX.Y.Z` | Publishes a release built from the pinned upstream version |

To release, tag the pinned version: `git tag v1.4.0 && git push origin v1.4.0`.
The workflow rejects a tag that does not match the pin. For a jRTL fix on the
same upstream version, in the Java code or a new native patch, add a fourth
number, such as `v1.4.0.1`; for a pre-release add a suffix, such as `v1.4.0-rc1`.

To adopt a new upstream release, change `RTLSDR_REF` and `RTLSDR_VERSION` in
`native/versions.env`, which also moves the jRTL version. If upstream added
functions, the build lists them; add them to `Native.java`.

Every release and nightly also carries `rtl-sdr-blog-<ref>-source.tar.gz`, the
exact source of the bundled librtlsdr, as its GPL license requires: the upstream
commit with the jRTL patches applied, and the patch files in its `jrtl-patches`
directory.

## License

Apache License 2.0. The bundled native libraries have their own licenses: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
