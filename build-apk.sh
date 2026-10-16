#!/bin/bash
# XTracker 手工编译打包脚本（绕开 Gradle：本机沙箱拦截 localhost TCP，Gradle daemon 无法工作）
set -e
export JAVA_HOME=~/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
SDK=~/android-sdk
BT=$SDK/build-tools/34.0.0
PROJ=~/workspace/xtracker
OUT=$PROJ/out
ANDROID_JAR=$SDK/platforms/android-34/android.jar
KOTLINC=~/kotlin/kotlinc/bin/kotlinc
STDLIB=~/kotlin/kotlinc/lib/kotlin-stdlib.jar
CP="$PROJ/libs/kotlinx-coroutines-core-jvm-1.8.1.jar:$PROJ/libs/kotlinx-coroutines-android-1.8.1.jar"

# 图标 PNG 与签名 keystore 以 .b64 文本存仓库（推送通道不支持二进制），构建时还原
for b64 in "$PROJ"/app/src/main/res/drawable-nodpi/ic_launcher_art.png.b64 \
           "$PROJ"/app/src/main/res/mipmap-*/ic_launcher*.png.b64 \
           "$PROJ"/debug.keystore.b64; do
  [ -f "$b64" ] && base64 -d "$b64" > "${b64%.b64}"
done

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "== [1/6] aapt2 compile res"
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/res-flat.zip"

echo "== [2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  "$OUT/res-flat.zip"

echo "== [3/6] kotlinc"
# shellcheck disable=SC2046
"$KOTLINC" -jvm-target 17 -nowarn \
  -cp "$CP:$ANDROID_JAR" \
  -d "$OUT/classes" \
  $(find "$PROJ/app/src/main/java" -name "*.kt") \
  "$OUT/gen/xyz/moshushi/app/R.java"

echo "== [4/6] d8 dex"
# shellcheck disable=SC2046
"$BT/d8" --release --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") \
  "$PROJ/libs/kotlinx-coroutines-core-jvm-1.8.1.jar" \
  "$PROJ/libs/kotlinx-coroutines-android-1.8.1.jar" \
  "$STDLIB"

echo "== [5/6] package apk"
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/app-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== [6/6] sign"
"$BT/apksigner" sign --ks "$PROJ/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/xtracker-v1.4.2.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/xtracker-v1.4.2.apk" | head -3
ls -la "$OUT/xtracker-v1.4.2.apk"
echo BUILD_OK
