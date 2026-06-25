#!/bin/bash
# Build static FFmpeg + FFprobe for Android arm64-v8a (16KB page compatible)
set -euo pipefail

FFMPEG_VERSION="7.0.2"
OUT_DIR="$PWD/build/ffmpeg-out"
mkdir -p "$OUT_DIR"

# Install NDK if not present
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    NDK_VER="r27c"
    NDK_ZIP="android-ndk-${NDK_VER}-linux.zip"
    if [ ! -d "android-ndk-${NDK_VER}" ]; then
        echo "Downloading NDK ${NDK_VER}..."
        wget -q "https://dl.google.com/android/repository/${NDK_ZIP}"
        unzip -q "$NDK_ZIP"
    fi
    export ANDROID_NDK_HOME="$PWD/android-ndk-${NDK_VER}"
fi

echo "NDK: $ANDROID_NDK_HOME"

HOST_TAG="linux-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
API=24
TARGET="aarch64-linux-android"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"
PREFIX="$OUT_DIR"

# Download FFmpeg
if [ ! -d "ffmpeg-${FFMPEG_VERSION}" ]; then
    echo "Downloading FFmpeg ${FFMPEG_VERSION}..."
    wget -q "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
    tar xf "ffmpeg-${FFMPEG_VERSION}.tar.xz"
fi

cd "ffmpeg-${FFMPEG_VERSION}"

echo "Configuring FFmpeg..."
./configure \
    --prefix="$PREFIX" \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cross-prefix="$TOOLCHAIN/bin/${TARGET}-" \
    --sysroot="$SYSROOT" \
    --cc="$CC" \
    --cxx="$CXX" \
    --ar="$AR" \
    --ranlib="$RANLIB" \
    --strip="$STRIP" \
    --enable-static \
    --disable-shared \
    --disable-everything \
    --enable-encoder=aac \
    --enable-decoder=aac,mp3,mp3adu,mp3on4,mp1,mp2 \
    --enable-parser=aac,mpegaudio \
    --enable-demuxer=aac,mp3,mov,m4v \
    --enable-muxer=adts,mp4 \
    --enable-filter=loudnorm,dynaudnorm,aformat,volume,ametadata \
    --enable-protocol=file,pipe \
    --enable-ffmpeg \
    --enable-ffprobe \
    --disable-ffplay \
    --disable-avdevice \
    --disable-postproc \
    --disable-network \
    --disable-doc \
    --disable-debug \
    --enable-small \
    --enable-optimizations \
    --extra-cflags="-O3 -fPIC -DANDROID -D__ANDROID_API__=$API" \
    --extra-ldflags="-Wl,-z,max-page-size=16384 -fPIE -pie -static-libgcc" \
    2>&1 | tail -20

echo "Building FFmpeg (make -j$(nproc))..."
make -j$(nproc) 2>&1 | tail -5

echo "Installing..."
make install 2>&1 | tail -5

cd ..

# Verify binaries
echo ""
echo "=== Built binaries ==="
ls -lh "$PREFIX/bin/"
echo ""
echo "=== Binary info ==="
file "$PREFIX/bin/ffmpeg"
file "$PREFIX/bin/ffprobe"

# Test that ffprobe can run (it will fail on no input, but should start)
echo ""
echo "=== Runtime test ==="
"$PREFIX/bin/ffprobe" -version 2>&1 || echo "WARNING: binary may not run on build host (expected for cross-compiled binary)"
