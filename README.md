# Offline GGUF Chat

A quiet, black-and-white Android app for talking to GGUF language models that live
on your phone. No account, no API key, no phoning home: on-device inference is the
default and the identity of the app. Since 1.4 there is also an *optional* Cloud
tab — bring your own key (free tiers on OpenRouter, Gemini, Groq, Cerebras,
Mistral and NVIDIA; OpenCode Console pay-as-you-go) — which is off until you
turn it on, and the only thing in the app that can open a socket.

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

- Streaming output, coalesced on a ~96 ms / 16-character window for visibly
  progressive text without redrawing or writing to Room for every token.
- A quick chat-mode menu: Balanced, Fast, Chat, Coding, and Deep thinking. Modes
  adjust the prompt and relevant sampling defaults; the model menu routes to the
  full 38-model catalogue, including thirteen refusal-free fine-tunes behind an
  18+ confirmation.
- Reasoning models get their scratch work split into a separate collapsible
  *thinking* channel (`<think>`, `<thinking>`, `<reasoning>` and friends),
  incrementally, with marker prefixes held back so a tag split across two token
  pieces never leaks into the answer.
- Markdown rendering with **added/removed line diffs** inside code blocks: when a
  model answers with a patch, or when you regenerate and compare, changed lines
  are marked with `+` / `−`, gutter-marked and tinted rather than coloured.
  Fenced blocks can show a filename, language, line count and one-tap copy;
  blocks over 50 lines collapse into a compact file-style panel, with copy and
  Android's document picker for saving snippets as files.
- **Per-message like counts.** Every message carries a ♥ with a count; the
  conversation caches its totals, and the top bar shows the running sum. A
  second flag marks "not helpful", which is kept separately from a like.
- Long answers can continue in bounded chunks with one tap, reusing the same
  assistant message. There is no app-wide total-output cap; each pass is bounded
  by the model's context and phone memory.
- Long conversations are windowed before tokenization to keep prompt evaluation
  bounded. The app keeps the complete transcript in local storage and tells you
  when older turns or part of an oversized newest turn were omitted from a prompt. Stop mid-generation, regenerate from
  any point, edit and resend, pin conversations, search history, export as Markdown or JSON.
- Nine built-in *skills* — prompt + GBNF grammar pairs such as `code-reply`,
  `patch-mode`, `json-extract`, `summarize`, `no-refusals` — editable in-app,
  with grammar validation before save. Custom skills can define reusable task
  roles or fictional personas and their speaking style.

**Discovery**

- A curated offline catalogue of 38 models scored against *your* device: RAM,
  cores, free storage and API level decide what is recommended, what is merely
  possible, and what will not fit. Qwen3, Gemma 3, DeepSeek R1 distills and
  friends at every size from 229 MB up; preferences cover use-case style
  (including Reasoning and Roleplay), a speed-vs-quality bias and a download
  ceiling. A **"Start here"** card names the single best first download for
  your phone.
- Refusal-free / abliterated entries now exist at 1B–8B (not just 7B-class)
  and sit behind a one-time **18+ confirmation**; unconfirmed, those models
  are not even scored.
- Discover never downloads anything itself. It ranks locally and hands you a
  link to huggingface.co to open in your browser.

**Cloud (opt-in, off by default)**

- OpenRouter, **Google Gemini**, Groq, **Cerebras**, **Mistral**, NVIDIA,
  OpenCode Console, or any OpenAI-compatible server (llama.cpp / LM Studio /
  Ollama on your LAN). Paste an API key, browse the live
  model list with **FREE badges**, a free-only filter, prices and context
  sizes, then tap a model to chat with it. OpenCode's free models are labelled
  **IN-APP ONLY**: they answer 403 outside the OpenCode app by OpenCode's own
  policy, so the picker hides them by default, explains the refusal if you run
  one anyway, and points at free providers that do work. Streaming, the
  thinking channel, stop/continue/regenerate and the transcript all behave
  exactly like a local model. Keys live in app-private storage, outside the settings
  snapshot, and are sent only to the provider you picked.

