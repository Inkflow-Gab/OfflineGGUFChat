# The native layer

How the C++ side is built, why llama.cpp is not in the repository, and what to do
when a llama.cpp revision moves the API.

---

## 1. The rule: the engine is optional

`OFFLINEGGUF_WITH_LLAMA` defaults to **OFF**. In that configuration the build
still produces a real `libllama_jni.so` with all 23 JNI entry points — it just
links against a bridge that reports the engine as absent:

```
BridgeAvailable()      -> false
BridgeEngineVersion()  -> "none (llama.cpp not linked)"
BridgeLoad(...)        -> false, error = kMessage (below)
BridgeGenerate(...)    -> false, and fires callbacks.on_error with the same text
```

`kMessage` is written for the person who will actually read it:

```
Inference engine not compiled into this build.

This APK contains the GGUF tooling (scan, inspect, import, chat history) but
no llama.cpp backend, so generation is unavailable.

To build with inference:
  1. ./scripts/sync-llama.sh
  2. ./gradlew assembleRelease -Pofflinegguf.llama=true

See docs/NATIVE.md.
```

That text is surfaced verbatim by `NativeLlamaEngine.unavailableReason`, and the
chat, model-detail and settings screens print it rather than showing a generic
failure. A fresh clone therefore builds, installs, parses GGUF files,
scores the catalogue and runs every unit test — it simply cannot generate tokens
until you opt in.

Why not vendor llama.cpp?

- It is ~40 MB of source and would dominate this repository.
- Its licence and its release cadence are its own business; pinning a copy here
  invites stale, silently-diverging forks.
- The default CI build then needs no NDK-heavy 20-minute compile to stay green.

## 2. Turning the engine on

```bash
./scripts/sync-llama.sh                    # clone the pinned tag into app/llama-cpp
./gradlew assembleDebug -Pofflinegguf.llama=true
```

Or in one step: `./scripts/sync-llama.sh --engine`.

| Input | Meaning |
| --- | --- |
| `-Pofflinegguf.llama=true` | sets `OFFLINEGGUF_WITH_LLAMA=ON` (env `OFFLINEGGUF_LLAMA` works too) |
| `-Pofflinegguf.llamaDir=PATH` | use a llama.cpp checkout elsewhere; default `app/llama-cpp` |
| `-Pofflinegguf.abis=a,b` | restrict ABIs; default `arm64-v8a,armeabi-v7a,x86_64`. CI uses `arm64-v8a` |
| `-Pofflinegguf.minify=true` | R8 + resource shrinking for the release variant |
| `./scripts/sync-llama.sh --tag bNNNN` | fetch a different revision than the pin |
| `./scripts/sync-llama.sh --update` | re-fetch the pinned tag |

`llama.cpp.pin` is the source of truth for the revision:

```
LLAMA_CPP_REPO=https://github.com/ggml-org/llama.cpp
LLAMA_CPP_TAG=b11721
LLAMA_CPP_COMMIT=ff5a23376725e5a889fa0bfb987b7541dc304da0
LLAMA_CPP_HEADER=include/llama.h
```

`app/llama-cpp/` is git-ignored; nothing the script fetches ever enters a commit.
If `OFFLINEGGUF_WITH_LLAMA=ON` and no `llama.h` is found, CMake stops with a
`FATAL_ERROR` naming both the directory it searched and the command to run —
never a wall of missing-symbol errors.

## 3. Directory map

```
app/cpp/
  CMakeLists.txt              options, probes, targets, page-size flags
  llama_jni.cpp               the JNI surface (23 functions) + token pump
  gguf/gguf_probe.{h,cpp}     standalone GGUF header reader used as a preflight
  llama/llama_bridge.h        backend-agnostic contract (the only shared header)
  llama/llama_bridge_none.cpp compiled when the engine is OFF
  llama/llama_bridge_llama.cpp compiled when the engine is ON — the ONLY file
                              that includes llama.h
  llama/llama_compat.h        OFFLINEGGUF_LLAMA_HAS_* shims
  util/utf8.h                 CompleteUtf8Length: how many leading bytes of a
                              buffer form whole UTF-8 code points
  tests/gguf_probe_test.cpp   golden-fixture host test
  tests/utf8_test.cpp         framer host test
```

