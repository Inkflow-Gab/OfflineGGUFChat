# Offline GGUF Chat

A quiet, black-and-white Android app for talking to GGUF language models that live
on your phone. No account, no API key, no network permission at all — the app
cannot phone home because it was never given the ability to.

```
┌──────────────────────────────────────┐
│  Qwen2.5 3B · Q4_K_M        ⌁ 18 t/s │   ← model, quant, live speed
│  ▓▓▓▓▓▓▓▓▓▓░░░░░░  2 148 / 4 096 tok │   ← context gauge
├──────────────────────────────────────┤
│  you                            ♥ 0  │
│  Explain mmap in one line.           │
│                                      │
│  model                          ♥ 3  │
│  ▸ thinking · 41 tokens              │   ← collapsible reasoning
│  It maps a file into memory and      │
│  lets the kernel page it in.         │
│  ```diff                             │
│  - read() into a buffer              │   ← added/removed lines
│  + mmap() the file directly          │
│  ```                                 │
├──────────────────────────────────────┤
│  [ skill ▾ ]  Ask anything…      ▸   │
└──────────────────────────────────────┘
```

Everything on that screen is drawn in ink and paper. There is no colour anywhere
in the app — not for accents, not for errors — because a transcript that already
holds code, diffs and gauges does not need a fourth visual channel. See
[`docs/DESIGN.md`](docs/DESIGN.md).

---

## What it does

**Models**

- Reads real GGUF files: magic, version, alignment, tensor table, and the full
  metadata section, parsed by a hand-written reader in
  [`GgufReader.kt`](app/src/main/java/com/offlinegguf/chat/data/gguf/GgufReader.kt).
  Architecture, parameter count, dominant quantisation, context length, vocab
  size, block count and KV shape all come out of the file itself — nothing is
  guessed from the filename.
- Import from the system file picker, from a share sheet, or by opening a
  `.gguf` from a file manager (the app registers for `*.gguf`). Imports are real
  copies into app storage with per-64 KB progress, so a moved or deleted source
  file cannot break a conversation later.
- Tells you *before* you load whether a model will fit: RAM budget, KV-cache
  cost per token, expected context, and an estimated tokens/second, from
  [`ModelFit.kt`](app/src/main/java/com/offlinegguf/chat/domain/fit/ModelFit.kt).
  Per-model overrides for context, threads, batch, GPU layers and KV type.

**Chat**

- Streaming output, coalesced on a 256 ms / 48-character window so the UI stays
  smooth instead of redrawing per token.
- Reasoning models get their scratch work split into a separate collapsible
  *thinking* channel (`<think>`, `<thinking>`, `<reasoning>` and friends),
  incrementally, with marker prefixes held back so a tag split across two token
  pieces never leaks into the answer.
- Markdown rendering with **added/removed line diffs** inside code blocks: when a
  model answers with a patch, or when you regenerate and compare, changed lines
  are marked with `+` / `−`, gutter-marked and tinted rather than coloured.
- **Per-message like counts.** Every message carries a ♥ with a count; the
  conversation caches its totals, and the top bar shows the running sum. A
  second flag marks "not helpful", which is kept separately from a like.
- Stop mid-generation, regenerate from any point (the tail is rewound, not
  deleted), edit and resend, pin conversations, search history, export a
  conversation as Markdown or JSON.
- Nine built-in *skills* — prompt + GBNF grammar pairs such as `code-reply`,
  `patch-mode`, `json-extract`, `summarize`, `no-refusals` — editable in-app,
  with grammar validation before save.

**Discovery**

- A curated offline catalogue of 18 models scored against *your* device: RAM,
  cores, free storage and API level decide what is recommended, what is merely
  possible, and what will not fit. Preferences cover use-case style, a
  speed-vs-quality bias, a download ceiling, and an **uncensored** switch that
  adds the five alignment-removed / abliterated entries.
- The app has no internet permission, so Discover cannot download anything. It
  ranks locally and hands you a link to huggingface.co to open in your browser.

## Getting the app

