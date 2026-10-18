#include "llama.h"
#include <cstring>
#include <vector>

// ---------------------------------------------------------------------------
// STUB IMPLEMENTATION — replace with real llama.cpp from ggml-org/llama.cpp
// ---------------------------------------------------------------------------

llama_model * llama_load_model_from_file(const char * /*path*/, llama_model_params /*params*/) {
    // Real implementation: memory-map GGUF, parse tensors, allocate model.
    return nullptr;
}

void llama_free_model(llama_model * /*model*/) {}

llama_context * llama_new_context_with_model(llama_model * /*model*/, llama_context_params /*params*/) {
    return nullptr;
}

void llama_free(llama_context * /*ctx*/) {}

int32_t llama_n_vocab(const llama_model * /*model*/) { return 0; }
int32_t llama_n_ctx_train(const llama_model * /*model*/) { return 0; }
const char * llama_arch_name(const llama_model * /*model*/) { return "unknown"; }
int32_t llama_model_has_vision(const llama_model * /*model*/) { return 0; }

std::vector<llama_token> llama_tokenize(const llama_model * /*model*/, const char * /*text*/, bool /*add_bos*/) {
    return {};
}

int32_t llama_decode(llama_context * /*ctx*/, const llama_token * /*tokens*/, int32_t /*n_tokens*/, int32_t /*n_past*/) {
    return -1;
}

llama_token llama_sample_token(llama_context * /*ctx*/, const llama_sampling_params * /*params*/, void * /*candidates*/) {
    return LLAMA_TOKEN_NONE;
}

llama_token llama_token_eos(const llama_model * /*model*/) { return LLAMA_TOKEN_EOS; }

int32_t llama_token_to_piece(llama_context * /*ctx*/, llama_token /*token*/, char * /*buf*/, int32_t /*n*/, int32_t /*n_past*/) {
    return 0;
}

llama_sampling_params llama_sampling_defaults() { return {}; }

llama_sampling_context * llama_sampling_init(llama_sampling_params /*params*/) { return nullptr; }
void llama_sampling_free(llama_sampling_context * /*ctx*/) {}
