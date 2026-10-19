// Single place that absorbs llama.cpp's C API churn.
//
// CMake probes the supplied llama.cpp checkout (see check_cxx_source_compiles
// calls in CMakeLists.txt) and defines OFFLINEGGUF_LLAMA_HAS_* for whatever it
// finds. Everything the bridge needs then goes through the wrappers below, so
// llama_bridge_llama.cpp stays readable and one pin bump does not cascade.
//
// The "modern" branch of each macro pair matches llama.cpp b11719, the ref
// recorded in llama-cpp.version. Fallbacks target older trees.
#pragma once

#include <llama.h>
#include <ggml.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

namespace ogg {
namespace compat {

using Tokens = std::vector<llama_token>;

inline llama_model_params ModelParams() { return llama_model_default_params(); }

inline llama_context_params ContextParams() { return llama_context_default_params(); }

// --- loading -----------------------------------------------------------------
inline llama_model *LoadModel(const std::string &path, llama_model_params &p) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MODEL_LOAD_FROM_FILE)
    return llama_model_load_from_file(path.c_str(), p);
#else
    return llama_load_model_from_file(path.c_str(), p);
#endif
}

inline void FreeModel(llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MODEL_FREE)
    llama_model_free(model);
#else
    llama_free_model(model);
#endif
}

inline void SetMmap(llama_model_params &p, bool mmap, bool mlock) {
#if defined(OFFLINEGGUF_LLAMA_HAS_LOAD_MODE)
    // b11719 replaced the two booleans with a single load_mode enum.
    llama_load_mode mode = LLAMA_LOAD_MODE_AUTO;
    if (mmap && mlock) mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    else if (mmap) mode = LLAMA_LOAD_MODE_MMAP;
    else if (mlock) mode = LLAMA_LOAD_MODE_MLOCK;
    else mode = LLAMA_LOAD_MODE_NONE;
    p.load_mode = mode;
#else
    p.use_mmap = mmap ? 1 : 0;
    p.use_mlock = mlock ? 1 : 0;
#endif
}

inline llama_context *InitContext(llama_model *model, llama_context_params &p) {
#if defined(OFFLINEGGUF_LLAMA_HAS_INIT_FROM_MODEL)
    return llama_init_from_model(model, p);
#else
    return llama_new_context_with_model(model, p);
#endif
}

/**
 * KV-cache element types. Quantised K/V cuts the cache in half or better on a
 * memory-tight phone, which is the difference between the weights staying
 * resident and being paged back in on every token.
 */
inline void SetKvCacheType(llama_context_params &p, const std::string &type_k,
                           const std::string &type_v) {
#if defined(OFFLINEGGUF_LLAMA_HAS_KV_TYPE)
    auto parse = [](const std::string &name, ggml_type fallback) {
        if (name == "q8_0") return GGML_TYPE_Q8_0;
        if (name == "q4_0") return GGML_TYPE_Q4_0;
        if (name == "q4_1") return GGML_TYPE_Q4_1;
        if (name == "q5_0") return GGML_TYPE_Q5_0;
        if (name == "q5_1") return GGML_TYPE_Q5_1;
        if (name == "bf16") return GGML_TYPE_BF16;
        if (name == "f32") return GGML_TYPE_F32;
        if (name == "f16") return GGML_TYPE_F16;
        return fallback;
    };
    p.type_k = parse(type_k, GGML_TYPE_F16);
    p.type_v = parse(type_v, GGML_TYPE_F16);
#else
    (void)p;
    (void)type_k;
    (void)type_v;
#endif
}

inline void SetContextThreads(llama_context_params &p, int32_t threads) {
#if defined(OFFLINEGGUF_LLAMA_HAS_CTX_THREADS)
    p.n_threads = threads;
    p.n_threads_batch = threads > 0 ? threads : p.n_threads_batch;
#else
    (void)p;
    (void)threads;  // newer builds configure threads on the CPU backend
#endif
}

// --- vocabulary --------------------------------------------------------------
#if defined(OFFLINEGGUF_LLAMA_HAS_VOCAB_API)
inline const llama_vocab *Vocab(const llama_model *model) { return llama_model_get_vocab(model); }
#else
inline const llama_model *Vocab(const llama_model *model) { return model; }
#endif

inline int32_t VocabSize(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_VOCAB_API)
    return llama_vocab_n_tokens(Vocab(model));
#else
    return llama_n_vocab(model);
#endif
}

inline int32_t NCtxTrain(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MODEL_N_CTX_TRAIN)
    return llama_model_n_ctx_train(model);
#else
    return llama_n_ctx_train(model);
#endif
}

inline int64_t ParamCount(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MODEL_N_PARAMS)
    return static_cast<int64_t>(llama_model_n_params(model));
#else
    return 0;
#endif
}

