# Roadmap & feature brainstorm

Where Offline GGUF Chat goes next. Shipped items link to the release that
carried them; the backlog is grouped by theme and tagged with rough effort
(S/M/L) and whether it needs the network (🌐), stays fully on-device (📱),
or is build/release plumbing (🔧).

The design rules do not change: black-and-white ink & paper, no account,
on-device inference is the default and the identity of the app, anything
network-shaped is opt-in with the user's own key.

---

## ✅ Shipped in 1.4.0

- **Cloud tab (opt-in)** — OpenRouter, Groq, NVIDIA and any
  OpenAI-compatible server (llama.cpp / LM Studio / Ollama on your LAN).
  Live model browser with **FREE badges**, free-only filter, search, prices
  and context sizes; one tap makes a cloud model the chat target. Streaming,
  thinking-channel split, stop/continue/regenerate all shared with the
  local engine. Off by default; keys never leave the phone except to the
  chosen provider.
- **Catalogue v4: 38 models** — Qwen3 (0.6B–8B), Gemma 3, SmolLM2,
  Phi-4 mini, DeepSeek R1 distills, Qwen2.5 Coder 3B; refusal-free builds
  at *every* size (1B/1.5B/3B abliterated + Dolphin 3 + Stheno roleplay),
  with `Thinking` and `18+` badges and a recency nudge in scoring.
- **"Start here" pick** in Discover — the single best small download that
  runs comfortably on *this* phone, one tap to the file.
- **18+ maturity gate** — the refusal-free shelf is locked behind a
  one-time age confirmation (Discover card or Settings → Maturity).
  Unconfirmed users don't just see it blurred: those models are never
  scored or listed.
- **New navigation** — four tabs (Chat · Models · Discover · Cloud),
  spring-animated tab icons, a bottom bar that slides away on detail
  screens, tab-to-tab cross-fades vs. directional push slides.
- **New app icon** — ink bubble + streaming token lines on paper, with an
  Android 13 themed (monochrome) variant.
- **One keystore forever** — `scripts/setup-keystore.sh` + CI decoding;
  every future APK installs as an update. See `SIGNING.md`.
- **Check for updates** in Settings → About opens the latest GitHub
  release; same-key signing makes it a one-tap update.

---

## 💬 Chat experience

- **Personas library** (M 📱) — first-class characters (name, avatar glyph,
  system prompt, greeting, sampling preset) built on Skills; a persona
  picker above the composer. Roleplay styles in the catalogue already
  point here.
- **Chat branching** (M 📱) — "branch from here" on any message forks the
  transcript; branches listed in History under the parent.
- **Voice input** (M 📱) — on-device speech-to-text via Android's
  `SpeechRecognizer` (or whisper.cpp later); push-to-talk button in the
  composer.
- **Read aloud** (S 📱) — TTS per message with the system engine; auto-read
  toggle for hands-free roleplay/cooking/coding.
- **Swipe between conversations** (S 📱) — horizontal swipe on the top bar
  to move through recent chats, with the tab-style cross-fade.
- **Per-chat model + settings memory** (M 📱) — reopening a chat restores
  its model (local or cloud), mode and skill.
- **Message queue** (S 📱) — type the next message while one is streaming;
  it sends when generation ends.
- **Token-level drafts** (M 🌐) — for cloud models, show an estimated cost
  under the composer before sending (price × windowed tokens).

## 🧠 Models & discovery

- **In-app downloader** (L 🌐, opt-in) — download a catalogue GGUF directly
  (with resume + checksum) instead of the browser hand-off, behind the same
  "you turned networking on" consent as Cloud. Biggest friction-killer in
  the app.
- **Model of the week / what's new shelf** (S 📱) — catalogue `released`
  dates already exist; surface the newest three at the top of Discover.
- **Compare view** (M 📱) — pick two catalogue entries, side-by-side fit,
  speed, context cost on *your* phone.
- **Quant chooser** (M 📱) — per family, explain Q4_K_M vs Q5_K_M vs Q8_0
  with the fit verdict for each before downloading any of them.
- **Benchmark my phone** (M 📱) — 20-second local prompt-eval micro-benchmark
  replacing the tok/s estimate with a measured number that then feeds the
  advisor.
- **Vision (mmproj) support** (L 📱) — Gemma 3 / Qwen2.5-VL GGUFs are in the
  wild; needs clip wiring in the JNI bridge + image attach in the composer.
- **LoRA picker polish** (M 📱) — the load request already carries adapter
  paths; add import + per-model adapter lists in Model Detail.

## ☁️ Cloud

- **More free providers** (S 🌐) — GitHub Models, Cerebras, Together free
  tier, SambaNova, Mistral La Plateforme, Hugging Face Inference Providers.
  The provider table makes each a ~6-line entry.
- **Auto model refresh** (S 🌐) — cache the model list per provider, refresh
  in the background when the Cloud tab opens; NEW badge on models that
  appeared since last visit.
- **Usage meter** (M 🌐) — per-provider token/cost totals stored locally;
  OpenRouter key-info endpoint for remaining credits.
- **Fallback chain** (M 🌐) — "if this model 429s, try that one" — pick an
  ordered list of free models; the engine walks it on rate-limit errors.
- **Cloud vision** (M 🌐) — attach images to cloud models that accept them
  (`image_url` parts); share-sheet "Ask about this image".
- **Web search toggle** (M 🌐) — OpenRouter's `:online` suffix / provider
  search tools behind an explicit per-chat switch, clearly badged.

## 🎨 Design & animation

- **Animated empty states** (S 📱) — the launcher mark's token lines
  "streaming" as a subtle loop on the empty chat screen (respects Reduce
  motion).
- **Shared-element model cards** (M 📱) — Discover card → Model Detail
  morph once Navigation Compose shared transitions are stable in our BOM.
- **Haptic vocabulary** (S 📱) — tiny ticks on send, like, tab switch and
  generation-complete; already have the `haptics` setting.
- **Widgets & shortcuts** (M 📱) — home-screen shortcut "New chat with
  <persona/model>", app-shortcut long-press menu, Quick Settings tile to
  unload the model.
- **Tablet / landscape two-pane** (M 📱) — history list + chat side by side
  on wide windows; the tab bar becomes a rail.

## 🔧 Platform, data, release

- **Backup & restore** (M 📱) — export/import everything (chats, skills,
  settings, model list) as one zip via SAF; cloud keys excluded by default
  with an explicit opt-in checkbox.
- **Encrypted key storage** (S 📱) — move cloud API keys from DataStore to
  Android Keystore-wrapped storage.
- **Delta-friendly releases** (S 🔧) — publish per-ABI APKs and an .aab for
  anyone sideloading via bundletool; keep the universal arm64 APK primary.
- **F-Droid metadata** (S 🔧) — fastlane tree so the app can be submitted;
  reproducible-build notes alongside `llama.cpp.pin`.
- **Update badge** (S 🌐, opt-in with Cloud) — when cloud is on, quietly
  check the GitHub releases API at most daily and badge Settings when a
  newer signed APK exists. Never when cloud is off.

## 🧪 Wild ideas (later, maybe)

- **Two models debate** — local model and a free cloud model answer the
  same prompt; you keep the better thread. (Both engines already exist.)
- **On-device RAG over your files** — embeddings GGUF + the attachment
  pipeline = "chat with this folder", fully offline.
- **Scheduled prompts** — "every morning, summarise yesterday's chat"
  running locally via WorkManager while charging.
- **Prompt library sync file** — a plain Markdown file in SAF storage as
  the source of truth for skills/personas, editable from a PC.
