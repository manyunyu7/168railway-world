#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?Path to Android NDK}"
host_dir=$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -1)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$host_dir/bin/aarch64-linux-android24-clang"
cargo build --locked --release --target aarch64-linux-android
mkdir -p build/jniLibs/arm64-v8a build/assets/world-native
cp target/aarch64-linux-android/release/libworld_native.so build/jniLibs/arm64-v8a/
cp ../T-007-real-nyc.save build/assets/world-native/nyc.save
cp ../../LICENSE ../../NOTICE.md build/assets/world-native/
