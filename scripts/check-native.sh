#!/usr/bin/env bash
# Native checks that run WITHOUT the Android NDK and WITHOUT llama.cpp.
#
# What this proves, on a plain Linux/macOS host with a C++17 compiler:
#   1. the GGUF header parser builds and passes its golden fixtures
#   2. the UTF-8 framer keeps its contract (same table as the JVM unit test)
#   3. the "no engine" bridge compiles — the default build path is real code
#   4. the JNI layer type-checks against a real jni.h (skipped when no JDK)
#
# CI runs this in its own job so a native regression fails fast and separately
# from the Gradle build.
#
#   ./scripts/check-native.sh            # all checks
#   ./scripts/check-native.sh --quiet    # only failures
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/cpp"
OUT="${OUT_DIR:-$ROOT/build/native-check}"
QUIET=0
[[ "${1:-}" == "--quiet" ]] && QUIET=1

mkdir -p "$OUT"
FAILED=0
PASSED=0
SKIPPED=0

say() { [[ "$QUIET" == 1 ]] || echo "$@"; }

step() {
    local name="$1"; shift
    say "==> $name"
    if "$@" >"$OUT/$name.log" 2>&1; then
        say "    ok"
        PASSED=$((PASSED + 1))
    else
        echo "    FAILED — see $OUT/$name.log"
        tail -n 40 "$OUT/$name.log" | sed 's/^/      /'
        FAILED=$((FAILED + 1))
    fi
}

skip() {
    say "==> $1"
    say "    skipped: $2"
    SKIPPED=$((SKIPPED + 1))
}

# ------------------------------------------------------------------ compiler
CXX_BIN="${CXX:-}"
if [[ -z "$CXX_BIN" ]]; then
    for candidate in g++ clang++ c++; do
        if command -v "$candidate" >/dev/null 2>&1; then CXX_BIN="$candidate"; break; fi
    done
fi
if [[ -z "$CXX_BIN" ]]; then
    echo "error: no C++ compiler found (tried \$CXX, g++, clang++, c++)" >&2
    exit 1
fi
say "compiler: $CXX_BIN ($($CXX_BIN --version | head -n 1))"

CXXFLAGS=(-std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter -I"$CPP")

# ------------------------------------------------------- 1. GGUF golden test
step gguf-probe-test \
    "$CXX_BIN" "${CXXFLAGS[@]}" "$CPP/tests/gguf_probe_test.cpp" "$CPP/gguf/gguf_probe.cpp" \
    -o "$OUT/gguf_probe_test"
if [[ -x "$OUT/gguf_probe_test" ]]; then
    step gguf-probe-run "$OUT/gguf_probe_test"
fi

# -------------------------------------------------------- 2. UTF-8 framer
step utf8-test "$CXX_BIN" "${CXXFLAGS[@]}" "$CPP/tests/utf8_test.cpp" -o "$OUT/utf8_test"
if [[ -x "$OUT/utf8_test" ]]; then
    step utf8-run "$OUT/utf8_test"
fi

# --------------------------------------------- 3. the "engine absent" bridge
step bridge-none \
    "$CXX_BIN" "${CXXFLAGS[@]}" -DOFFLINEGGUF_HAVE_LLAMA=0 -c \
    "$CPP/llama/llama_bridge_none.cpp" -o "$OUT/llama_bridge_none.o"

# --------------------------------------------------------- 4. JNI type check
# android/log.h does not exist off-device; a 12-line stub is enough to prove the
# JNI signatures, the callback contract and the session bookkeeping still compile.
STUB="$OUT/host-stub/android"
mkdir -p "$STUB"
cat >"$STUB/log.h" <<'STUB_HEADER'
#pragma once
// Minimal host stand-in for <android/log.h>, used only by scripts/check-native.sh.
#ifdef __cplusplus
extern "C" {
#endif
typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
} android_LogPriority;
int __android_log_print(int prio, const char *tag, const char *fmt, ...);
int __android_log_write(int prio, const char *tag, const char *text);
#ifdef __cplusplus
}
#endif
STUB_HEADER

JNI_INCLUDES=()
if [[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/include/jni.h" ]]; then
    JNI_INCLUDES+=(-I"$JAVA_HOME/include")
    for platform in linux darwin win32; do
        [[ -d "$JAVA_HOME/include/$platform" ]] && JNI_INCLUDES+=(-I"$JAVA_HOME/include/$platform")
    done
fi
if [[ "${#JNI_INCLUDES[@]}" -eq 0 ]]; then
    # GitHub runners and most dev boxes have a JDK somewhere; try to find one.
    for guess in /usr/lib/jvm/*/include "$HOME/.gradle/jdks"/*/include; do
        if [[ -f "$guess/jni.h" ]]; then
            JNI_INCLUDES+=(-I"$guess")
            [[ -d "$(dirname "$guess")/include/linux" ]] && JNI_INCLUDES+=(-I"$(dirname "$guess")/include/linux")
            break
        fi
    done
fi

if [[ "${#JNI_INCLUDES[@]}" -eq 0 ]]; then
    skip jni-syntax-check "no jni.h (set JAVA_HOME to a JDK to enable it)"
else
    step jni-syntax-check \
        "$CXX_BIN" "${CXXFLAGS[@]}" "${JNI_INCLUDES[@]}" -I"$OUT/host-stub" \
        -DOFFLINEGGUF_HAVE_LLAMA=0 -fsyntax-only "$CPP/llama_jni.cpp"
fi

# ------------------------------------------- 5. optional: the real bridge
LLAMA_DIR="${OFFLINEGGUF_LLAMA_DIR:-$ROOT/app/llama-cpp}"
if [[ -f "$LLAMA_DIR/include/llama.h" && "${#JNI_INCLUDES[@]}" -gt 0 ]]; then
    say "==> bridge-llama (found $LLAMA_DIR)"
    # Capability probes are CMake's job; here we only check the file still parses.
    # Capability probes (OFFLINEGGUF_LLAMA_HAS_*) are CMake's job; without them
    # the newest symbols are assumed present, which is right for the pinned tag.
    step bridge-llama-syntax \
        "$CXX_BIN" "${CXXFLAGS[@]}" "${JNI_INCLUDES[@]}" -I"$OUT/host-stub" \
        -I"$LLAMA_DIR/include" -I"$LLAMA_DIR/ggml/include" \
        -DOFFLINEGGUF_HAVE_LLAMA=1 -fsyntax-only "$CPP/llama/llama_bridge_llama.cpp"
else
    skip bridge-llama "llama.cpp not present (run scripts/sync-llama.sh to enable it)"
fi

# ------------------------------------------------------------------ summary
echo
echo "native checks: $PASSED passed, $FAILED failed, $SKIPPED skipped"
if [[ "$FAILED" -gt 0 ]]; then
    echo "logs in $OUT"
    exit 1
fi
