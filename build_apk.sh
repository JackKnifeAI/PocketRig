#!/bin/bash
# PocketRig — reproducible on-device APK build.
# Builds the bare-metal xmrig payload (once), compiles the app, packages & signs.
# Run from /root/pocketminer.  Everything runs inside the Ubuntu proot except the
# xmrig cross-compile, which uses Termux's Android-targeting clang.
set -e

ROOT=/root/pocketminer
APP=$ROOT/app
SC=/tmp/claude-0/-root/d5aab43a-c189-4068-a8f0-286b74c4ed31/scratchpad
TERMUX=/data/data/com.termux/files/usr/bin
ANDROID_JAR=$SC/android.jar
UBER=$SC/uber-apk-signer.jar

echo "==> [1/6] aapt: resources + manifest + assets -> base.apk + R.java"
cd $APP
rm -rf build gen && mkdir -p build gen build/classes
/usr/bin/aapt package -f -m -J gen -M AndroidManifest.xml -S res -A assets \
  -I $ANDROID_JAR -F build/base.apk --min-sdk-version 26 --target-sdk-version 33

echo "==> [2/6] javac: compile app (Java 8 bytecode, no invokedynamic for dx)"
$TERMUX/javac -source 8 -target 8 -bootclasspath $ANDROID_JAR -d build/classes \
  gen/com/pocketrig/miner/R.java src/com/pocketrig/miner/*.java

echo "==> [3/6] dx: dex the classes"
$TERMUX/dx --dex --output=build/classes.dex build/classes

echo "==> [4/6] assemble: add dex + native libs"
cd $APP/build
cp base.apk pocketrig-unsigned.apk
/usr/bin/aapt add pocketrig-unsigned.apk classes.dex
mkdir -p lib/arm64-v8a
cp $APP/libs/arm64-v8a/libxmrig.so lib/arm64-v8a/
cp $APP/libs/arm64-v8a/libc++_shared.so lib/arm64-v8a/
/usr/bin/aapt add pocketrig-unsigned.apk lib/arm64-v8a/libxmrig.so lib/arm64-v8a/libc++_shared.so

echo "==> [5/6] zipalign"
/usr/bin/zipalign -f 4 pocketrig-unsigned.apk pocketrig-aligned.apk

echo "==> [6/6] sign (v1+v2+v3)"
if [ -f "$ROOT/keystore.properties" ]; then
  . "$ROOT/keystore.properties"
  echo "    signing with release keystore ($KS_ALIAS)"
  $TERMUX/java -jar $UBER --apks pocketrig-aligned.apk --skipZipAlign \
    --ks "$ROOT/$KS_PATH" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS"
else
  echo "    WARNING: no keystore.properties found — falling back to a debug key (NOT for release)"
  $TERMUX/java -jar $UBER --apks pocketrig-aligned.apk --skipZipAlign
fi

SIGNED=$(ls -t pocketrig-aligned*[sS]igned.apk 2>/dev/null | head -1)
[ -n "$SIGNED" ] || { echo "sign step produced no signed APK"; exit 1; }
cp "$SIGNED" $ROOT/PocketRig.apk
cp "$SIGNED" /data/data/com.termux/files/home/PocketRig.apk
echo
echo "DONE -> $ROOT/PocketRig.apk  and  ~/PocketRig.apk"
/usr/bin/aapt dump badging $ROOT/PocketRig.apk 2>/dev/null | grep -E "package:|native-code"

# --- To rebuild the bare-metal xmrig payload from scratch (rarely needed) ---
# T=$TERMUX
# cd $SC/libuv && rm -rf b && mkdir b && cd b && \
#   cmake .. -DCMAKE_C_COMPILER=$T/clang -DCMAKE_AR=$T/llvm-ar -DCMAKE_RANLIB=$T/llvm-ranlib \
#     -DLIBUV_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release && make -j4 uv_a
# cd $SC/xmrig && rm -rf build-android && mkdir build-android && cd build-android && \
#   cmake .. -DCMAKE_C_COMPILER=$T/clang -DCMAKE_CXX_COMPILER=$T/clang++ \
#     -DCMAKE_AR=$T/llvm-ar -DCMAKE_RANLIB=$T/llvm-ranlib -DCMAKE_STRIP=$T/llvm-strip \
#     -DWITH_TLS=OFF -DWITH_HWLOC=OFF -DWITH_OPENCL=OFF -DWITH_CUDA=OFF -DWITH_MSR=OFF \
#     -DUV_INCLUDE_DIR=$SC/libuv/include -DUV_LIBRARY=$SC/libuv/b/libuv.a -DCMAKE_BUILD_TYPE=Release && \
#   make -j4 && cp xmrig-notls $APP/libs/arm64-v8a/libxmrig.so
# cp $T/../lib/libc++_shared.so $APP/libs/arm64-v8a/
