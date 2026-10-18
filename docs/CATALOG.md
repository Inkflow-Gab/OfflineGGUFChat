# The model catalogue and the device-aware advisor

Discover ranks models against the handset it is running on. This document covers
the data format, the arithmetic, and how to extend either.

---

## 1. Why the catalogue is bundled

The app has **no `INTERNET` permission**. It cannot query the Hugging Face API,
cannot fetch a model list, and cannot download weights — not because those calls
are awkward, but because a permission it does not hold cannot be abused by a
future dependency.

So the shortlist is a compiled-in asset,
[`app/src/main/assets/catalog/models.json`](../app/src/main/assets/catalog/models.json),
scored locally against a probe of the device. Discover then hands the user a link
to open in their browser. The download is the browser's job, and the import back
into the app is a real file copy through the Storage Access Framework.

The asset is parsed by `CatalogStore` with
`ignoreUnknownKeys + isLenient + coerceInputValues`, and `parseOrNull` returns
null instead of throwing — a corrupt catalogue must not crash the app. Every
entry has a `fileHint`, so the primary link points at a specific file rather than
a repository tree.

## 2. Entry format

```json
{
  "id": "qwen2.5-3b-instruct-q4km",
  "repo": "Qwen/Qwen2.5-3B-Instruct-GGUF",
  "family": "Qwen2.5",
  "displayName": "Qwen2.5 3B Instruct",
  "paramsBillions": 3.09,
  "quant": "Q4_K_M",
  "approxSizeBytes": 1930000000,
  "contextLength": 32768,
  "blockCount": 36,
  "embeddingLength": 2048,
  "headCountKv": 2,
  "style": ["general", "chat", "analysis"],
  "languages": ["en", "zh"],
  "license": "Apache-2.0",
  "uncensored": false,
  "qualityScore": 55,
  "released": "2024-09",
  "filePattern": "*q4_k_m*.gguf",
  "fileHint": "qwen2.5-3b-instruct-q4_k_m.gguf",
  "notes": "The best all-round small model here: long context, strong at…"
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Unique key, `<family>-<size>-<variant>-<quant>`; also the local sort key |
| `repo` | string | `owner/name` on Hugging Face — the only thing URLs are built from |
| `family` | string | Architecture family, used for the search fallback URL |
| `displayName` | string | What the UI shows |
| `paramsBillions` | double | Parameter count in billions; `paramCount` derives the exact figure |
| `quant` | string | Quantisation label (`Q4_K_M`, `Q8_0`, …) |
| `approxSizeBytes` | long | Download size — drives the fit verdict *before* any download |
| `contextLength` | int | Trained context, the ceiling for `clampContext` |
| `blockCount`, `embeddingLength`, `headCountKv` | int | Shape, so the KV cache can be costed per token |
| `style` | string[] | Any of `general`, `code`, `chat`, `analysis`, `uncensored` |
| `languages` | string[] | BCP-47-ish tags |
| `license` | string | The *weight* licence, shown next to every entry |
| `uncensored` | bool | Refusal behaviour removed or reduced (see §4) |
| `qualityScore` | int | 0–100 editorial judgement of capability at this size |
| `released` | string | `YYYY-MM` |
| `filePattern` | string | Glob for the quant inside the repo |
| `fileHint` | string? | Exact filename → `resolve/main/<file>`; falls back to the tree |
| `notes` | string | One or two sentences of honest editorialising |

Top level: `catalogVersion` (int), `updated` (ISO date), `note` (string),
`entries` (array). Bump `catalogVersion` whenever the shape changes.

These are **estimates for a decision made before a download**. Once a file is on
the device, the real GGUF header replaces every one of them — architecture,
tensor count, parameter count, quantisation and context all come from the file
(see [`NATIVE.md`](NATIVE.md) §7).

## 3. What ships

18 entries, 356 MB to 4.92 GB, all Q4_K_M, `catalogVersion` 3.

| id | family | params | size | context | quality | styles |
| --- | --- | --- | --- | --- | --- | --- |
| `qwen2.5-0.5b-instruct-q4km` | Qwen2.5 | 0.49 B | 356 MB | 32 768 | 18 | general, chat |
| `tinyllama-1.1b-chat-q4km` | TinyLlama | 1.1 B | 669 MB | 4 096 | 20 | general, chat |
| `llama3.2-1b-instruct-q4km` | Llama 3.2 | 1.24 B | 752 MB | 131 072 | 34 | general, chat, analysis |
| `qwen2.5-1.5b-instruct-q4km` | Qwen2.5 | 1.54 B | 990 MB | 32 768 | 42 | general, chat, analysis |
| `qwen2.5-coder-1.5b-instruct-q4km` | Qwen2.5 Coder | 1.54 B | 990 MB | 32 768 | 45 | code |
| `gemma2-2b-it-q4km` | Gemma 2 | 2.6 B | 1 630 MB | 8 192 | 48 | general, chat |
| `llama3.2-3b-instruct-q4km` | Llama 3.2 | 3.21 B | 1 920 MB | 131 072 | 58 | general, chat, analysis |
| `qwen2.5-3b-instruct-q4km` | Qwen2.5 | 3.09 B | 1 930 MB | 32 768 | 55 | general, chat, analysis |
| `phi3.5-mini-instruct-q4km` | Phi 3.5 | 3.82 B | 2 250 MB | 131 072 | 62 | general, analysis, code |
| `wizardlm-7b-uncensored-q4km` † | WizardLM | 6.74 B | 4 080 MB | 4 096 | 58 | uncensored, general |
| `dolphin-mistral-7b-q4km` † | Dolphin (Mistral) | 7.25 B | 4 370 MB | 32 768 | 66 | uncensored, chat, general |
| `nous-hermes-2-mistral-7b-dpo-q4km` † | Nous Hermes 2 (Mistral) | 7.25 B | 4 370 MB | 32 768 | 69 | uncensored, chat, general |
| `mistral-7b-instruct-v0.3-q4km` | Mistral | 7.25 B | 4 370 MB | 32 768 | 70 | general, code, analysis |
| `openhermes-2.5-mistral-7b-q4km` † | OpenHermes (Mistral) | 7.25 B | 4 370 MB | 32 768 | 70 | uncensored, general, analysis |
| `qwen2.5-7b-instruct-abliterated-q4km` † | Qwen2.5 (abliterated) | 7.62 B | 4 680 MB | 131 072 | 71 | uncensored, general, analysis |
| `qwen2.5-7b-instruct-q4km` | Qwen2.5 | 7.62 B | 4 680 MB | 131 072 | 74 | general, code, analysis |
| `qwen2.5-coder-7b-instruct-q4km` | Qwen2.5 Coder | 7.62 B | 4 680 MB | 131 072 | 80 | code |
| `llama3.1-8b-instruct-q4km` | Llama 3.1 | 8.03 B | 4 920 MB | 131 072 | 78 | general, analysis, code |

† = `uncensored: true`. Licences across the set: Apache-2.0, MIT, Llama 3
community license, Gemma terms of use, and "See repository" where the upstream
page is the authority.

## 4. The uncensored category

Five entries are fine-tunes whose refusal behaviour has been removed or reduced —
Dolphin, OpenHermes 2.5, Nous Hermes 2 DPO, WizardLM Uncensored, and an
*abliterated* Qwen2.5 7B (refusal directions surgically removed from the weight
matrix rather than fine-tuned away).

How the app treats them:

- They are a **style**, not a hidden menu. `ModelStyle.UNCENSORED` ("Refusal-free")
  sits beside General / Code / Conversation / Analysis, and there is a separate
  `advisorUncensored` preference that filters to only these.
- Turning it on changes the ranking, and the entries carry a badge saying so.
  Nothing else changes: no extra permission, no network call, no gate.
- `notes` for each one states plainly what was removed and what that costs
  (abliterated models, for example, tend to lose a little instruction-following
  precision). The trade is the user's to make with real information.
- The app applies no output filtering of its own, in either direction. What the
  model generates is what the transcript shows.

They are all 7 B-class, 4.08–4.68 GB, so on a handset below roughly 12 GB of RAM
they come back as `WILL_NOT_FIT` and are pushed to the bottom of the list — the
advisor will not suggest something the phone cannot hold.

## 5. Reading the device

`DeviceProbe` builds a `DeviceProfile` from what the handset actually reports:

| Probe | Source |
| --- | --- |
| `ramBytes` | `ActivityManager.MemoryInfo.totalMem` |
| `javaHeapBytes` / `largeJavaHeapBytes` | `memoryClass` / `largeMemoryClass` |
| `cores` | `Runtime.availableProcessors()` |
| `maxCpuMhz` | highest readable `cpufreq/cpuinfo_max_freq` under `/sys/devices/system/cpu` |
| `abiList`, `apiLevel` | `Build` |
| `freeStorageBytes` / `totalStorageBytes` | `StatFs` on `/data` (`Environment.getDataDirectory()`) |
| `pageSizeBytes` | kernel page size — 4 096 or 16 384 |
| `lowRamDevice` | `ActivityManager.isLowRamDevice` |
| `engineAvailable` | whether the native engine was compiled in |

`DeviceProfile` is a plain data class with no Android types in it, so the fit
maths and the advisor are unit-testable on a JVM. `DeviceProfile.Unknown` is the
fallback for emulators and CI where probing returns nothing useful.

Tiers (`DeviceTier.of`):

| Tier | Rule | RAM fraction for models |
| --- | --- | --- |
| `LIGHT` | low-RAM device, or < 3 GB, or ≤ 4 cores | 0.45 |
| `MEDIUM` | < 6 GB | 0.52 |
| `STRONG` | < 10 GB | 0.58 |
| `FLAGSHIP` | everything else | 0.62 |

`modelRamBudgetBytes` = RAM × fraction, clamped to [512 MB, 14 GB]. Being
optimistic here is what gets an app killed mid-generation, so the fraction stays
well under 1: the OS, the launcher and whatever else is open have a claim too.
Derived suggestions: `suggestedThreads` = `cores − 2` clamped to [2, 4],
`suggestedBatch` = 128 below 4 GB else 512, and `suggestedContext` stepping
1 024 → 16 384 with RAM.

## 6. Will it fit

`ModelFit.assess` is the arithmetic behind every verdict.

- **Weights** cost `weightBytes × 1.12` (`MMAP_FACTOR` — mmap is not free) plus
  `RUNTIME_OVERHEAD` of 256 MB.
- **KV cache** costs `2 × layers × kvDim × bytesPerValue` per token, where
  `bytesPerValue` is 4 for f32, 2 for f16, 1.0625 for q8_0 and 0.5625 for q4_0.
  When the header does not give the shape, a per-parameter ratio is used instead:
  7e-5 for models ≥ 5 B, 1e-4 below.
- **Context** is halved repeatedly (floor 512) until the total fits, and the
  reduction is reported: *"Context dropped to N tokens to fit in available
  memory."* If it still does not fit, the KV type falls back to q8_0 and says so.
- **Pressure** = needed ÷ budget → `COMFORTABLE` ≤ 0.62, `WORKABLE` ≤ 0.85,
  `TIGHT` ≤ 1.0, else `WILL_NOT_FIT`. A file that cannot be read at all is
  `UNREADABLE`.
- **Speed** = bandwidth for the tier (6 / 11 / 18 / 26 GB/s) ÷ size in GB, times a
  thread factor clamped to [0.6, 1.15], result clamped to [0.3, 80] tok/s.

Every number above is pinned by `ModelFitTest` — including the awkward cases: a
3 500 MiB model on a 4 GB phone losing context to 1 024, and a q8_0 KV fallback
that still needs a note. If you change a constant, that test tells you which
verdicts moved.

## 7. Scoring

`ModelAdvisor.score` returns 0–100 from four normalised terms:

| Term | Normalisation |
| --- | --- |
| `quality` | `qualityScore / 100` |
| `speed` | `tokensPerSecond / 15`, clamped to [0, 1] — 15 tok/s reads as "fast enough", so more than that buys nothing |
| `headroom` | `1 − pressure` |
| `styleMatch` | 1.0 exact style match (or uncensored when asked for) · 0.55 also tagged `general` · 0.2 otherwise |

Weights depend on the *Prefer speed* preference:

| | quality | speed | headroom | style |
| --- | --- | --- | --- | --- |
| balanced | 0.42 | 0.26 | 0.20 | 0.12 |
| prefer speed | 0.22 | 0.45 | 0.20 | 0.13 |

Then two penalties, both of which exist to stop the advisor recommending
something the phone cannot actually take:

- `WILL_NOT_FIT` → × 0.12. Capability is only worth scoring once it fits; a
  3 GB handset should not be told to fetch 8 GB "because it is the best model".
- Free storage between 1 byte and 2× the download size → × 0.5. Importing is a
  real copy, so the file has to fit twice over, briefly.

Ranking (`recommend`): unfit models sort after fit ones, then by score
descending, then `take(limit)` and renumber from 1. `matches(prefs)` filters
first on the uncensored flag, the requested style, and the download ceiling.
`best` is the top of that list; `headline` renders it as one sentence starting
with the winner's display name.

## 8. Links, never downloads

```
modelCardUrl  https://huggingface.co/{repo}
repoUrl       https://huggingface.co/{repo}/tree/main
fileUrl(f)    https://huggingface.co/{repo}/resolve/main/{encode(f)}
primaryUrl    fileUrl(fileHint) when known, else repoUrl
searchUrl     https://huggingface.co/models?search={family}%20GGUF
```

`primaryUrl` aims at the exact file, because that is one tap instead of a browse.
`searchUrl` is the fallback that still works if a repository has moved or a quant
was renamed — searching by family outlives any single URL. Percent-encoding uses
`URLEncoder` with `+` replaced by `%20`.

## 9. Adding an entry

1. Append to `entries`. `id` must be unique; every field in §2 should be present.
2. `approxSizeBytes` must be the real file size — the fit verdict and the storage
   penalty both depend on it.
3. Set `blockCount`, `embeddingLength` and `headCountKv` if you want the KV cost
   computed from shape rather than from the per-parameter fallback.
4. `style` decides where it appears; add `uncensored` to the list *and* set
   `"uncensored": true` if it is a refusal-free fine-tune (they are kept in sync
   on purpose — the flag drives filtering, the style drives copy).
5. `notes` should say something a spec sheet does not, including the downside.
6. Bump `catalogVersion` and `updated`.
7. Run `./gradlew testDebugUnitTest`. `CatalogTest`'s
   `the shipped catalogue is well formed` reads the real asset from
   `src/main/assets/${CatalogStore.ASSET_PATH}` and fails if it is missing, then
   asserts: at least 12 entries, unique ids, `repo` contains a `/`, non-blank
   `displayName` / `license` / `notes`, positive `approxSizeBytes` and
   `paramsBillions`, `contextLength >= 2048`, `qualityScore` in 0..100, both URL
   shapes, at least 3 refusal-free entries, and — for 3 GB, 6 GB and 12 GB
   synthetic devices — that the top recommendation is not `WILL_NOT_FIT`. With the
   uncensored preference on a 12 GB device the top refusal-free pick must be
   usable. As of `catalogVersion` 3 the winners are Llama 3.2 1B (3 GB), Qwen2.5
   1.5B (6 GB), Llama 3.2 3B (12 GB) and OpenHermes 2.5 Mistral 7B (refusal-free).

   The rest of `CatalogTest` covers the advisor with synthetic models: unfit
   models rank last whatever their quality, the limit is honoured, the uncensored
   toggle and style agree, the download cap filters, reasons and badges are never
   empty, low storage and non-Apache licences are called out, an empty catalogue
   degrades instead of crashing, and bad JSON returns null.

## 10. Editing the advisor

`ModelAdvisor` and `ModelFit` are pure functions of `(DeviceProfile,
CatalogModel, Prefs)` with no Android dependencies, which is why they can be
tested exhaustively on a JVM. Keep it that way: anything that needs a `Context`
belongs in `DeviceProbe` or a repository, not here.
