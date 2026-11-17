#!/usr/bin/env bash
# Builds the JNI bridge for Linux and runs real models through it (no phone needed).
# Needs: g++, cmake, ninja, a JDK. Or run inside a container, e.g.
#   podman run --rm -v <repo>:/repo -v <llama>:/llama -v <models>:/models docker.io/library/gcc:14 \
#     bash -c 'apt-get update -qq && apt-get install -y -qq cmake ninja-build default-jdk-headless >/dev/null && \
#              /repo/patnub-ai/scripts/test-native-host.sh /llama /models'
# Args: <patched llama.cpp dir> <models dir>
set -euo pipefail
LLAMA="$1"; MODELS="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-/tmp/patnub-native-host}"
mkdir -p "$OUT"
cmake -S "$HERE/../app/src/main/cpp" -B "$OUT/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR="$LLAMA" >/dev/null
cmake --build "$OUT/build" --target patnub_llm -j"$(nproc)" >/dev/null
javac -d "$OUT/classes" "$HERE/native-host-test/ai/patnub/engine/NativeEngine.java"
run() { java -Djava.library.path="$OUT/build" -cp "$OUT/classes" ai.patnub.engine.NativeEngine "$@"; }

Q="I have had a headache for two days and feel dizzy when I stand up. What could cause this? Answer in two sentences."
echo "== Gemma 4 E2B, in memory, text"
run "$MODELS/gemma-4-E2B-it-Q4_0.gguf" "$MODELS/mmproj-gemma-4-E2B-it-Q8_0.gguf" 0 "$Q" - 80
echo "== Gemma 4 E2B, in memory, photo"
run "$MODELS/gemma-4-E2B-it-Q4_0.gguf" "$MODELS/mmproj-gemma-4-E2B-it-Q8_0.gguf" 0 "Read the medicine name, strength and form on this package." "$MODELS/test-package.png" 60
echo "== MedGemma 1.5 4B, in memory, photo"
run "$MODELS/medgemma-1.5-4b-it.Q4_K_M.gguf" "$MODELS/medgemma-1.5-4b-it.mmproj-Q8_0.gguf" 0 "Read the medicine name, strength and form on this package." "$MODELS/test-package.png" 60
echo "== MedGemma 1.5 4B, streamed through msr_engine (1 GiB arena), text"
run "$MODELS/medgemma-1.5-4b-it.Q4_K_M.gguf" - 1 "$Q" - 80
