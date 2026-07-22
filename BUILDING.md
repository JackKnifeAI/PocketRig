# Building PocketRig

PocketRig was bootstrapped entirely on the phone (Termux + a Ubuntu PRoot) with no
Gradle and no Android Studio, but the toolchain is ordinary and reproducible anywhere.

There are two artifacts:

1. **`libxmrig.so`** — the native RandomX miner, cross-compiled from
   [xmrig](https://github.com/xmrig/xmrig) source (GPLv3).
2. **The APK** — resources + a WebView UI + a thin Java service layer, packaged around
   that `.so`.

## 1. Build `libxmrig.so` from source

The shipped `app/libs/arm64-v8a/libxmrig.so` is xmrig built for bionic/arm64 with TLS,
hwloc, OpenCL, CUDA and MSR disabled (it talks to pools directly and is governed by the
app, so it needs none of them). `libuv` is linked statically.

Using an Android-targeting clang (Termux's `clang` targets `aarch64-linux-android`; an
NDK `aarch64-linux-android<api>-clang` works identically):

```bash
# libuv (static)
cd libuv && mkdir -p b && cd b
cmake .. -DCMAKE_C_COMPILER=clang -DCMAKE_AR=llvm-ar -DCMAKE_RANLIB=llvm-ranlib \
  -DLIBUV_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release
make -j"$(nproc)" uv_a

# xmrig (RandomX only, no TLS/hwloc/OpenCL/CUDA/MSR)
cd ../../xmrig && mkdir -p build-android && cd build-android
cmake .. -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
  -DCMAKE_AR=llvm-ar -DCMAKE_RANLIB=llvm-ranlib -DCMAKE_STRIP=llvm-strip \
  -DWITH_TLS=OFF -DWITH_HWLOC=OFF -DWITH_OPENCL=OFF -DWITH_CUDA=OFF -DWITH_MSR=OFF \
  -DUV_INCLUDE_DIR=../../libuv/include -DUV_LIBRARY=../../libuv/b/libuv.a \
  -DCMAKE_BUILD_TYPE=Release
make -j"$(nproc)"

# install into the app tree
cp xmrig            ../../pocketminer/app/libs/arm64-v8a/libxmrig.so
cp "$(clang -print-file-name=libc++_shared.so)" ../../pocketminer/app/libs/arm64-v8a/
```

Naming it `libxmrig.so` (rather than shipping a bare `xmrig` binary) lets Android's
package installer extract it into the app's native lib dir with execute permission.

## 2. Build the APK

`build_apk.sh` runs the standard `aapt → javac → dx → aapt add → zipalign → sign`
pipeline. Java is compiled at source/target 8 (no `invokedynamic`) so `dx` can dex it —
that's why the code uses anonymous classes instead of lambdas.

Requirements: `aapt`, `zipalign`, `javac`, `dx` (or `d8`), an `android.jar`, and an APK
signer (this repo used `uber-apk-signer`).

```bash
bash build_apk.sh
```

## 3. Signing (release)

**Do not ship the debug key.** Generate a dedicated release keystore once, keep it out of
the repo (`.gitignore` already excludes `*.keystore`), and sign every release with it —
an app's signing identity can never change after its first public release without
breaking updates.

```bash
keytool -genkeypair -v -keystore release.keystore -alias pocketrig \
  -keyalg RSA -keysize 4096 -validity 10000
# then sign the aligned APK with that key (apksigner / uber-apk-signer)
```

## F-Droid main repo (future)

The main F-Droid repo builds everything from source on its own server and does **not**
accept the prebuilt `libxmrig.so`. Shipping there requires a build recipe (metadata YAML)
that compiles xmrig from source as part of the F-Droid build. The from-source steps in
section 1 are the basis for that recipe. Until then, the pragmatic channel is
**IzzyOnDroid**, which accepts the prebuilt FOSS APK from GitHub Releases; the
`fastlane/metadata/` tree in this repo is the store listing it reads.