inline int64_t WeightBytes(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MODEL_SIZE)
    return static_cast<int64_t>(llama_model_size(model));
#else
    return 0;
#endif
}

inline std::string ModelDescription(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MODEL_DESC)
    char buf[512];
    const int32_t n = llama_model_desc(model, buf, sizeof(buf));
    if (n > 0) return std::string(buf, static_cast<size_t>(std::min<int32_t>(n, 511)));
#else
    (void)model;
#endif
    return "unknown";
}

inline std::string ChatTemplate(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_CHAT_TEMPLATE)
    const char *t = llama_model_chat_template(model, nullptr);
    return t ? std::string(t) : std::string();
#else
    (void)model;
    return std::string();
#endif
}

// --- tokenize / detokenize ---------------------------------------------------
inline Tokens Tokenize(const llama_model *model, const std::string &text, bool add_bos,
                       bool special) {
    if (text.empty()) return Tokens{};
    // A token never spans fewer bytes than one UTF-8 char, so byte length is a
    // safe upper bound; the margin covers the BOS token and split specials.
    Tokens out(text.size() + 16, LLAMA_TOKEN_NULL);
    const int32_t n = llama_tokenize(Vocab(model), text.data(), static_cast<int32_t>(text.size()),
                                     out.data(), static_cast<int32_t>(out.size()), add_bos, special);
    if (n < 0) {
        // Buffer too small: llama.cpp reports the required size as -n.
        out.assign(static_cast<size_t>(-n), LLAMA_TOKEN_NULL);
        const int32_t retry = llama_tokenize(Vocab(model), text.data(),
                                             static_cast<int32_t>(text.size()), out.data(),
                                             static_cast<int32_t>(out.size()), add_bos, special);
        if (retry < 0) {
            out.clear();
            return out;
        }
        out.resize(static_cast<size_t>(retry));
        return out;
    }
    out.resize(static_cast<size_t>(n));
    return out;
}

inline std::string PieceToString(const llama_context *ctx, const llama_model *model,
                                  llama_token token) {
    char buf[128];
    // Modern builds take the vocab, older ones the context.
#if defined(OFFLINEGGUF_LLAMA_HAS_TOKEN_TO_PIECE_LSTRIP)
    const int n = llama_token_to_piece(Vocab(model), token, buf, sizeof(buf), 0, false);
#elif defined(OFFLINEGGUF_LLAMA_HAS_VOCAB_API)
    const int n = llama_token_to_piece(Vocab(model), token, buf, sizeof(buf), 0);
#else
    const int n = llama_token_to_piece(ctx, token, buf, sizeof(buf), 0);
#endif
    if (n <= 0) return std::string();
    return std::string(buf, static_cast<size_t>(std::min(n, static_cast<int>(sizeof(buf)))));
}

inline bool IsEndOfGeneration(const llama_model *model, llama_token token) {
#if defined(OFFLINEGGUF_LLAMA_HAS_VOCAB_IS_EOG)
    return llama_vocab_is_eog(Vocab(model), token);
#else
    return token == llama_token_eos(model);
#endif
}

inline bool AddBosToken(const llama_model *model) {
#if defined(OFFLINEGGUF_LLAMA_HAS_VOCAB_GET_ADD_BOS)
    return llama_vocab_get_add_bos(Vocab(model));
#else
    return true;
#endif
}

// --- context / KV ------------------------------------------------------------
inline void ClearContext(llama_context *ctx) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MEMORY_API)
    llama_memory_clear(llama_get_memory(ctx), false);
#elif defined(OFFLINEGGUF_LLAMA_HAS_KV_SELF_CLEAR)
    llama_kv_self_clear(ctx);
#else
    llama_kv_cache_clear(ctx);
#endif
}

// Drops cached state for tokens beyond `keep` so the next prompt reuses the
// shared prefix. Returns false when the backend cannot do partial removal.
inline bool RewindContext(llama_context *ctx, int32_t keep) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MEMORY_SEQ_RM)
    return llama_memory_seq_rm(llama_get_memory(ctx), 0, keep, -1);
#elif defined(OFFLINEGGUF_LLAMA_HAS_KV_SELF_SEQ_RM)
    return llama_kv_self_seq_rm(ctx, 0, keep, -1);
#else
    (void)ctx;
    (void)keep;
    return false;
#endif
}

inline int32_t ContextUsed(const llama_context *ctx) {
#if defined(OFFLINEGGUF_LLAMA_HAS_MEMORY_SEQ_POS_MAX)
    const llama_pos max_pos = llama_memory_seq_pos_max(llama_get_memory(const_cast<llama_context *>(ctx)), 0);
    return max_pos < 0 ? 0 : static_cast<int32_t>(max_pos + 1);
#else
    (void)ctx;
    return 0;
#endif
}

