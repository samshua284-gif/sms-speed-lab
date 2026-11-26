#!/bin/bash
# Tamanina (2G SMS Speed Lab) build — manual aapt2/javac/d8 pipeline (no gradle).
set -e
cd "$(dirname "$0")"
export PATH="$HOME/jdk/bin:$PATH"
SDK="$HOME/android-sdk"; BT="$SDK/build-tools/34.0.0"; AJAR="$SDK/platforms/android-34/android.jar"
PASS="$(cat android/keystore-password.txt)"
rm -rf build; mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir src/res -o build/res.zip
"$BT/aapt2" link -o build/app-unsigned.apk -I "$AJAR" --manifest src/AndroidManifest.xml \
    build/res.zip --java build/gen --auto-add-overlay
javac -encoding UTF-8 -cp "$AJAR" -d build/classes $(find src/java build/gen -name '*.java')
"$BT/d8" --lib "$AJAR" --output build/dex $(find build/classes -name '*.class')
cp build/app-unsigned.apk build/app.apk
(cd build/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f -p 4 build/app.apk build/app-aligned.apk
"$BT/apksigner" sign --ks android/smslab-release.keystore --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --out smslab-1.15.apk build/app-aligned.apk
"$BT/apksigner" verify --print-certs smslab-1.15.apk | head -3
ls -la smslab-1.15.apk
