#!/usr/bin/env bash
# Runs every automated check: contract (Node), clinic app (Node + type-check/build), Android unit tests.
# Extra Gradle arguments can be passed through, e.g. scripts/verify.sh -I ~/.cache/patnub-build/tmpbuild.gradle
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== contract =="
(cd contract && node --test)

echo "== doctor-web =="
(cd doctor-web && npm test && npm run build)

echo "== patnub-ai =="
(cd patnub-ai && ./gradlew "$@" :app:testDebugUnitTest)

echo "All checks passed."
