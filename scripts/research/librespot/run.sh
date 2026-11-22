#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
    echo "usage: bash $0 LIBRESPOT_CHECKOUT NEW_OUTPUT_DIRECTORY" >&2
    exit 2
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
upstream="$(cd -- "$1" && pwd -P)"
revision=d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d
test_file="$upstream/playback/tests/issue_21_pcm.rs"
if [[ $(git -C "$upstream" rev-parse HEAD) != "$revision" ||
      -n $(git -C "$upstream" status --porcelain) || -e "$test_file" ]]; then
    echo "A clean, disposable librespot v0.8.0 checkout at $revision is required" >&2
    exit 2
fi
mkdir -- "$2"
output="$(cd -- "$2" && pwd -P)"
export AUDIPP_SPOTIFY_FIXTURES="$output/fixtures"
mkdir -p -- "$AUDIPP_SPOTIFY_FIXTURES/player"

python3 - "$AUDIPP_SPOTIFY_FIXTURES" <<'PY'
import pathlib
import struct
import sys
import wave

directory = pathlib.Path(sys.argv[1])
for name, rate, channels in [
    ("stereo", 44100, 2),
    ("48k", 48000, 2),
    ("mono", 44100, 1),
]:
    left = (8192, -16384)[:channels]
    with wave.open(str(directory / (name + ".wav")), "wb") as stream:
        stream.setparams((channels, 2, rate, 0, "NONE", "not compressed"))
        for sign in (1, -1):
            frame = struct.pack("<" + "h" * channels, *(sign * x for x in left))
            stream.writeframes(frame * (rate // 2))
PY
cd -- "$AUDIPP_SPOTIFY_FIXTURES"
for name in stereo 48k mono; do
    flac="$name.flac"
    if [[ $name == stereo ]]; then flac=player/stereo.flac; fi
    ffmpeg -nostdin -v error -i "$name.wav" -fflags +bitexact \
        -c:a flac -flags:a +bitexact -metadata title=audipp-fixture "$flac"
done
ffmpeg -nostdin -v error -i stereo.wav -fflags +bitexact \
    -c:a libvorbis -flags:a +bitexact stereo.ogg
sha256sum -- *.wav *.flac *.ogg player/stereo.flac

cd -- "$upstream"
mkdir -p -- playback/tests
(set -o noclobber; cat "$script_dir/issue_21_pcm.rs" > "$test_file")
trap 'rm -- "$test_file"' EXIT
cargo=(cargo +1.91.1)
options=(--locked -p librespot-playback --no-default-features
         --features rustls-tls-webpki-roots,passthrough-decoder)
"${cargo[@]}" --version
CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_RUNNER='unshare -Urn --' \
    "${cargo[@]}" test "${options[@]}" --test issue_21_pcm -- --nocapture

if [[ -n ${AUDIPP_SPOTIFY_NDK:-} ]]; then
    ndk_bin="$AUDIPP_SPOTIFY_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
    export CC_aarch64_linux_android="$ndk_bin/aarch64-linux-android23-clang"
    export CC_x86_64_linux_android="$ndk_bin/x86_64-linux-android23-clang"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_x86_64_linux_android"
    for target in aarch64-linux-android x86_64-linux-android; do
        "${cargo[@]}" test "${options[@]}" --target "$target" --test issue_21_pcm --no-run
        "${cargo[@]}" tree "${options[@]}" --target "$target" -e normal,build \
            --prefix none --format '{p}|{l}' \
            | sed -E 's/ \([^)]*\)\|/|/; s/ \(\*\)$//' \
            | LC_ALL=C sort -u > "$output/$target-licenses.txt"
    done
fi
