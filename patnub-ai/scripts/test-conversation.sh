#!/usr/bin/env bash
# Runs the Assistant's real prompts through the real models on a PC (no phone).
#  1. ./gradlew :app:testDebugUnitTest --tests '*ConversationTest*' with PROMPT_DIR=<dir> writes the prompts
#  2. this script answers them with MedGemma (Gemma 3 format) and Gemma 4 E2B, in-memory runtime, temp 0.3
# Needs a host build from scripts/test-native-host.sh in $OUT. Args: <models dir> <prompt dir>
set -euo pipefail
MODELS="$1"; PROMPTS="$2"; OUT="${OUT:-/tmp/patnub-native-host}"
run() { java -Djava.library.path="$OUT/build" -cp "$OUT/classes" ai.patnub.engine.NativeEngine "$@"; }
for m in medgemma gemma4; do
  if [ $m = medgemma ]; then M="$MODELS/medgemma-1.5-4b-it.Q4_K_M.gguf"; T=gemma3; else M="$MODELS/gemma-4-E2B-it-Q4_0.gguf"; T=gemma4; fi
  for f in "$PROMPTS"/*."$T".txt; do
    echo "== $m $(basename "$f")"
    run "$M" - 0 "$(cat "$f")" - 220 raw 0.3 2>&1 | grep -E "^ANSWER|^rc=" | tee "${f%.txt}.out"
  done
done