**Workspace + web sandbox (per session)**

- Every session history gets its own workspace: snippets saved from answers
  and pages saved from the sandbox live there, keyed by name, and never leak
  into other chats. Re-saving `index.html` updates the one file.
- HTML, CSS and JS code blocks carry a ▶ button that runs them in a locked-down
  WebView: JavaScript on, but the network, file system and DOM storage off —
  even `fetch()` cannot phone home. `console.log` is mirrored into the page.

**Slow first answer? It tells you where the time went**

- Every generation logs one line and keeps it under *⋮ → Generation details*:
  prompt tokens, tokenize/prefill time, time-to-first-token, first time text was
  on screen, decode speed, thread count, context, batch, and how many times the
  model was loaded.
- `adb logcat -s InferenceTrace OfflineGGUF` shows it live. A 28-token "hello"
  that takes minutes prints its verdict: hidden reasoning, slow decode, or
  prompt evaluation.
- Reasoning is **off by default** for models that have it (Qwen3 and friends).
  Turning it on is one menu tap; the app switches the model's own template, it
  does not strip tokens after the fact. Off, a greeting is answered directly.
- The model is loaded once and reused: opening an old chat no longer re-reads
  the weights or discards the KV cache.

**Fast on a phone, not just correct**

- Inference runs on the **performance cores**. ggml waits for its slowest worker,
  so putting threads on a 6×A55 cluster makes every matmul finish at little-core
  speed — the app reads the CPU clock bins and keeps workers on the big cluster.
