#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}
JDK=${JAVA_HOME:-$(/usr/libexec/java_home)}
BUILD="$PWD/build"
shasum -a 256 -c engine.sha256
rm -rf "$BUILD/classes" "$BUILD/dex" "$BUILD/generated" "$BUILD/res"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/generated" "$BUILD/res"
"$SDK/build-tools/34.0.0/aapt2" compile --dir res -o "$BUILD/res/resources.zip"
"$SDK/build-tools/34.0.0/aapt2" link -o "$BUILD/base.apk" -I "$SDK/platforms/android-36/android.jar" --manifest AndroidManifest.xml --java "$BUILD/generated" -A assets "$BUILD/res/resources.zip"
find src "$BUILD/generated" -name '*.java' -print0 | xargs -0 "$JDK/bin/javac" --release 8 -encoding UTF-8 -cp "$SDK/platforms/android-36/android.jar" -d "$BUILD/classes"
"$JDK/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$JDK/bin/java" -cp "$SDK/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --lib "$SDK/platforms/android-36/android.jar" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unsigned.apk" classes*.dex)
zip -q "$BUILD/unsigned.apk" lib/arm64-v8a/librclone.so
"$SDK/build-tools/34.0.0/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KEYSTORE=${SIGNING_KEYSTORE:-$BUILD/development.keystore}
if [ ! -f "$KEYSTORE" ]; then
 "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias development -dname 'CN=Rclone Next Development' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
mkdir -p dist
OUT="dist/RcloneNext-$VERSION-arm64.apk"
if [ -f "$OUT" ] && [ "${ALLOW_REBUILD:-0}" != 1 ]; then echo "$OUT already exists; set ALLOW_REBUILD=1 to replace this unreleased build" >&2; exit 1; fi
"$JDK/bin/java" -jar "$SDK/build-tools/34.0.0/lib/apksigner.jar" sign --v4-signing-enabled false --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$OUT" "$BUILD/aligned.apk"
"$JDK/bin/java" -jar "$SDK/build-tools/34.0.0/lib/apksigner.jar" verify "$OUT"
"$SDK/build-tools/34.0.0/zipalign" -c -p 4 "$OUT"
shasum -a 256 "$OUT"
