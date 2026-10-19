# The model catalogue and the device-aware advisor

Discover ranks models against the handset it is running on. This document covers
the data format, the arithmetic, and how to extend either.

---

## 1. Why the catalogue is bundled

Discovery is **offline by design**. The app holds an `INTERNET` permission only
for the opt-in Cloud tab (the user's own API key); this catalogue never uses it.
It does not query the Hugging Face API, fetch a model list, or download weights —
not because those calls are awkward, but because a feature that cannot phone
home cannot leak what you are browsing for.

So the shortlist is a compiled-in asset,
[`app/src/main/assets/catalog/models.json`](../app/src/main/assets/catalog/models.json),
scored locally against a probe of the device. Discover then hands the user a link
to open in their browser. The download is the browser's job, and the import back
into the app is a real file copy through the Storage Access Framework.

The asset is parsed by `CatalogStore` with
`ignoreUnknownKeys + isLenient + coerceInputValues`, and `parseOrNull` returns
null instead of throwing — a corrupt catalogue must not crash the app. Entries
with a `fileHint` deep-link to the exact GGUF file; the rest link to the repo
tree, which stays correct even when a quant file is renamed upstream.

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
| `style` | string[] | Any of `general`, `code`, `chat`, `analysis`, `reasoning`, `roleplay`, `uncensored` |
| `languages` | string[] | BCP-47-ish tags |
| `license` | string | The *weight* licence, shown next to every entry |
| `uncensored` | bool | Refusal behaviour removed or reduced (see §4) |
| `maturity` | string | `general` (default) or `mature`; `mature` (or `uncensored`) puts the entry behind the 18+ confirmation |
| `reasoning` | bool | Emits a visible thinking trace; badged `Thinking` and matched by the Reasoning style |
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

38 entries, 229 MB to 5.02 GB, all Q4_K_M, `catalogVersion` 4 (13 refusal-free, 7 reasoning).

| id | family | params | size | context | quality | styles |
| --- | --- | --- | --- | --- | --- | --- |
| `smollm2-360m-instruct-q4km` | SmolLM2 | 0.36 B | 229 MB | 8 192 | 16 | general, chat |
| `qwen2.5-0.5b-instruct-q4km` | Qwen2.5 | 0.49 B | 356 MB | 32 768 | 18 | general, chat |
| `qwen3-0.6b-q4km` | Qwen3 | 0.6 B | 397 MB | 32 768 | 30 | general, chat, reasoning |
| `tinyllama-1.1b-chat-q4km` | TinyLlama | 1.1 B | 669 MB | 4 096 | 20 | general, chat |
| `llama3.2-1b-instruct-q4km` | Llama 3.2 | 1.24 B | 752 MB | 131 072 | 34 | general, chat, analysis |
| `llama3.2-1b-abliterated-q4km` | Llama 3.2 Abliterated | 1.24 B | 752 MB | 131 072 | 32 | uncensored, chat, roleplay |
| `gemma3-1b-it-q4km` | Gemma 3 | 1.0 B | 762 MB | 32 768 | 36 | general, chat |
| `qwen2.5-1.5b-instruct-q4km` | Qwen2.5 | 1.54 B | 990 MB | 32 768 | 42 | general, chat, analysis |
| `qwen2.5-coder-1.5b-instruct-q4km` | Qwen2.5 Coder | 1.54 B | 990 MB | 32 768 | 45 | code |
| `qwen2.5-1.5b-abliterated-q4km` | Qwen2.5 Abliterated | 1.54 B | 990 MB | 32 768 | 40 | uncensored, chat, general, roleplay |
| `smollm2-1.7b-instruct-q4km` | SmolLM2 | 1.71 B | 1.06 GB | 8 192 | 44 | general, chat, code |
| `qwen3-1.7b-q4km` | Qwen3 | 1.72 B | 1.11 GB | 32 768 | 52 | general, chat, analysis, reasoning |
| `deepseek-r1-distill-qwen-1.5b-q4km` | DeepSeek R1 Distill | 1.54 B | 1.12 GB | 32 768 | 54 | reasoning, analysis, code |
| `gemma2-2b-it-q4km` | Gemma 2 | 2.6 B | 1.63 GB | 8 192 | 48 | general, chat |
| `qwen2.5-coder-3b-instruct-q4km` | Qwen2.5 Coder | 3.09 B | 1.90 GB | 32 768 | 62 | code, analysis |
| `llama3.2-3b-instruct-q4km` | Llama 3.2 | 3.21 B | 1.92 GB | 131 072 | 58 | general, chat, analysis |
| `qwen2.5-3b-instruct-q4km` | Qwen2.5 | 3.09 B | 1.93 GB | 32 768 | 55 | general, chat, analysis |
| `qwen2.5-3b-abliterated-q4km` | Qwen2.5 Abliterated | 3.09 B | 1.93 GB | 32 768 | 53 | uncensored, general, analysis, roleplay |
| `llama3.2-3b-abliterated-q4km` | Llama 3.2 Abliterated | 3.21 B | 2.02 GB | 131 072 | 55 | uncensored, chat, roleplay, general |
| `dolphin3-llama3.2-3b-q4km` | Dolphin 3.0 | 3.21 B | 2.02 GB | 131 072 | 57 | uncensored, chat, roleplay, code |
| `phi3.5-mini-instruct-q4km` | Phi 3.5 | 3.82 B | 2.25 GB | 131 072 | 62 | general, analysis, code |
| `phi4-mini-instruct-q4km` | Phi-4 mini | 3.84 B | 2.49 GB | 131 072 | 66 | general, analysis, code, reasoning |
| `qwen3-4b-q4km` | Qwen3 | 4.02 B | 2.50 GB | 32 768 | 72 | general, chat, analysis, code, reasoning |
| `gemma3-4b-it-q4km` | Gemma 3 | 4.3 B | 2.63 GB | 131 072 | 68 | general, chat, analysis |
| `wizardlm-7b-uncensored-q4km` | WizardLM | 6.74 B | 4.08 GB | 4 096 | 58 | uncensored, general |
| `mistral-7b-instruct-v0.3-q4km` | Mistral | 7.25 B | 4.37 GB | 32 768 | 70 | general, code, analysis |
| `dolphin-mistral-7b-q4km` | Dolphin (Mistral) | 7.25 B | 4.37 GB | 32 768 | 66 | uncensored, chat, general, roleplay |
| `openhermes-2.5-mistral-7b-q4km` | OpenHermes (Mistral) | 7.25 B | 4.37 GB | 32 768 | 70 | uncensored, general, analysis |
| `nous-hermes-2-mistral-7b-dpo-q4km` | Nous Hermes 2 (Mistral) | 7.25 B | 4.37 GB | 32 768 | 69 | uncensored, chat, general, roleplay |
| `dolphin3-mistral-7b-q4km` | Dolphin 3.0 | 7.25 B | 4.37 GB | 32 768 | 70 | uncensored, chat, general, roleplay, code |
| `qwen2.5-7b-instruct-q4km` | Qwen2.5 | 7.62 B | 4.68 GB | 131 072 | 74 | general, code, analysis |
| `qwen2.5-coder-7b-instruct-q4km` | Qwen2.5 Coder | 7.62 B | 4.68 GB | 131 072 | 80 | code |
| `qwen2.5-7b-instruct-abliterated-q4km` | Qwen2.5 (abliterated) | 7.62 B | 4.68 GB | 131 072 | 71 | uncensored, general, analysis |
| `deepseek-r1-distill-qwen-7b-q4km` | DeepSeek R1 Distill | 7.62 B | 4.68 GB | 32 768 | 77 | reasoning, analysis, code |
| `llama3.1-8b-instruct-q4km` | Llama 3.1 | 8.03 B | 4.92 GB | 131 072 | 78 | general, analysis, code |
| `llama3.1-8b-abliterated-q4km` | Llama 3.1 Abliterated | 8.03 B | 4.92 GB | 131 072 | 74 | uncensored, general, chat, roleplay |
| `l3-8b-stheno-v3.2-q4km` | Stheno | 8.03 B | 4.92 GB | 8 192 | 71 | roleplay, chat, uncensored |
| `qwen3-8b-q4km` | Qwen3 | 8.19 B | 5.02 GB | 32 768 | 83 | general, chat, analysis, code, reasoning |
