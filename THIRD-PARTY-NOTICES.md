# Third-party notices

jRTL is licensed under the Apache License 2.0. The jar also contains native
libraries from the projects below, which keep their own licenses. Full license
texts are in `META-INF/licenses/` inside the jar.

## librtlsdr (rtl-sdr-blog)

Source: <https://github.com/rtlsdrblog/rtl-sdr-blog>, a fork of
<https://gitea.osmocom.org/sdr/rtl-sdr>

Licensed under the GNU General Public License version 2 or later. See
`META-INF/licenses/rtl-sdr-COPYING`.

The bundled librtlsdr is modified: jRTL applies the patches in `native/patches`
of this repository to the upstream source. Each patch describes its change and
the files it modifies carry a comment describing it. The exact upstream commit
and the patches applied in each jar are recorded in
`jrtl/native/<platform>/build-info.properties` (`rtlsdr.commit` and
`rtlsdr.patches`). Every jRTL release and nightly build publishes the matching
source archive, `rtl-sdr-blog-<ref>-source.tar.gz`, next to the jar: the
modified source that was built, with the patch files in its `jrtl-patches`
directory. The modifications are under the same license as librtlsdr.

jRTL is an independent project and is not endorsed by RTL-SDR Blog, Osmocom or
their contributors.

## libusb

Source: <https://github.com/libusb/libusb>

Licensed under the GNU Lesser General Public License version 2.1 or later. See
`META-INF/licenses/libusb-COPYING`. The exact version in each jar is recorded in
`jrtl/native/<platform>/build-info.properties`, and `native/versions.env` in
this repository records the release it was built from.

libusb is shipped as a separate shared library so it can be replaced: point the
`jrtl.library.path` system property at a directory containing your own build
along with librtlsdr.
