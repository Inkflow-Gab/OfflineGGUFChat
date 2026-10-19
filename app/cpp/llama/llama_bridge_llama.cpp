// Real inference backend. Compiled only when OFFLINEGGUF_WITH_LLAMA=ON, and
// every llama.cpp call goes through llama_compat.h so this file does not have
// to track upstream renames directly.
#include "llama_bridge.h"
#include "llama_compat.h"
#include "util/utf8.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

namespace ogg {
namespace {

#if defined(OFFLINEGGUF_LLAMA_HAS_ABORT_CALLBACK)
bool StopRequested(void *user_data) {
    auto *flag = static_cast<std::atomic<bool> *>(user_data);
    return flag != nullptr && flag->load(std::memory_order_relaxed);
}
#endif

// Streaming UTF-8 accumulator: pieces are pushed as raw bytes and only whole
// code points cross JNI.
class ByteStream {
public:
    void Push(const char *data, size_t size) { buf_.append(data, size); }

    const char *Data() const { return buf_.data(); }
    size_t Size() const { return buf_.size(); }

    size_t Flushable() const { return CompleteUtf8Length(buf_.data(), buf_.size()); }

    void Consume(size_t n) {
        if (n >= buf_.size()) {
            buf_.clear();
            return;
        }
        buf_.erase(0, n);
    }

    bool empty() const { return buf_.empty(); }

private:
    std::string buf_;
};

}  // namespace

struct BridgeSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_adapter_lora *> adapters;
    compat::Tokens cached;  // tokens represented in the KV cache
    std::atomic<bool> running{false};
    std::atomic<bool> stop_requested{false};
    int32_t n_ctx = 0;
    int32_t n_batch = 512;
    std::string last_error;
};

bool BridgeAvailable() { return true; }

std::string BridgeEngineVersion() { return compat::EngineVersion(); }

BridgeSession *BridgeCreate() { return new BridgeSession(); }

void BridgeUnload(BridgeSession *s) {
    if (!s) return;
    for (auto *a : s->adapters) compat::FreeAdapter(a);
    s->adapters.clear();
    if (s->ctx) llama_free(s->ctx);
    if (s->model) compat::FreeModel(s->model);
    s->ctx = nullptr;
    s->model = nullptr;
    s->cached.clear();
    s->n_ctx = 0;
}

void BridgeDestroy(BridgeSession *session) {
    if (!session) return;
    BridgeUnload(session);
    delete session;
}

bool BridgeIsLoaded(BridgeSession *session) { return session && session->ctx && session->model; }

bool BridgeLoad(BridgeSession *s, const std::string &path, const BridgeLoadParams &params,
                std::string *error) {
    if (!s) return false;
    BridgeUnload(s);
    s->last_error.clear();

    auto fail = [&](const std::string &message) {
        s->last_error = message;
        if (error) *error = message;
        return false;
    };

    llama_model_params mp = compat::ModelParams();
    mp.n_gpu_layers = params.n_gpu_layers;
    compat::SetMmap(mp, params.use_mmap, params.use_mlock);

    s->model = compat::LoadModel(path, mp);
    if (!s->model) {
        return fail(
            "Could not load this model. The file may be incomplete, or it needs more memory "
            "than this device can map. Try a smaller quantisation (Q4_K_M) or a lower context.");
    }

    const int32_t train_ctx = compat::NCtxTrain(s->model);
    llama_context_params cp = compat::ContextParams();
    cp.n_ctx = static_cast<uint32_t>(
        train_ctx > 0 ? std::min<int32_t>(params.n_ctx, train_ctx) : params.n_ctx);
    cp.n_batch = static_cast<uint32_t>(std::max<int32_t>(
        8, std::min<int32_t>(params.n_batch, static_cast<int32_t>(cp.n_ctx))));
    compat::SetContextThreads(cp, params.n_threads);
#if defined(OFFLINEGGUF_LLAMA_HAS_ABORT_CALLBACK)
    cp.abort_callback = &StopRequested;
    cp.abort_callback_data = &s->stop_requested;
#endif

    s->ctx = compat::InitContext(s->model, cp);
    if (!s->ctx) {
        compat::FreeModel(s->model);
        s->model = nullptr;
        return fail("Out of memory allocating the KV cache for n_ctx=" +
                    std::to_string(cp.n_ctx) + ". Lower the context size and try again.");
    }

    s->n_ctx = static_cast<int32_t>(cp.n_ctx);
    s->n_batch = static_cast<int32_t>(cp.n_batch);

    for (const auto &lora : params.loras) {
        if (!compat::SupportsAdapters()) {
            s->last_error = "This llama.cpp build has no LoRA support; adapters were skipped.";
            continue;
        }
        llama_adapter_lora *adapter = compat::LoadAdapter(s->model, lora.first);
        if (adapter) {
            s->adapters.push_back(adapter);
        } else {
            s->last_error = "Failed to load LoRA adapter: " + lora.first;
        }
    }
    return true;
}

