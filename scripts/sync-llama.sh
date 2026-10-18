#!/usr/bin/env bash
# Fetches the pinned llama.cpp revision into app/llama-cpp so the native engine
# can be compiled in.
#
# The repository does NOT vendor llama.cpp: a fresh clone builds without it and
# ships a real (if inert) engine bridge. This script is the opt-in step.
#
#   ./scripts/sync-llama.sh              # pinned tag from llama.cpp.pin
#   ./scripts/sync-llama.sh --tag b4000  # a different revision
#   ./scripts/sync-llama.sh --update     # pull the current tag again
#   ./scripts/sync-llama.sh --engine     # sync, then assemble a debug APK with
#                                        # the engine compiled in
#
# app/llama-cpp is git-ignored, so nothing here changes what you commit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIN="$ROOT/llama.cpp.pin"
DEST="$ROOT/app/llama-cpp"

TAG=""
REFRESH=0
BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)     TAG="${2:-}"; shift 2 ;;
    --update)  REFRESH=1; shift ;;
    --engine)  BUILD=1; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

if [[ -z "$TAG" ]]; then
  TAG="$(grep -E '^LLAMA_CPP_TAG=' "$PIN" | cut -d= -f2)"
fi
REPO="$(grep -E '^LLAMA_CPP_REPO=' "$PIN" | cut -d= -f2)"
EXPECT_HEADER="$(grep -E '^LLAMA_CPP_HEADER=' "$PIN" | cut -d= -f2)"

if [[ -z "$TAG" || -z "$REPO" ]]; then
  echo "error: llama.cpp.pin must define LLAMA_CPP_REPO and LLAMA_CPP_TAG" >&2
  exit 1
fi

echo "==> llama.cpp $TAG"
echo "    repo: $REPO"
echo "    dest: ${DEST#"$ROOT"/}"

if [[ -d "$DEST/.git" ]]; then
  if [[ "$REFRESH" == 1 ]]; then
    git -C "$DEST" fetch --tags --depth 1 origin "$TAG"
    git -C "$DEST" checkout -q "tags/$TAG"
  else
    echo "    already present; pass --update to re-fetch"
  fi
else
  rm -rf "$DEST"
  git clone --depth 1 --branch "$TAG" "$REPO" "$DEST"
fi

# The build fails loudly if this is missing, but failing here is friendlier.
if [[ ! -f "$DEST/$EXPECT_HEADER" ]]; then
  echo "error: $EXPECT_HEADER not found under ${DEST#"$ROOT"/}" >&2
  echo "       the pinned revision looks wrong, or the clone was partial" >&2
  exit 1
fi

HEAD_SHA="$(git -C "$DEST" rev-parse --short HEAD)"
echo "==> checked out $HEAD_SHA"

if [[ "$BUILD" == 1 ]]; then
  echo "==> assembling a debug APK with the engine compiled in"
  "$ROOT/gradlew" -p "$ROOT" :app:assembleDebug \
    -Pofflinegguf.llama=true \
    -Pofflinegguf.llamaDir="$DEST"
  echo "==> app/build/outputs/apk/debug/"
else
  cat <<NEXT

Next: build an APK with the engine enabled.

  ./gradlew :app:assembleRelease -Pofflinegguf.llama=true

Add -Pofflinegguf.abis=arm64-v8a to build one ABI instead of three, which is
several times faster. If llama.cpp's own CMake options conflict with yours,
the bridge turns the noisy ones off already (see app/cpp/CMakeLists.txt).

Without this step the app still builds and runs: it parses GGUF headers,
estimates fit and recommends models, and reports that generation needs an
engine build.
NEXT
fi
