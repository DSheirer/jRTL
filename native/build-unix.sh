#!/usr/bin/env bash
# Builds libusb and librtlsdr as shared libraries for the host platform and
# copies them to dist/native/<platform>. Works on Linux and macOS.
#
# Usage: native/build-unix.sh [rtl-sdr-blog-git-ref]
#
# libusb stays a separate shared library because it is LGPL-2.1: keeping it out
# of librtlsdr lets users replace it. It is built without udev so the Linux
# binaries do not need libudev at runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/native/versions.env"
REF="${1:-$RTLSDR_REF}"
WORK="${JRTL_WORK_DIR:-$ROOT/build/native-work}"
JOBS="$(getconf _NPROCESSORS_ONLN)"

case "$(uname -s)" in
    Linux)  OS=linux ;;
    Darwin) OS=macos ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  ARCH=x86_64 ;;
    aarch64|arm64) ARCH=aarch64 ;;
    *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
PLATFORM="$OS-$ARCH"
OUT="$ROOT/dist/native/$PLATFORM"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT" "$ROOT/dist/native/licenses"
cd "$WORK"

echo "=== libusb $LIBUSB_VERSION ==="
TARBALL="libusb-$LIBUSB_VERSION.tar.bz2"
curl -fsSL -o "$TARBALL" \
    "https://github.com/libusb/libusb/releases/download/v$LIBUSB_VERSION/$TARBALL"
if command -v sha256sum >/dev/null; then
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | sha256sum -c -
else
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | shasum -a 256 -c -
fi
tar xjf "$TARBALL"

MIN_MACOS=11.0
USB_PREFIX="$WORK/usb"
USB_ARGS=(--prefix="$USB_PREFIX" --disable-static --enable-shared)
if [ "$OS" = linux ]; then
    USB_ARGS+=(--disable-udev)
else
    export CFLAGS="-O2 -mmacosx-version-min=$MIN_MACOS"
    export LDFLAGS="-mmacosx-version-min=$MIN_MACOS"
fi
(cd "libusb-$LIBUSB_VERSION" && ./configure "${USB_ARGS[@]}" >/dev/null && make -j"$JOBS" install >/dev/null)
unset CFLAGS LDFLAGS

echo "=== librtlsdr $REF ==="
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$REF" "$RTLSDR_REPOSITORY" rtlsdr
UPSTREAM_COMMIT="$(git -C rtlsdr rev-parse HEAD)"

# jRTL's fixes to upstream, applied in name order. A patch that no longer
# applies fails the build of the pinned ref. On upstream master it only warns,
# because upstream may have taken the fix, and the build continues without it.
PATCHES=""
for patch in "$ROOT"/native/patches/*.patch; do
    [ -e "$patch" ] || continue
    name="$(basename "$patch")"
    if git -C rtlsdr apply "$patch"; then
        PATCHES="${PATCHES:+$PATCHES,}$name"
        echo "Applied $name"
    elif [ "$REF" = "$RTLSDR_REF" ]; then
        echo "$name does not apply to $REF" >&2
        exit 1
    else
        echo "::warning::$name does not apply to $REF; building without it"
    fi
done

# Upstream finds libusb with pkg-config, which links by name and so cannot use
# a libusb outside the default search path. Disabling it makes upstream take the
# paths given here instead.
CMAKE_ARGS=(
    -S rtlsdr -B build-rtlsdr
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON
    -DLIBUSB_INCLUDE_DIRS="$USB_PREFIX/include/libusb-1.0"
)
if [ "$OS" = linux ]; then
    USB_LIB="$USB_PREFIX/lib/libusb-1.0.so"
    CMAKE_ARGS+=(
        # Lets the library claim a dongle from the kernel's DVB-T driver, so
        # users do not have to blacklist dvb_usb_rtl28xxu.
        -DDETACH_KERNEL_DRIVER=ON
        # $ORIGIN only: the build tree's paths must not leak into the shipped library.
        -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON -DCMAKE_INSTALL_RPATH='$ORIGIN')
else
    USB_LIB="$USB_PREFIX/lib/libusb-1.0.0.dylib"
    CMAKE_ARGS+=(-DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_MACOS")
fi
cmake "${CMAKE_ARGS[@]}" -DLIBUSB_LIBRARIES="$USB_LIB" >/dev/null
# Only the library: the command line tools need pthreads and getopt.
cmake --build build-rtlsdr --parallel "$JOBS" --target rtlsdr >/dev/null

echo "=== packaging $PLATFORM ==="
if [ "$OS" = linux ]; then
    cp -L "$USB_PREFIX/lib/libusb-1.0.so.0" "$OUT/libusb-1.0.so.0"
    cp -L build-rtlsdr/src/librtlsdr.so "$OUT/librtlsdr.so"
    chmod 755 "$OUT"/*
    # Only glibc and libusb may remain as dependencies.
    readelf -d "$OUT/librtlsdr.so" | grep -E 'NEEDED|RUNPATH|RPATH'
else
    cp -L "$USB_PREFIX/lib/libusb-1.0.0.dylib" "$OUT/libusb-1.0.0.dylib"
    cp -L build-rtlsdr/src/librtlsdr.dylib "$OUT/librtlsdr.dylib"
    chmod 755 "$OUT"/*
    # Point librtlsdr at the bundled libusb, not the absolute build path, and
    # re-sign because install_name_tool invalidates the ad-hoc signatures.
    install_name_tool -id @rpath/libusb-1.0.0.dylib "$OUT/libusb-1.0.0.dylib"
    install_name_tool -id @rpath/librtlsdr.dylib "$OUT/librtlsdr.dylib"
    install_name_tool -change "$USB_LIB" @loader_path/libusb-1.0.0.dylib "$OUT/librtlsdr.dylib"
    codesign -f -s - "$OUT"/*.dylib
    otool -L "$OUT/librtlsdr.dylib"
    if otool -L "$OUT/librtlsdr.dylib" | grep -qF "$WORK"; then
        echo "librtlsdr still refers to the build directory" >&2
        exit 1
    fi
fi

# Pinned sources must match the bindings. Tracking upstream master only warns,
# so that one new upstream function does not stop the nightly builds.
if ! "$ROOT/native/check-bindings.sh" rtlsdr/include/rtl-sdr.h; then
    [ "$REF" != "$RTLSDR_REF" ] || exit 1
    echo "::warning::Bindings differ from rtl-sdr.h at $REF"
fi

cp rtlsdr/COPYING "$ROOT/dist/native/licenses/rtl-sdr-COPYING"
cp "libusb-$LIBUSB_VERSION/COPYING" "$ROOT/dist/native/licenses/libusb-COPYING"
cat > "$OUT/build-info.properties" <<INFO
rtlsdr.ref=$REF
rtlsdr.commit=$UPSTREAM_COMMIT
rtlsdr.patches=$PATCHES
libusb.version=$LIBUSB_VERSION
INFO
ls -l "$OUT"
