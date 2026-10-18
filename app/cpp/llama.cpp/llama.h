#pragma once

/**
 * llama.cpp — minimal API surface used by Offline GGUF Chat.
 *
 * This is a STUB header for project structure. Replace the contents of
 * llama.cpp/llama.cpp with the real source from https://github.com/ggml-org/llama.cpp
 * to enable actual inference.
 */

#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>

struct llama_model;
struct llama_context;
struct llama_sampling_context;

struct llama_model_params {
    int32_t n_gpu_layers = 0;
    int32_t use_mmap = 1;
    int32_t use_mlock = 0;
    int32_t reserved[16] = {};
};

struct llama_context_params {
    int32_t n_ctx = 512;
    int32_t n_batch = 512;
    int32_t n_threads = 4;
    int32_t n_threads_batch = 4;
    int32_t reserved[16] = {};
};

struct llama_sampling_params {
    float temp = 0.7f;
    float top_p = 0.9f;
    int32_t top_k = 40;
    float repeat_penalty = 1.1f;
    int32_t seed = -1;
    int32_t reserved[16] = {};
};

using llama_token = int32_t;

constexpr llama_token LLAMA_TOKEN_EOS  = -2;
constexpr llama_token LLAMA_TOKEN_NONE = -1;

// Model loading
llama_model * llama_load_model_from_file(const char * path, llama_model_params params);
void llama_free_model(llama_model * model);

// Context
llama_context * llama_new_context_with_model(llama_model * model, llama_context_params params);
void llama_free(llama_context * ctx);

// Model info
int32_t  llama_n_vocab(const llama_model * model);
int32_t  llama_n_ctx_train(const llama_model * model);
const char * llama_arch_name(const llama_model * model);
int32_t    llama_model_has_vision(const llama_model * model);

// Tokenizer (matches GGUF's embedded vocab — BPE or SentencePiece depending on model)
std::vector<llama_token> llama_tokenize(const llama_model * model, const char * text, bool add_bos);

// Inference
int32_t llama_decode(llama_context * ctx, const llama_token * tokens, int32_t n_tokens, int32_t n_past);
llama_token llama_sample_token(llama_context * ctx, const llama_sampling_params * params, void * candidates);
llama_token llama_token_eos(const llama_model * model);
int32_t llama_token_to_piece(llama_context * ctx, llama_token token, char * buf, int32_t n, int32_t n_past);

// Sampling
llama_sampling_params llama_sampling_defaults();
llama_sampling_context * llama_sampling_init(llama_sampling_params params);
void llama_sampling_free(llama_sampling_context * ctx);
