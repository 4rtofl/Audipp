#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
: "${ANDROID_HOME:?Android SDK path is required}"
: "${JAVA_HOME:?JDK 17 path is required}"
build_dir="$(mktemp -d)"
usb_src="$build_dir/libusb"
usb_commit=15a7ebb4d426c5ce196684347d2b7cafad862626
ndk="$ANDROID_HOME/ndk/28.2.13676358"
ndk_bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
printf 'Build directory: %s\n' "$build_dir"

git -c advice.detachedHead=false clone --quiet --depth 1 --branch v1.0.29 \
    https://github.com/libusb/libusb.git "$usb_src"
test "$(git -C "$usb_src" rev-parse HEAD)" = "$usb_commit"

(
    cd -- "$usb_src"
    ./autogen.sh --disable-udev --enable-static --disable-shared
    make -j4
) >"$build_dir/host-build.log" 2>&1
cc -Wall -Wextra -Werror -I "$usb_src/libusb" "$probe_dir/usbfs_contract.c" \
    "$usb_src/libusb/.libs/libusb-1.0.a" -pthread -Wl,--wrap=ioctl,--wrap=poll \
    -o "$build_dir/usbfs-contract"
"$build_dir/usbfs-contract"

(
    cd -- "$usb_src/android"
    "$ndk/ndk-build" -j4 'APP_ABI=arm64-v8a armeabi-v7a x86_64' APP_PLATFORM=android-23 \
        APP_BUILD_SCRIPT="$PWD/jni/libusb.mk" USE_PC_NAME=1 \
        NDK_OUT="$build_dir/ndk-obj" NDK_LIBS_OUT="$build_dir/lib"
) >"$build_dir/ndk-build.log" 2>&1
for target in arm64-v8a:aarch64-linux-android armeabi-v7a:armv7a-linux-androideabi x86_64:x86_64-linux-android; do
    abi="${target%:*}"
    "$ndk_bin/${target#*:}23-clang" -Wall -Wextra -Werror -shared -fPIC -Wl,--no-undefined \
        -I "$usb_src/libusb" "$probe_dir/android_fd_probe.c" \
        -L "$build_dir/lib/$abi" -l:libusb-1.0.so -o "$build_dir/lib/$abi/libaudipp_usb_fd_probe.so"
    "$ndk_bin/llvm-readelf" -h -l -d -n --dyn-syms "$build_dir/lib/$abi/"*.so >"$build_dir/$abi.elf.txt"
done

cd -- "$build_dir"
"$JAVA_HOME/bin/javac" --release 8 -cp "$android_jar" -d classes "$probe_dir/UsbFdProbe.java"
"$build_tools/d8" --min-api 23 --lib "$android_jar" --output "$build_dir" \
    classes/com/audipp/research/usbfd/UsbFdProbe.class
"$build_tools/aapt2" link --manifest "$probe_dir/AndroidManifest.xml" -I "$android_jar" -o unsigned.apk
zip -q -0 unsigned.apk classes.dex lib/*/*.so
"$build_tools/zipalign" -P 16 -f 4 unsigned.apk aligned.apk
"$JAVA_HOME/bin/keytool" -genkeypair -keystore probe.jks -storepass android -keypass android \
    -dname 'CN=Audipp research probe' -alias probe -keyalg RSA -keysize 2048 -validity 30
"$build_tools/apksigner" sign --ks probe.jks --ks-pass pass:android --out probe.apk aligned.apk
"$build_tools/apksigner" verify --min-sdk-version 23 probe.apk
printf 'Local probe APK: %s/probe.apk\n' "$build_dir"
