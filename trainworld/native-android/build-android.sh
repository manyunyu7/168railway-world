#!/bin/sh
set -eu
cd "$(dirname "$0")"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Android NDK directory}"
case "$(uname -s)" in
    Darwin) host=darwin ;;
    Linux) host=linux ;;
    *) echo "Run this script with the Android NDK on Linux or macOS." >&2; exit 2 ;;
esac
for host_dir in "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/"$host"-*; do
    [ ! -x "$host_dir/bin/llvm-readelf" ] || break
done
readelf="$host_dir/bin/llvm-readelf"
[ -x "$readelf" ] || { echo "Missing NDK LLVM toolchain for $host." >&2; exit 1; }

if [ "$#" -eq 0 ]; then set -- armeabi-v7a arm64-v8a x86_64; fi
target_dir=${CARGO_TARGET_DIR:-target}
mkdir -p build/jniLibs build/assets/world-native
stage=$(mktemp -d build/.jni-XXXXXXXX)
trap 'rm -rf "$stage"' 0
trap 'exit 1' HUP INT TERM

for abi in "$@"; do
    case "$abi" in
        armeabi-v7a) target=armv7-linux-androideabi; clang_target=armv7a-linux-androideabi; headers=arm-linux-androideabi; machine=ARM ;;
        arm64-v8a) target=aarch64-linux-android; clang_target=$target; headers=$target; machine=AArch64 ;;
        x86_64) target=x86_64-linux-android; clang_target=$target; headers=$target; machine="Advanced Micro Devices X86-64" ;;
        *) echo "Unsupported Android ABI: $abi" >&2; exit 2 ;;
    esac
    var=$(echo "$target" | tr '-' '_')
    upper=$(echo "$var" | tr '[:lower:]' '[:upper:]')
    clang="$host_dir/bin/${clang_target}24-clang"
    args="--target=${clang_target}24 --sysroot=\"$host_dir/sysroot\" -isystem \"$host_dir/sysroot/usr/include/$headers\""
    env "CC_$var=$clang" "AR_$var=$host_dir/bin/llvm-ar" \
        "CARGO_TARGET_${upper}_LINKER=$clang" "BINDGEN_EXTRA_CLANG_ARGS_$var=$args" \
        cargo rustc --locked --release --target "$target" --target-dir "$target_dir" --lib -- \
        -C link-arg=-Wl,-soname,libworld_native.so -C link-arg=-Wl,-z,max-page-size=16384

    lib="$target_dir/$target/release/libworld_native.so"
    actual=$("$readelf" -h "$lib" | awk -F: '/Machine:/ {sub(/^[[:space:]]+/,"",$2); print $2}')
    [ "$actual" = "$machine" ] || { echo "$abi has unexpected ELF machine $actual" >&2; exit 1; }
    "$readelf" -d "$lib" | grep -F '(SONAME)' | grep -Fq '[libworld_native.so]' || {
        echo "$abi is missing SONAME libworld_native.so" >&2; exit 1;
    }
    alignments=$("$readelf" -lW "$lib" | awk '$1=="LOAD" { print $NF }')
    [ -n "$alignments" ] || { echo "No ELF LOAD segment for $abi" >&2; exit 1; }
    for alignment in $alignments; do
        [ "$((alignment))" -ge 16384 ] || { echo "$abi has a LOAD alignment below 16 KiB" >&2; exit 1; }
    done
    mkdir -p "$stage/$abi"
    cp "$lib" "$stage/$abi/libworld_native.so"
    echo "$abi: $machine, libworld_native.so, LOAD >= 16 KiB"
done

for staged_dir in "$stage"/*; do
    abi=$(basename "$staged_dir")
    mkdir -p "build/jniLibs/$abi"
    mv "$staged_dir/libworld_native.so" "build/jniLibs/$abi/libworld_native.so"
done
mkdir -p build/assets/world-native/snapshots
cp ../app/src/game/zone-names.json ../app/src/game/zone-aliases.json build/assets/world-native/
cp ../app/public/snapshots/*.jpg build/assets/world-native/snapshots/
cp ../T-007-real-nyc.save build/assets/world-native/nyc.save
cp ../../LICENSE ../../NOTICE.md build/assets/world-native/
