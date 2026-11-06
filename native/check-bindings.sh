#!/usr/bin/env bash
# Compares the functions in rtl-sdr.h with the bindings in Native.java, so a new
# upstream function fails the build rather than being silently unavailable.
#
# Usage: native/check-bindings.sh <path-to-rtl-sdr.h>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEADER="$1"
BINDINGS="$ROOT/src/main/java/io/github/dsheirer/rtlsdr/internal/Native.java"
# Exported but deliberately unbound: rtlsdr_wait_async is deprecated upstream
# and is rtlsdr_read_async with the default buffers.
UNBOUND="rtlsdr_wait_async"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Declarations look like: RTLSDR_API <type> [*]name(
# Functions without RTLSDR_API, such as _rtlsdr_set_direct_sampling, are not
# exported from the Windows DLL, so they cannot be bound.
grep -E '^RTLSDR_API' "$HEADER" \
    | sed -E 's/.*[ *](rtlsdr_[a-z0-9_]+)[ ]*\(.*/\1/' \
    | grep -vxF -e "$UNBOUND" | sort -u > "$WORK/header"
grep -oE '"rtlsdr_[a-z0-9_]+"' "$BINDINGS" | tr -d '"' | sort -u > "$WORK/bindings"

MISSING="$(comm -23 "$WORK/header" "$WORK/bindings")"
STALE="$(comm -13 "$WORK/header" "$WORK/bindings")"
if [ -n "$MISSING" ] || [ -n "$STALE" ]; then
    [ -z "$MISSING" ] || printf 'Functions in rtl-sdr.h without a binding:\n%s\n' "$MISSING" >&2
    [ -z "$STALE" ] || printf 'Bindings not in rtl-sdr.h:\n%s\n' "$STALE" >&2
    exit 1
fi
echo "Bindings match rtl-sdr.h ($(wc -l < "$WORK/header" | tr -d ' ') functions)"
