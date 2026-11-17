#!/usr/bin/env bash
# Fetches llama.cpp at msr_engine's pinned commit and applies the msr patch.
# Usage: scripts/prepare-native.sh [target_dir]   (default: msr_engine/third_party/llama.cpp)
# Pass a target outside the repo when the repo's disk is short on space, then build with
#   ./gradlew -PllamaDir=<target_dir> :app:assembleDebug
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MSR="$ROOT/msr_engine"
TARGET="${1:-$MSR/third_party/llama.cpp}"
COMMIT="$(git -C "$MSR" ls-tree HEAD third_party/llama.cpp | awk '{print $3}')"
PATCH="$MSR/patches/0001-msr-external-buffer-hook.patch"

mkdir -p "$TARGET"
cd "$TARGET"
[ -d .git ] || git init -q
git remote get-url origin >/dev/null 2>&1 || git remote add origin https://github.com/ggml-org/llama.cpp.git
git fetch -q --depth 1 origin "$COMMIT"
git checkout -q -f FETCH_HEAD
if git apply --reverse --check "$PATCH" 2>/dev/null; then
  echo "patch already applied"
else
  git apply "$PATCH"
fi
echo "llama.cpp ready at $TARGET ($COMMIT)"
