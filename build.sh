#!/usr/bin/env bash
# HeoMusic Android APK 构建脚本（零 Gradle / 零 Maven 依赖）
set -e
ROOT=/z/HeoMusic/apk
JDK=$ROOT/_toolchain/jdk/jdk-17/bin
BT=$ROOT/_toolchain/sdk/build-tools/android-15
JAR=$ROOT/_toolchain/sdk/platforms/android-35/android.jar
SRC=$ROOT/app/src/main
OUT=$ROOT/app/build
APK=$ROOT/HeoMusic.apk
KS=$ROOT/keystore/debug.keystore

w() { cygpath -w "$1"; }

echo "[1/8] clean"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex" "$ROOT/keystore"

echo "[2/8] aapt2 compile resources"
"$BT/aapt2.exe" compile --dir "$(w "$SRC/res")" -o "$(w "$OUT/res.zip")"

echo "[3/8] aapt2 link"
"$BT/aapt2.exe" link \
    -o "$(w "$OUT/base.apk")" \
    -I "$(w "$JAR")" \
    --manifest "$(w "$SRC/AndroidManifest.xml")" \
    -R "$(w "$OUT/res.zip")" \
    --java "$(w "$OUT/gen")" \
    -A "$(w "$SRC/assets")" \
    --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 2 --version-name 1.1.0

echo "[4/8] javac"
(cd "$SRC/java" && "$JDK/javac.exe" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$(w "$JAR")" \
    -d "$(w "$OUT/classes")" \
    $(find . -name '*.java') "$(w "$OUT/gen/com/heomusic/player/R.java")")

echo "[5/8] d8 -> classes.dex"
(cd "$OUT/classes" && "$JDK/java.exe" -cp "$(w "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(w "$JAR")" --min-api 26 --output "$(w "$OUT/dex")" \
    $(find . -name '*.class'))

echo "[6/8] pack dex into apk"
python "$(w "$ROOT/tools/pack.py")" "$(w "$OUT/base.apk")" \
    "$(w "$OUT/dex/classes.dex")" "$(w "$OUT/unsigned.apk")"

echo "[7/8] zipalign + sign"
if [ ! -f "$KS" ]; then
    "$JDK/keytool.exe" -genkeypair -v -keystore "$(w "$KS")" \
        -alias heomusic -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -storetype PKCS12 \
        -dname "CN=HeoMusic, OU=Dev, O=HeoMusic, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
"$BT/zipalign.exe" -f 4 "$(w "$OUT/unsigned.apk")" "$(w "$OUT/aligned.apk")"
"$JDK/java.exe" -jar "$(w "$BT/lib/apksigner.jar")" sign \
    --ks "$(w "$KS")" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled false --v2-signing-enabled true \
    --out "$(w "$APK")" "$(w "$OUT/aligned.apk")"

echo "[8/8] verify"
"$JDK/java.exe" -jar "$(w "$BT/lib/apksigner.jar")" verify --verbose "$(w "$APK")" | head -12
ls -lh "$APK"
