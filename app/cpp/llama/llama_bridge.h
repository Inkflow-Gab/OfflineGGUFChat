// Backend-agnostic bridge between the JNI surface and llama.cpp.
//
// Two implementations are compiled by CMakeLists.txt:
//   llama_bridge_none.cpp    -> always reports "engine not compiled"
//   llama_bridge_llama.cpp   -> real llama.cpp, only when OFFLINEGGUF_WITH_LLAMA=ON
//
// Keeping the bridge behind this header means llama_jni.cpp compiles in both
// configurations, which is what keeps CI green on a fresh clone.
#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace ogg {

struct BridgeLoadParams {
    int32_t n_ctx = 2048;
    int32_t n_gpu_layers = 0;
    int32_t n_threads = 4;
    // Workers for prompt processing. 0 = derive from the core count, which is
    // what the app uses: prefill scales with every core it can borrow, while
    // n_threads stays on the performance cluster because decode does not.
    int32_t n_threads_batch = 0;
    int32_t n_batch = 512;
    bool use_mmap = true;
    bool use_mlock = false;
    // KV-cache element types ("f16", "bf16", "q8_0", "q4_0"). f16 is the
    // default; q8_0 halves the cache on phones that cannot afford f16.
    std::string kv_type_k = "f16";
    std::string kv_type_v = "f16";
    // LoRA adapters applied on top of the base model: (path, scale).
    std::vector<std::pair<std::string, float>> loras;
};

struct BridgeSampleParams {
    int32_t n_predict = 256;
    float temperature = 0.7f;
    int32_t top_k = 40;
    float top_p = 0.9f;
    float min_p = 0.05f;
    float typical_p = 1.0f;
    float repeat_penalty = 1.1f;
    int32_t repeat_last_n = 64;
    float presence_penalty = 0.0f;
    float frequency_penalty = 0.0f;
    int32_t seed = -1;
    bool ignore_eos = false;
    std::vector<int32_t> stop_tokens;  // extra token ids that end generation
    std::string grammar;               // GBNF text; empty = unconstrained
};

struct BridgeStats {
    int32_t tokens_generated = 0;
    int32_t prompt_tokens = 0;
    int32_t context_used = 0;
    int64_t microseconds = 0;
    bool truncated = false;
    bool cancelled = false;

    // ---- latency breakdown -------------------------------------------------
    // The three numbers that separate "the prompt was expensive" from "the
    // model is rambling": tokenization, prompt evaluation, and the wait for
    // the first generated token. All measured on the generating thread.
    int64_t tokenize_micros = 0;
    int64_t prefill_micros = 0;
    /// Start of generation -> first piece handed to the callback.
    int64_t first_token_micros = 0;
    /// Sum of the per-token decode steps (the steady-state cost).
    int64_t decode_micros = 0;
    int32_t prefill_tokens = 0;   // prompt tokens actually evaluated this run
    int32_t reused_tokens = 0;    // prompt tokens served from the KV cache
    int32_t decode_calls = 0;     // llama_decode invocations (prefill excluded)
    int32_t n_ctx = 0;
    int32_t n_threads = 0;
    int32_t n_batch = 0;
    int32_t n_threads_batch = 0;
};

// Invoked from the generating thread. `bytes` is raw UTF-8 for one tokenizer
// piece and may end mid-code-point; the Java side reassembles it.
struct BridgeCallbacks {
    void *user = nullptr;
    void (*on_token)(void *user, const char *bytes, int len) = nullptr;
    void (*on_complete)(void *user, const BridgeStats &stats) = nullptr;
    void (*on_error)(void *user, const char *message) = nullptr;
};

struct BridgeSession;

bool BridgeAvailable();
std::string BridgeEngineVersion();

BridgeSession *BridgeCreate();
void BridgeDestroy(BridgeSession *session);

bool BridgeLoad(BridgeSession *session, const std::string &path, const BridgeLoadParams &params,
                std::string *error);
void BridgeUnload(BridgeSession *session);
bool BridgeIsLoaded(BridgeSession *session);

bool BridgeGenerate(BridgeSession *session, const std::string &prompt,
                    const BridgeSampleParams &params, const BridgeCallbacks &callbacks,
                    BridgeStats *stats);
void BridgeRequestStop(BridgeSession *session);

// True while a generation is in flight on this session.
bool BridgeIsBusy(BridgeSession *session);
// Blocks until the in-flight generation finishes (or `timeout_ms` elapses).
// Used by destroy() so a model is never freed underneath a running decode.
void BridgeWaitIdle(BridgeSession *session, int64_t timeout_ms);

// Metadata the engine knows better than the Kotlin GGUF reader does.
int32_t BridgeVocabSize(BridgeSession *session);
int32_t BridgeNCtxTrain(BridgeSession *session);
int32_t BridgeContextSize(BridgeSession *session);
int32_t BridgeContextUsed(BridgeSession *session);
int64_t BridgeParamCount(BridgeSession *session);
int64_t BridgeWeightBytes(BridgeSession *session);
std::string BridgeModelDescription(BridgeSession *session);
std::string BridgeChatTemplate(BridgeSession *session);
bool BridgeHasMultimodalProjector(BridgeSession *session);
bool BridgeSupportsAdapters(BridgeSession *session);

// Forget cached KV beyond `keep_tokens` so the next prompt can reuse the
// prefix. `keep_tokens <= 0` drops everything.
void BridgeRewindContext(BridgeSession *session, int32_t keep_tokens);

const char *BridgeLastError(BridgeSession *session);

}  // namespace ogg