Targets: `offlinegguf_gguf` (static), `llama_jni` (shared, the one Kotlin loads
with `System.loadLibrary("llama_jni")`), plus `gguf_probe_test` and `utf8_test`
when `OFFLINEGGUF_HOST_TESTS=ON`.

## 4. The compatibility probe layer

llama.cpp renames things. Rather than `#ifdef` on a version number, CMake
compile-tests each symbol the bridge actually uses and defines a capability
macro. There are 25 of them:

```
ABORT_CALLBACK      ADAPTER_LORA        BATCH_GET_ONE       CHAT_TEMPLATE
CTX_THREADS         INIT_FROM_MODEL     KV_SELF_CLEAR       KV_SELF_SEQ_RM
LOAD_MODE           MEMORY_API          MEMORY_SEQ_POS_MAX  MEMORY_SEQ_RM
MODEL_DESC          MODEL_FREE          MODEL_LOAD_FROM_FILE MODEL_N_CTX_TRAIN
MODEL_N_PARAMS      MODEL_SIZE          PRINT_SYSTEM_INFO   SAMPLER_GRAMMAR
SAMPLER_PENALTIES   TOKEN_TO_PIECE_LSTRIP  VOCAB_API        VOCAB_GET_ADD_BOS
VOCAB_IS_EOG
```

(all prefixed `OFFLINEGGUF_LLAMA_HAS_`). `llama_compat.h` then provides the
fallback — an older KV-cache API instead of the newer `llama_memory_*` one, the
pre-vocab `llama_token_eos()` instead of `llama_vocab_is_eog()`, and so on. A
revision that drops something the bridge needs loses a capability and takes the
shim path; it does not fail to compile.

**Adding a probe:** append a `check_symbol_exists`/`check_cxx_source_compiles`
block in `CMakeLists.txt`, add the `OFFLINEGGUF_LLAMA_HAS_*` definition to the
list, then guard the call site in `llama_bridge_llama.cpp`. Bumping
`LLAMA_CPP_TAG` in the pin file means re-running this pass.

## 5. The JNI contract

Two invariants matter more than the rest.

**Raw bytes, not Java strings.** Token pieces arrive as
`onTokenBytes(byte[])` — the unmodified UTF-8 the tokenizer produced. A single
piece can end mid-code-point (an emoji is routinely split across two pieces), and
JNI's modified-UTF-8 string conversion would corrupt it. The Kotlin side
reassembles with `StreamingUtf8Decoder`, which uses the same rule as
`ogg::CompleteUtf8Length` in `util/utf8.h`: emit the longest whole-code-point
prefix, keep the tail. Both implementations are covered by tests —
`utf8_test.cpp` on the host, `StreamingUtf8DecoderTest.kt` on the JVM.

**One generation at a time, never freed underneath itself.** `nativeGenerate`
blocks its own thread and pumps callbacks; `nativeStop` sets an abort flag the
decode loop checks between tokens. `nativeDestroySession` calls
`BridgeWaitIdle` before freeing, so a session cannot be torn down mid-decode.
Kotlin runs generation on a single-thread dispatcher built from
`Executors.newSingleThreadExecutor()` rather than `Dispatchers.Default`, because
the engine is not re-entrant.

Handles are `jlong` session pointers validated by `Resolve()`; an invalid handle
returns a zero/empty value or the string `"invalid session handle"` instead of
crashing. Six of the 23 functions are generated by `FORWARD_INT` /
`FORWARD_STRING` macros, so `grep` for a symbol name will not find them — use
`nm -g --defined-only` on a compiled object, or the count below.

```
Kotlin `external fun` declarations : 23
C++ JNI definitions                : 23  (17 explicit + 6 macro-generated)
```

