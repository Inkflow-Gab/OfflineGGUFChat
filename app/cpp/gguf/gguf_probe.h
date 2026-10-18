// GGUF container header probe.
//
// Parses the parts of a .gguf file that can be read without touching tensor
// data: magic/version, tensor + KV counts, the KV metadata we care about, and
// the tensor-info table (used to derive the quantization label).
//
// Spec reference: https://github.com/ggml-org/llama.cpp/blob/master/docs/gguf.md
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ogg {

enum class GgufType : uint32_t {
    kUint8 = 0,
    kInt8 = 1,
    kUint16 = 2,
    kInt16 = 3,
    kUint32 = 4,
    kInt32 = 5,
    kFloat32 = 6,
    kBool = 7,
    kString = 8,
    kArray = 9,
    kUint64 = 10,
    kInt64 = 11,
    kFloat64 = 12,
    kUint128 = 13,
    kInt128 = 14,
};

// One metadata entry, kept in a deliberately small typed subset. Arrays are
// stored as element type + length only (their payloads are huge and unused).
struct GgufValue {
    GgufType type = GgufType::kUint32;
    int64_t i = 0;
    double d = 0.0;
    bool bool_value = false;
    std::string s;
    GgufType array_elem_type = GgufType::kUint32;
    uint64_t array_len = 0;
    std::vector<int64_t> array_ints;  // only for small numeric arrays (token ids)

    bool IsArray() const { return type == GgufType::kArray; }
    bool IsString() const { return type == GgufType::kString; }
};

struct GgufTensorInfo {
    std::string name;
    std::vector<uint64_t> dims;
    uint32_t dtype = 0;
    uint64_t offset = 0;
};

struct GgufProbeResult {
    bool ok = false;
    // Human readable reason. Empty when ok == true.
    std::string error;

    bool magic_present = false;
    uint32_t version = 0;
    uint64_t n_tensors = 0;
    uint64_t n_kv = 0;
    uint64_t header_size = 0;
    // Number of tensor-info records actually parsed (== n_tensors unless truncated).
    uint64_t tensor_count_seen = 0;
    // True when the KV walk hit the read budget: metadata is partial but valid.
    bool metadata_truncated = false;
    uint64_t alignment = 32;
    uint64_t data_start = 0;

    std::string architecture;
    std::string name;
    std::string tokenizer_model;
    std::string tokenizer_pre;
    std::string chat_template;      // may be empty -> no template embedded
    bool has_chat_template = false;
    bool add_bos_token = false;

    int64_t context_length = 0;     // training context from the arch namespace
    int64_t n_vocab = 0;         // declared {arch}.vocab_size when present
    int64_t token_count = 0;     // length of tokenizer.ggml.tokens
    int64_t n_head = 0;
    int64_t n_layer = 0;
    int64_t n_embd = 0;
    int64_t bos_token_id = -1;
    int64_t eos_token_id = -1;
    int64_t padding_token_id = -1;
    int64_t eot_token_id = -1;
    std::vector<int64_t> bos_token_ids;

    // Dominant tensor quantization type, e.g. "Q4_K_M"; "F16"/"F32" for
    // unquantised files, "mixed" when no single type dominates.
    std::string quantization;
    std::vector<std::pair<std::string, uint32_t>> tensor_dtypes;
    uint64_t param_count = 0;       // sum of tensor element counts
    uint64_t estimated_weight_bytes = 0;
};

// Read the header of `path`. Never maps the whole file: it reads at most
// `metadata_budget_bytes` of KV metadata (plus the tensor-info table), which
// keeps this safe for multi-GB files on low-RAM devices.
GgufProbeResult ProbeGguf(const std::string &path, size_t metadata_budget_bytes = 8u << 20);

// Convenience helpers exposed to Kotlin through the JNI layer.
const char *DtypeToString(uint32_t dtype);
size_t DtypeBlockBytes(uint32_t dtype, size_t *elements_per_block);

}  // namespace ogg