- The whole native engine is built `-O3` (ggml's hot loops are C, not C++), KV
  cache defaults to q8_0 when the phone is tight on RAM, and prompt batches got
  larger so prefill needs fewer decode calls.
- Generation stops at the model's own turn-end token instead of running on to
  the token cap, so answers finish cleanly and quickly.
- Qwen3-style ` thinking` blocks are recognised as reasoning even without the
  closing angle bracket, so they never leak into the answer.

**Navigation**

- Four animated tabs — Chat, Models, Discover, Cloud — with springy icons,
  a bottom bar that slides away on detail screens, cross-fades between
  tabs and directional slides when you go deeper. Everything collapses to
  clean cuts under *Reduce motion*.

## Getting the app

Prebuilt APKs are published on the
[releases page](https://github.com/Inkflow-Gab/OfflineGGUFChat/releases) for
every `v*` tag, with a `checksums.txt` alongside:

| Asset | Signed with | Use |
| --- | --- | --- |
| `OfflineGGUFChat-<version>-release.apk` | **the one release keystore** (see [`docs/SIGNING.md`](docs/SIGNING.md)) | daily driver |
| `OfflineGGUFChat-<version>-debug.apk` | debug key | poking at logs |

Every release is signed with the same key, so a new APK installs straight
over the old one — chats, models and settings stay put. Signing is a
one-time setup: `./scripts/setup-keystore.sh`, paste four GitHub secrets,
done forever.

Both are `arm64-v8a` only, which is what phones from the last several years run.

### Building from source

```bash
git clone https://github.com/Inkflow-Gab/OfflineGGUFChat.git
cd OfflineGGUFChat
./scripts/sync-llama.sh          # fetch the pinned inference engine
./gradlew assembleDebug -Pofflinegguf.abis=arm64-v8a
./gradlew testDebugUnitTest      # JVM unit tests
./scripts/check-native.sh        # native checks; exercises llama.cpp if synced
```

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34.0.0.
NDK 25.2.9519653 and CMake 3.22.1 build the native library; the distributable
CI APKs compile the real llama.cpp backend into that library.

## llama.cpp is included in the installable APKs

The source repository does not vendor upstream llama.cpp, but the normal CI APK
build now fetches the pinned revision and compiles the real engine into **both
debug and release APKs**. That is the APK to install when you want to load GGUF
models; the previous engine-free APK correctly reported that inference was not
connected.

For a local build, run:

```bash
./scripts/sync-llama.sh
./gradlew assembleDebug -Pofflinegguf.abis=arm64-v8a
```

Gradle detects `app/llama-cpp/include/llama.h` and enables the engine
automatically. `-Pofflinegguf.llama=true` remains available to force it on, and
fails clearly if the source was not synced. The C++ bridge is pinned to the
revision in `llama.cpp.pin`; the checkout is git-ignored and never committed.

### Use it without root

Root is **not required** on Android. Import a `.gguf` through Models → Import or
open/share the file from a file manager. Android's Storage Access Framework gives
the app temporary access; the app copies the model into its own private
`filesDir/models` directory, then llama.cpp opens that stable path as the normal
app user. Rooted phones use the same safe app sandbox; root does not improve CPU
inference or remove RAM/storage limits. Pick a model that the device-aware fit
adviser marks usable, and leave enough free storage for the import copy.

On-device inference never touches the network; the `INTERNET` permission
exists only for the opt-in Cloud tab and stays unused while cloud is off.
Download model weights in a browser or another trusted source, verify their
licence, then import the GGUF; model files are not bundled in the APK.

## Repository layout

```
app/src/main/java/com/offlinegguf/chat/
  util/         formatting, streaming UTF-8 decoder, diff engine, markdown blocks
  data/gguf/    GGUF types + the sequential header reader
  data/catalog/ bundled model catalogue and the device-aware advisor
  data/cloud/   opt-in OpenAI-compatible providers (OpenRouter/Groq/NVIDIA/OpenCode Zen/custom)
  data/workspace/  per-session workspace files + the sandbox document shell
  data/local/   Room entities, DAOs, database
  data/settings/DataStore-backed preferences
  data/repository/  model, chat and skill repositories
  domain/device/handset probe and capability tiers
  domain/fit/   will-it-run arithmetic
  domain/skills/GBNF handling and the built-in skills
  inference/    JNI bindings, stream coalescer, thinking parser, prompt templates
  ui/theme/     ink palette, type scale, motion spec, spacing grid
  ui/components/code block, markdown, message row, gauges, input bar
  ui/screens/   chat, models, model detail, discover, cloud, skills, settings, history,
                workspace, sandbox
  viewmodel/    one per screen
app/src/test/   JVM unit tests (no emulator, no mocking framework)
app/cpp/        GGUF probe, UTF-8 framer, JNI layer, two engine bridges
scripts/        sync-llama.sh, check-native.sh
.github/workflows/android-build.yml
```

~60 Kotlin files (~16 000 lines), 10 C++ files (~2 600 lines), 17 test files,
51 hand-written stroke icons plus the launcher mark (colour + themed
monochrome), and no third-party icon or font assets. Where it goes next:
[`docs/ROADMAP.md`](docs/ROADMAP.md).

## Continuous integration

`.github/workflows/android-build.yml` runs four jobs on normal pushes and pull
requests:

1. **native** — `./scripts/check-native.sh` on a plain runner: the GGUF parser
   against golden fixtures, the UTF-8 framer, and the no-engine bridge.
2. **unit-tests** — `./gradlew testDebugUnitTest`, with the HTML report uploaded
   as an artifact.
3. **apk** — needs both of the above; fetches the pinned llama.cpp revision,
   assembles engine-enabled debug *and* release APKs for arm64, decodes the
   one release keystore from the `KEYSTORE_B64` secret, verifies that
   `libllama_jni.so` contains the real engine/JNI entry point, prints the
   signing-certificate fingerprint into the log, and uploads them.
4. **release** — on `v*` tags only; renames the APKs to
   `OfflineGGUFChat-<version>-{debug,release}.apk`, writes `checksums.txt`
   (sha256) and publishes a GitHub Release.


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
