#!/usr/bin/env python3
"""Inject classes.dex + native libs into the aapt2-linked base APK.

Keeps resources.arsc stored (untouched); adds the dex and .so compressed
(extractNativeLibs=true means the loader extracts them at install time).
"""
import shutil
import zipfile

BASE = "build/base2.apk"
OUT = "build/pocketrig2-unsigned.apk"

shutil.copy(BASE, OUT)
z = zipfile.ZipFile(OUT, "a", zipfile.ZIP_DEFLATED)
z.write("build/classes.dex", "classes.dex")
z.write("libs/arm64-v8a/libxmrig.so", "lib/arm64-v8a/libxmrig.so")
z.write("libs/arm64-v8a/libc++_shared.so", "lib/arm64-v8a/libc++_shared.so")
z.close()

names = zipfile.ZipFile(OUT).namelist()
print("entries:", len(names))
for n in names:
    print(" ", n)
