# Product design and performance plan

## Product promise

Offline GGUF Chat should feel like a small, fast chat workspace, not a cloud service with a broken network connection. Model weights, transcripts, personas, and code stay local. The interface must be honest about uncertainty, device limits, and what is merely an estimate.

"No wrong answers" and literally unlimited output cannot be guaranteed by a local language model. The product goal is to make errors less likely and easier to spot, and to let a user continue long work in bounded chunks without pretending the device has infinite context or memory.

## Delivered in this branch

- **Fast first use:** mode-specific starter prompts make the empty chat actionable. Fast mode limits output length; Coding and Deep thinking favor lower-temperature sampling.
- **Faster long conversations:** `PromptWindow` estimates a safe prompt budget from the loaded context and reserves room for output. It keeps recent history, marks omissions, clips an oversized newest turn with a visible marker, and never deletes the saved transcript.
- **Low-memory protection:** thread count, prompt batch, and requested context are reassessed against the current device before native model loading. Low-RAM phones use one inference worker and a smaller prompt batch.
- **Long-form answers:** an answer that reaches its per-pass output cap can continue in the same message. Each pass is bounded; the native model context and available memory remain the final limit.
- **Code workspace:** fenced blocks show filename, language and line count, collapse after 50 lines, copy to clipboard, and save through Android's document picker.
- **Personas and modes:** persistent chat modes and editable custom skills support task roles or fictional speaking styles. The model catalogue exposes its refusal-free entries without claiming they are certified or guaranteed to behave a particular way.
- **Presentation:** refreshed adaptive launcher mark, calmer mode-aware quick starts, collapsible reasoning, and progressively streamed text.

## Next improvements, in priority order

### P1 — make speed measurable and adaptive

1. Record prompt tokenization/evaluation time separately from output tokens/second. Show **time to first token** so slow model startup is distinguishable from slow generation.
2. Add a user-selectable **Auto / Low-memory / Faster** resource profile. Auto is the default; faster can raise prompt batch only when the fit assessment has safe RAM headroom.
3. Use Android memory-pressure callbacks to stop background scans, release unused engine buffers, and offer a safe context reduction before the process is killed.
4. Benchmark representative model sizes on a small set of phones; calibrate the current bandwidth estimates rather than presenting them as guarantees.

### P1 — improve correctness without faking certainty

1. Keep Deep thinking as an optional slower mode. A future opt-in **Review answer** pass can check arithmetic, code consistency, and contradictions against the visible prompt; label it as a same-model review, not independent verification.
2. Offer copyable uncertainty/source notes and clearly distinguish information supplied by the user from model-generated claims. Do not fabricate citations or imply internet checking in an offline app.
3. Add deterministic regression prompts for skills, attachments, continuation, code fences, and context-window trimming. Track whether changes improve results without pretending a small test set proves accuracy.

### P2 — better long-session workflow

1. Add a context dashboard showing estimated prompt tokens, output reserve, omitted turns, and the exact reason for a shortened prompt.
2. Offer an explicit local conversation summary that the user can inspect and edit before it replaces older turns in a prompt. Keep the original transcript untouched.
3. Add searchable/exportable persona packs and a per-chat persona selector, with a clear indication of which system prompt is active.
4. Add code-file metadata for multiple files in one answer and a preview of the exact filename/content before saving.

### P2 — accessible, resilient UI

1. Add an optional compact transcript layout for narrow screens, font-size controls for code, and accessible labels for every icon action.
2. Restore generation state cleanly after rotation/process recreation, including the ability to continue a capped answer when its saved state proves that it is safe to do so.
3. Add a low-storage warning before model imports and a cleanup screen that never removes a model without confirmation.

## Constraints to keep visible

- Small quantized models can run on weak phones, but their language ability is still smaller and slower models may take a long time. Picking a smaller model is a capability trade-off, not a software optimization that can be hidden.
- Context length, output limits, thermal throttling, free RAM, and Android process management are physical limits. Chunked continuation improves workflow; it does not create infinite memory.
- Refusal-free is a fine-tune characteristic, not a content rating, quality label, or accuracy guarantee. Model files are user-imported and are not bundled or downloaded by the app.
- A model reviewing its own answer can repeat the same mistake. Any future review feature must say so in the UI.