## 6. Build details worth knowing

- **16 KB pages.** `target_link_options(llama_jni PRIVATE "-Wl,-z,max-page-size=16384")`
  plus `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, so the `.so` loads on devices
  with a 16 KB kernel page size. `DeviceProfile.pageSizeBytes` records what the
  handset actually runs with.
- **Size.** `-O2 -ffunction-sections -fdata-sections` with `-Wl,--gc-sections`
  on release; `c++_shared` STL.
- **NDK / CMake.** 25.2.9519653 and 3.22.1, pinned in `app/build.gradle.kts` so
  local and CI builds agree.
- **minSdk 24, compile/target 34.**

## 7. GGUF parsing exists twice, deliberately

`app/cpp/gguf/gguf_probe.cpp` and
`data/gguf/GgufReader.kt` implement the same format independently. The Kotlin
reader is the authority — it produces everything the UI shows. The C++ probe is
a *preflight* the engine runs before handing a path to llama.cpp, so a truncated
download is rejected with our own error message instead of an opaque engine
failure.

Both are validated against the same expectations: the Kotlin reader by
`GgufReaderTest` (which writes GGUF files in memory — v1/v2/v3, every value type,
string arrays, odd alignments, truncated tensor tables) and the C++ probe by
`gguf_probe_test`. The two agreed on all nine shared fixtures when this was
last cross-checked.

Parsing rules both follow: counts and string lengths are `u64` for v2/v3 (`u32`
only for v1, which llama.cpp itself now rejects), array lengths are always `u64`,
tensor dimensions are `u32` for v1 and `u64` otherwise, alignment is a
power-of-two read from `general.alignment` (default 32), and tensor data starts
at `alignUp(headerEnd, alignment)`. Limits guard against a corrupt header
claiming absurd sizes: 10 M tensors, 1 M key-value pairs, 8 dimensions, and only
the first 8 strings of any array are materialised.

## 8. Checks you can run without the NDK

```bash
./scripts/check-native.sh
```

Runs on a plain Linux/macOS host with a C++17 compiler:

| Step | What it does |
| --- | --- |
| `gguf-probe-test` / `gguf-probe-run` | builds and runs `gguf_probe_test` |
| `utf8-test` / `utf8-run` | builds and runs `utf8_test` |
| `bridge-none` | compiles `llama_bridge_none.cpp` — the default build path is real code |
| `jni-syntax-check` | type-checks `llama_jni.cpp` against a real `jni.h`; **skipped** with no JDK (set `JAVA_HOME`) |
| `bridge-llama` | when `app/llama-cpp` exists, CMake compiles pinned llama.cpp and the real bridge, then smoke-tests availability, version, session lifetime and a failed model load; skipped otherwise |

Logs land in `build/native-check/`. On a bare host this reports 5 passed /
2 skipped; with a JDK available, 6 passed / 1 skipped. With llama.cpp synced and
CMake installed it builds the real host library and runs the three CTest cases
(2 core tests + the bridge smoke). The separate manual `engine-android` workflow
builds the actual arm64 APK through the Android NDK and checks the packaged JNI
library. Normal CI remains llama-free. CI runs the default checks as its own
`native` job so a native regression fails fast and separately from Gradle.

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `UnsatisfiedLinkError: llama_jni` | ABI not built for this device | rebuild with the device's ABI in `-Pofflinegguf.abis` |
| Chat says the engine is not compiled in | expected on a fresh clone | `./scripts/sync-llama.sh` then `-Pofflinegguf.llama=true` |
| CMake `FATAL_ERROR ... no llama.h` | engine ON, source missing | run the sync script, or pass `-Pofflinegguf.llamaDir` |
| Build fails after bumping the pin | API moved | re-run the capability probes (§4) |
| Garbled emoji in output | a bridge bypassed the byte contract | tokens must go through `onTokenBytes`, never `NewStringUTF` |
| App killed mid-generation | model too big for the RAM budget | trust the fit verdict in Model Detail; drop context or quant |