inline int32_t ContextSize(const llama_context *ctx) { return static_cast<int32_t>(llama_n_ctx(ctx)); }

// --- sampling ----------------------------------------------------------------
inline llama_sampler *NewChain() {
    return llama_sampler_chain_init(llama_sampler_chain_default_params());
}

inline void AddPenalties(llama_sampler *chain, int32_t n_vocab, int32_t last_n, float repeat,
                         float frequency, float presence) {
#if defined(OFFLINEGGUF_LLAMA_HAS_SAMPLER_PENALTIES)
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(n_vocab, last_n, repeat, frequency,
                                                                presence));
#else
    (void)chain;
    (void)n_vocab;
    (void)last_n;
    (void)repeat;
    (void)frequency;
    (void)presence;  // this llama.cpp build has no penalty sampler in the public API
#endif
}

inline bool AddGrammar(llama_sampler *chain, const llama_model *model, const std::string &grammar,
                       std::string *error) {
    if (grammar.empty()) return true;
#if defined(OFFLINEGGUF_LLAMA_HAS_SAMPLER_GRAMMAR)
    llama_sampler *g = llama_sampler_init_grammar(Vocab(model), grammar.c_str(), "root");
    if (!g) {
        if (error) *error = "GBNF grammar failed to parse";
        return false;
    }
    llama_sampler_chain_add(chain, g);
    return true;
#else
    (void)chain;
    (void)model;
    (void)error;
    return true;  // no grammar support -> ignore rather than fail the request
#endif
}

inline llama_token Sample(llama_sampler *sampler, llama_context *ctx) {
    return llama_sampler_sample(sampler, ctx, -1);
}

inline void Accept(llama_sampler *sampler, llama_token token) { llama_sampler_accept(sampler, token); }
inline void ResetSampler(llama_sampler *sampler) { llama_sampler_reset(sampler); }
inline void FreeSampler(llama_sampler *sampler) { llama_sampler_free(sampler); }

// --- batching ----------------------------------------------------------------
// Positions are tracked by llama_decode in llama_batch_get_one builds; older
// ones need them spelled out.
class BatchBuilder {
public:
    BatchBuilder(const llama_token *tokens, int32_t n, int32_t position) : n_(n) {
#if defined(OFFLINEGGUF_LLAMA_HAS_BATCH_GET_ONE)
        batch_ = llama_batch_get_one(const_cast<llama_token *>(tokens), n);
        (void)position;
#else
        batch_ = llama_batch_init(n, 0, 1);
        for (int32_t i = 0; i < n; ++i) {
            batch_.token[i] = tokens[i];
            batch_.pos[i] = position + i;
            batch_.seq_id[i] = 0;
            batch_.logits[i] = (i == n - 1) ? 1 : 0;
        }
        batch_.n_tokens = n;
#endif
    }

    ~BatchBuilder() {
#if !defined(OFFLINEGGUF_LLAMA_HAS_BATCH_GET_ONE)
        llama_batch_free(batch_);
#endif
    }

    llama_batch &Get() { return batch_; }

    void SetLora(llama_adapter_lora * /*adapter*/) {}

private:
    BatchBuilder(const BatchBuilder &) = delete;
    BatchBuilder &operator=(const BatchBuilder &) = delete;
    llama_batch batch_{};
    int32_t n_ = 0;
};

inline int Decode(llama_context *ctx, llama_batch &batch) { return llama_decode(ctx, batch); }

// --- LoRA --------------------------------------------------------------------
struct AdapterHandle {
    llama_adapter_lora *adapter = nullptr;
    float scale = 1.0f;
};

inline bool SupportsAdapters() {
#if defined(OFFLINEGGUF_LLAMA_HAS_ADAPTER_LORA)
    return true;
#else
    return false;
#endif
}

inline llama_adapter_lora *LoadAdapter(llama_model *model, const std::string &path) {
#if defined(OFFLINEGGUF_LLAMA_HAS_ADAPTER_LORA)
    return llama_adapter_lora_init(model, path.c_str());
#else
    (void)model;
    (void)path;
    return nullptr;
#endif
}

inline void FreeAdapter(llama_adapter_lora *adapter) {
#if defined(OFFLINEGGUF_LLAMA_HAS_ADAPTER_LORA)
    llama_adapter_lora_free(adapter);
#else
    (void)adapter;
#endif
}

// --- misc --------------------------------------------------------------------
inline std::string EngineVersion() {
    std::string out = "llama.cpp";
#if defined(OFFLINEGGUF_LLAMA_HAS_PRINT_SYSTEM_INFO)
    const char *info = llama_print_system_info();
    if (info) out += std::string(" (") + info + ")";
#endif
    return out;
}

inline int32_t MaxContextBatch(const llama_context *ctx) {
    (void)ctx;
    return 512;
}

}  // namespace compat
}  // namespace ogg