bool BridgeGenerate(BridgeSession *s, const std::string &prompt, const BridgeSampleParams &sp,
                    const BridgeCallbacks &callbacks, BridgeStats *stats_out) {
    if (!s || !s->ctx || !s->model) {
        if (callbacks.on_error) callbacks.on_error(callbacks.user, "No model is loaded.");
        return false;
    }

    bool expected = false;
    if (!s->running.compare_exchange_strong(expected, true)) {
        if (callbacks.on_error) {
            callbacks.on_error(callbacks.user, "A generation is already running.");
        }
        return false;
    }
    s->stop_requested.store(false);

    BridgeStats stats;
    const auto started = std::chrono::steady_clock::now();
    std::string error;
    llama_sampler *chain = nullptr;

    auto finish = [&](bool ok) {
        if (chain) compat::FreeSampler(chain);
        stats.microseconds = std::chrono::duration_cast<std::chrono::microseconds>(
                                 std::chrono::steady_clock::now() - started)
                                 .count();
        s->running.store(false);
        if (stats_out) *stats_out = stats;
        if (!ok) {
            s->last_error = error;
            if (callbacks.on_error && !error.empty()) {
                callbacks.on_error(callbacks.user, error.c_str());
            }
        } else if (callbacks.on_complete) {
            callbacks.on_complete(callbacks.user, stats);
        }
        return ok;
    };

    // ---- tokenize -----------------------------------------------------------
    compat::Tokens tokens = compat::Tokenize(s->model, prompt, /* add_bos */ true, /* special */ true);
    if (tokens.empty()) {
        error = "The prompt could not be tokenized (empty or unsupported characters).";
        return finish(false);
    }
    stats.prompt_tokens = static_cast<int32_t>(tokens.size());

    // Never ask the cache for more than it holds: keep the newest tokens, which
    // is what matters for a chat tail. (Older turns stay in the DB, not the KV.)
    if (static_cast<int32_t>(tokens.size()) > s->n_ctx - 1) {
        const size_t keep = static_cast<size_t>(std::max<int32_t>(1, s->n_ctx - 1));
        tokens.erase(tokens.begin(), tokens.begin() + (tokens.size() - keep));
        stats.truncated = true;
    }

    // ---- KV prefix reuse ----------------------------------------------------
    size_t reuse = 0;
    const size_t common = std::min(s->cached.size(), tokens.size());
    while (reuse < common && s->cached[reuse] == tokens[reuse]) ++reuse;
    if (reuse > 0 && !compat::RewindContext(s->ctx, static_cast<int32_t>(reuse))) {
        reuse = 0;
    }
    if (reuse == 0) {
        compat::ClearContext(s->ctx);
        s->cached.clear();
    } else {
        s->cached.resize(reuse);
    }

    // ---- sampler chain ------------------------------------------------------
    chain = compat::NewChain();
    if (sp.repeat_penalty != 1.0f || sp.presence_penalty != 0.0f || sp.frequency_penalty != 0.0f) {
        compat::AddPenalties(chain, compat::VocabSize(s->model), sp.repeat_last_n, sp.repeat_penalty,
                             sp.frequency_penalty, sp.presence_penalty);
    }
    if (sp.top_k > 1) llama_sampler_chain_add(chain, llama_sampler_init_top_k(sp.top_k));
    if (sp.typical_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_typical(sp.typical_p, 1));
    }
    if (sp.top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(sp.top_p, 1));
    if (sp.min_p > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(sp.min_p, 1));
    if (sp.temperature > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_temp(sp.temperature));
    const uint32_t seed =
        sp.seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(sp.seed) % (std::numeric_limits<uint32_t>::max() - 1) + 1;
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    if (!compat::AddGrammar(chain, s->model, sp.grammar, &error)) return finish(false);
    compat::ResetSampler(chain);

    auto decode = [&](const llama_token *data, int32_t n) -> bool {
        for (int32_t done = 0; done < n; done += s->n_batch) {
            if (s->stop_requested.load()) return true;  // partial is fine, we are aborting
            const int32_t chunk = std::min(s->n_batch, n - done);
            compat::Tokens slice(data, data + chunk);
            compat::BatchBuilder batch(slice.data(), chunk,
                                       static_cast<int32_t>(s->cached.size()));
            for (auto *adapter : s->adapters) batch.SetLora(adapter);
            if (compat::Decode(s->ctx, batch.Get()) != 0) return false;
            s->cached.insert(s->cached.end(), slice.begin(), slice.end());
        }
        return true;
    };

    // ---- prompt evaluation --------------------------------------------------
    if (reuse < tokens.size()) {
        const int32_t todo = static_cast<int32_t>(tokens.size() - reuse);
        if (!decode(tokens.data() + reuse, todo)) {
            error = "Failed to evaluate the prompt: the KV cache is full or the model is too "
                    "large for this device.";
            return finish(false);
        }
    }

    // ---- generation ---------------------------------------------------------
    ByteStream stream;
    const auto is_stop = [&](llama_token t) {
        if (compat::IsEndOfGeneration(s->model, t)) return true;
        return std::find(sp.stop_tokens.begin(), sp.stop_tokens.end(), t) != sp.stop_tokens.end();
    };

    int32_t generated = 0;
    while (generated < sp.n_predict) {
        if (s->stop_requested.load()) {
            stats.cancelled = true;
            break;
        }
        const llama_token token = compat::Sample(chain, s->ctx);
        if (!sp.ignore_eos && is_stop(token)) break;
        compat::Accept(chain, token);

        const std::string piece = compat::PieceToString(s->ctx, s->model, token);
        if (callbacks.on_token && !piece.empty()) {
            stream.Push(piece.data(), piece.size());
            const size_t flushable = stream.Flushable();
            if (flushable > 0) {
                callbacks.on_token(callbacks.user, stream.Data(), static_cast<int>(flushable));
                stream.Consume(flushable);
            }
        }

        ++generated;
        stats.tokens_generated = generated;

        if (!decode(&token, 1)) {
            stats.truncated = true;
            break;
        }
        if (s->cached.size() >= static_cast<size_t>(s->n_ctx)) {
            stats.truncated = true;
            break;
        }
    }

    // A trailing half code point would otherwise vanish from the answer.
    if (callbacks.on_token && !stream.empty()) {
        callbacks.on_token(callbacks.user, stream.Data(), static_cast<int>(stream.Size()));
    }

    stats.context_used = compat::ContextUsed(s->ctx);
    return finish(true);
}

