#!/usr/bin/env bash
# Answers the AnswerQualityEval questions with a real model on a PC and prints question + answer
# pairs to read through after changing PromptBuilder.RULES, KeywordRanker or content/.
#  1. ./gradlew :app:testDebugUnitTest --tests '*AnswerQualityEval*'   (writes app/build/eval)
#  2. scripts/eval-answers.sh <models dir> [gguf name]                  (default Gemma 4 E2B)
# Needs a host build from scripts/test-native-host.sh in $OUT. Prompts use the Gemma 4 format.
set -euo pipefail
MODELS="$1"; MODEL="${2:-gemma-4-E2B-it-Q4_0.gguf}"; OUT="${OUT:-/tmp/patnub-native-host}"
EVAL="$(cd "$(dirname "$0")/.." && pwd)/app/build/eval"
cd "$EVAL/prompts"
java -Djava.library.path="$OUT/build" -cp "$OUT/classes" ai.patnub.engine.NativeEngine \
  "$MODELS/$MODEL" - 0 "@$(ls ./*.txt | paste -sd,)" - 220 raw 0.3 2>/dev/null > "$EVAL/answers.txt"
paste -d'\n' "$EVAL/questions.txt" "$EVAL/answers.txt"
