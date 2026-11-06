#!/usr/bin/env bash
# Writes the source of a bundled librtlsdr: the upstream commit with the jRTL
# patches that the build applied, and the patch files themselves under
# jrtl-patches/. librtlsdr is GPL, so releases publish this next to the jar.
#
# Usage: native/source-archive.sh <build-info.properties> <prefix> <output.tar.gz>
#
# The commit and patch list come from build-info.properties in the jar, so the
# archive matches the binaries even if upstream moved during the build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/native/versions.env"
INFO="$1"
PREFIX="$2"
OUTPUT="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"

commit="$(sed -n 's/^rtlsdr\.commit=//p' "$INFO")"
patches="$(sed -n 's/^rtlsdr\.patches=//p' "$INFO")"
[ -n "$commit" ] || { echo "No rtlsdr.commit in $INFO" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git init -q "$WORK/src"
git -C "$WORK/src" fetch -q --depth 1 "$RTLSDR_REPOSITORY" "$commit"
git -C "$WORK/src" -c advice.detachedHead=false checkout -q FETCH_HEAD
if [ -n "$patches" ]; then
    mkdir "$WORK/src/jrtl-patches"
    IFS=, read -ra names <<< "$patches"
    for name in "${names[@]}"; do
        git -C "$WORK/src" apply "$ROOT/native/patches/$name"
        cp "$ROOT/native/patches/$name" "$WORK/src/jrtl-patches/"
    done
fi
git -C "$WORK/src" add -A
git -C "$WORK/src" archive --prefix="$PREFIX/" -o "$OUTPUT" "$(git -C "$WORK/src" write-tree)"
echo "Wrote $OUTPUT from $commit${patches:+ with $patches}"