Prebuilt APKs are published on the
[releases page](https://github.com/Inkflow-Gab/OfflineGGUFChat/releases) for
every `v*` tag, with a `checksums.txt` alongside:

| Asset | Signed with | Use |
| --- | --- | --- |
| `OfflineGGUFChat-<version>-release.apk` | release key when the repo secrets exist, otherwise the CI debug key | daily driver |
| `OfflineGGUFChat-<version>-debug.apk` | debug key | poking at logs |

Both are `arm64-v8a` only, which is what phones from the last several years run.

### Building from source

```bash
git clone https://github.com/Inkflow-Gab/OfflineGGUFChat.git
cd OfflineGGUFChat
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 167 JVM unit tests
./scripts/check-native.sh        # native checks, no NDK needed
```

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34.0.0.
NDK 25.2.9519653 and CMake 3.22.1 are needed for the native layer, which is
built in both configurations — see below.

## The engine is optional, on purpose

The repository does **not** vendor [llama.cpp](https://github.com/ggml-org/llama.cpp).
A fresh clone builds a complete app whose native layer is real, compiled code —
it just reports "engine not compiled in" instead of pretending to generate text.
Every screen that could offer generation prints the engine's own
`unavailableReason` verbatim, so you are never left guessing.

To turn inference on:

```bash
./scripts/sync-llama.sh --engine     # fetch the pinned revision, then build
# or, step by step:
./scripts/sync-llama.sh
./gradlew assembleDebug -Pofflinegguf.llama=true
```

`llama.cpp.pin` records the exact tag and commit the bridge was written against,
`app/llama-cpp/` is git-ignored, and the C++ side touches `llama.h` in exactly
one translation unit behind 25 compile-time capability probes, so a different
llama.cpp revision degrades instead of failing to build. The full story — bridge
design, JNI surface, 16 KB page alignment, gradle flags, ABI selection — is in
[`docs/NATIVE.md`](docs/NATIVE.md).

## Repository layout

```
app/src/main/java/com/offlinegguf/chat/
  util/         formatting, streaming UTF-8 decoder, diff engine, markdown blocks
  data/gguf/    GGUF types + the sequential header reader
  data/catalog/ bundled model catalogue and the device-aware advisor
  data/local/   Room entities, DAOs, database
  data/settings/DataStore-backed preferences
  data/repository/  model, chat and skill repositories
  domain/device/handset probe and capability tiers
  domain/fit/   will-it-run arithmetic
  domain/skills/GBNF handling and the built-in skills
  inference/    JNI bindings, stream coalescer, thinking parser, prompt templates
  ui/theme/     ink palette, type scale, motion spec, spacing grid
  ui/components/code block, markdown, message row, gauges, input bar
  ui/screens/   chat, models, model detail, discover, skills, settings, history
  viewmodel/    one per screen
app/src/test/   JVM unit tests (no emulator, no mocking framework)
app/cpp/        GGUF probe, UTF-8 framer, JNI layer, two engine bridges
scripts/        sync-llama.sh, check-native.sh
.github/workflows/android-build.yml
```

55 Kotlin files (~14 100 lines), 10 C++ files (~2 600 lines), 11 test files with
167 tests, 46 hand-written stroke icons plus two launcher layers, and no
third-party icon or font assets.

## Continuous integration

`.github/workflows/android-build.yml` runs four jobs on normal pushes and pull
requests:

1. **native** — `./scripts/check-native.sh` on a plain runner: the GGUF parser
   against golden fixtures, the UTF-8 framer, and the no-engine bridge.
2. **unit-tests** — `./gradlew testDebugUnitTest`, with the HTML report uploaded
   as an artifact.
3. **apk** — needs both of the above; assembles debug *and* release APKs
   (`-Pofflinegguf.abis=arm64-v8a`) and uploads them.
4. **release** — on `v*` tags only; renames the APKs to
   `OfflineGGUFChat-<version>-{debug,release}.apk`, writes `checksums.txt`
   (sha256) and publishes a GitHub Release.

The optional **engine-android** job is deliberately not part of normal CI. Use
GitHub Actions → Android Build → Run workflow and set `build_llama_engine` to
build the pinned llama.cpp source into an arm64 debug APK. It verifies that the
real JNI library made it into the APK and uploads it as
`llama-engine-arm64-debug-apk`. That is the end-to-end Android packaging check;
normal CI still proves the app builds without fetching/vendorizing llama.cpp.

Set `KEYSTORE_FILE` (base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` as repository secrets to sign release builds with your own key.
Without them the release APK is signed with the CI debug key and still installs.

## Privacy

- **Permissions requested: `WAKE_LOCK`, and that is the whole list.** No
  `INTERNET`, so no telemetry is possible even by accident. No storage permission
  either — files arrive through the Storage Access Framework.
- Conversations, messages, likes and imported models stay in app-private storage
  (Room + `filesDir/models`). Uninstalling removes them.
- Nothing leaves the device. The only outbound navigation is an explicit
  `huggingface.co` link you tap in Discover, handled by your browser.

## Licence

MIT — see [`LICENSE`](LICENSE). Model weights are *not* included and are covered
by their own licences, listed per entry in the catalogue
([`docs/CATALOG.md`](docs/CATALOG.md)). llama.cpp is MIT-licensed and is fetched
by `scripts/sync-llama.sh`, never committed here.
