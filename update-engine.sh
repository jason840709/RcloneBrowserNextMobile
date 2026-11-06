#!/bin/bash
# Download the official Android ARM64 engine and record its checksum.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=${1:?Usage: ./update-engine.sh <rclone version, e.g. 1.75.2>}
VERSION=${VERSION#v}
URL="https://beta.rclone.org/v$VERSION/testbuilds/rclone-android-21-armv8a.gz"
STORE="downloads/rclone/v$VERSION"
GZ="$STORE/rclone-android-21-armv8a.gz"
mkdir -p "$STORE"
if [ -f "$GZ" ] && [ -f "$STORE/SHA256SUMS.txt" ]; then
  echo "Using the copy downloaded earlier: $GZ"
  (cd "$STORE" && shasum -a 256 -c SHA256SUMS.txt)
else
  echo "Downloading $URL"
  curl -fL --retry 2 -m 900 -o "$GZ.part" "$URL"
  mv "$GZ.part" "$GZ"
  (cd "$STORE" && shasum -a 256 rclone-android-21-armv8a.gz > SHA256SUMS.txt)
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
gunzip -c "$GZ" > "$TMP/librclone.so"
DESC=$(file "$TMP/librclone.so")
case "$DESC" in
  *"ELF 64-bit"*"ARM aarch64"*"/system/bin/linker64"*) ;;
  *) echo "Not an Android ARM64 executable: $DESC" >&2; exit 1 ;;
esac
if ! LC_ALL=C grep -a -q "github.com/rclone/rclone	v$VERSION" "$TMP/librclone.so"; then
  echo "The embedded build info does not say rclone v$VERSION; stopping." >&2
  exit 1
fi
NEW=$(shasum -a 256 "$TMP/librclone.so" | cut -d' ' -f1)
OLD=$(cut -d' ' -f1 engine.sha256)
if [ "$NEW" = "$OLD" ] && [ -f lib/arm64-v8a/librclone.so ] && [ "$(shasum -a 256 lib/arm64-v8a/librclone.so | cut -d' ' -f1)" = "$NEW" ]; then
  echo "lib/arm64-v8a/librclone.so is already this build ($NEW); nothing changed."
  exit 0
fi
mkdir -p lib/arm64-v8a
cp "$TMP/librclone.so" lib/arm64-v8a/librclone.so
shasum -a 256 lib/arm64-v8a/librclone.so > engine.sha256
echo "Engine replaced: $OLD -> $NEW"
echo "Next: bump the app version, ./build.sh, then run both test suites (see the header of this script)."
