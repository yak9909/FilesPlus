#!/bin/bash
# ART verification of a patch bundle on the local Android 12 emulator (AVD "fp31", x86_64 + arm64 translation).
# Catches VerifyError crashes (e.g. bad branch targets in injected smali) that patch application alone does not.
#
# usage: bash scripts/emu-verify.sh [<absolute path to .mpp>]   (default: newest patches/build/libs/*.mpp)
# Needs: emulator running (emulator -avd fp31 -no-snapshot -no-audio -gpu swiftshader_indirect),
#        build/emu/morphe-desktop-*-all.jar (gh release download -R MorpheApp/morphe-cli -p '*.jar').
# Prints the number of rejected methods (must be 0) and lists them.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EMU="$ROOT/build/emu"
MPP="$(cygpath -w "${1:-$(ls -t "$ROOT"/patches/build/libs/*.mpp | head -1)}")"
J="/c/Program Files/Java/jdk-21.0.3/bin"; export JAVA_HOME='C:\Program Files\Java\jdk-21.0.3'
SDK="$LOCALAPPDATA/Android/Sdk"
export MSYS_NO_PATHCONV=1
ADB="$SDK/platform-tools/adb.exe -s emulator-5554"
P=com.google.android.apps.nbu.files.mod
mkdir -p "$EMU"; cd "$EMU"
CLI=$(ls morphe-desktop-*-all.jar | tail -1)
[ -f files.apkm ] || cp "$ROOT"/original/*.apkm files.apkm
[ -f emu.keystore ] || "$J/keytool" -genkeypair -keystore emu.keystore -storepass android -keypass android \
    -alias emu -keyalg RSA -validity 10000 -dname "CN=emu" >/dev/null 2>&1
rm -rf tmp
# The emulator has no 32-bit ARM translation: drop the app's armeabi-v7a libs (only small helpers).
"$J/java" -jar "$CLI" patch -p "$MPP" --striplibs=arm64-v8a --unsigned -o emu-unsigned.apk -t tmp files.apkm > emu-patch.log 2>&1
echo "Applied: $(grep -c 'INFO: Applied' emu-patch.log)"
BT=$(ls -d "$SDK/build-tools/"* | tail -1)
"$BT/apksigner.bat" sign --ks emu.keystore --ks-pass pass:android --out emu.apk emu-unsigned.apk
timeout 180 $ADB install -r emu.apk | tail -1
timeout 10 $ADB logcat -c
timeout 600 $ADB shell cmd package compile -m verify -f $P
sleep 2
LOG=$(timeout 30 $ADB logcat -d | grep -iE 'failed to verify|rejected class' || true)
echo "Rejected methods: $(printf '%s' "$LOG" | grep -c . || true)"
printf '%s\n' "$LOG" | cut -c1-300