void BridgeRequestStop(BridgeSession *s) {
    if (s) s->stop_requested.store(true);
}

bool BridgeIsBusy(BridgeSession *s) { return s && s->running.load(); }

void BridgeWaitIdle(BridgeSession *s, int64_t timeout_ms) {
    if (!s) return;
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(timeout_ms < 0 ? 30000 : timeout_ms);
    while (s->running.load()) {
        if (s->stop_requested.load() == false) s->stop_requested.store(true);
        if (std::chrono::steady_clock::now() > deadline) return;
        std::this_thread::yield();
    }
}

int32_t BridgeVocabSize(BridgeSession *s) { return s && s->model ? compat::VocabSize(s->model) : 0; }
int32_t BridgeNCtxTrain(BridgeSession *s) {
    return s && s->model ? compat::NCtxTrain(s->model) : 0;
}
int32_t BridgeContextSize(BridgeSession *s) {
    return s && s->ctx ? compat::ContextSize(s->ctx) : 0;
}
int32_t BridgeContextUsed(BridgeSession *s) {
    return s && s->ctx ? compat::ContextUsed(s->ctx) : 0;
}
int64_t BridgeParamCount(BridgeSession *s) { return s && s->model ? compat::ParamCount(s->model) : 0; }
int64_t BridgeWeightBytes(BridgeSession *s) {
    return s && s->model ? compat::WeightBytes(s->model) : 0;
}
std::string BridgeModelDescription(BridgeSession *s) {
    return s && s->model ? compat::ModelDescription(s->model) : "unknown";
}
std::string BridgeChatTemplate(BridgeSession *s) {
    return s && s->model ? compat::ChatTemplate(s->model) : std::string();
}
bool BridgeHasMultimodalProjector(BridgeSession *) {
    // Vision input lives in libmtmd, which this project does not link.
    return false;
}
bool BridgeSupportsAdapters(BridgeSession *) { return compat::SupportsAdapters(); }

void BridgeRewindContext(BridgeSession *s, int32_t keep_tokens) {
    if (!s || !s->ctx) return;
    if (keep_tokens <= 0) {
        compat::ClearContext(s->ctx);
        s->cached.clear();
        return;
    }
    if (static_cast<size_t>(keep_tokens) < s->cached.size()) {
        compat::RewindContext(s->ctx, keep_tokens);
        s->cached.resize(static_cast<size_t>(keep_tokens));
    }
}

const char *BridgeLastError(BridgeSession *s) {
    if (s && !s->last_error.empty()) return s->last_error.c_str();
    return "";
}

}  // namespace ogg
