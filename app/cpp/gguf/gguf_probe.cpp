#include "gguf/gguf_probe.h"

#include <algorithm>
#include <cinttypes>
#include <cstdio>
#include <cstring>

namespace ogg {
namespace {

constexpr uint64_t kMaxArrayLen = 200'000'000ull;
constexpr uint64_t kMaxStringLen = 64ull << 20;

struct DtypeInfo {
    const char *name;
    uint32_t block;      // elements per block
    uint32_t type_size;  // bytes per block
};

// Mirrors ggml's type traits. `type_size` is bytes per `block` elements, which
// is what we need to estimate weights from the tensor-info table alone.
const DtypeInfo &Dtype(uint32_t t) {
    static const DtypeInfo unknown{"?", 1, 0};
    static const DtypeInfo table[] = {
        {"F32", 1, 4},        // 0
        {"F16", 1, 2},        // 1
        {"Q4_0", 32, 18},     // 2
        {"Q4_1", 32, 20},     // 3
        {"Q4_2", 32, 18},     // 4  (legacy, removed from ggml)
        {"Q4_3", 32, 18},     // 5  (legacy, removed from ggml)
        {"Q5_0", 32, 22},     // 6
        {"Q5_1", 32, 24},     // 7
        {"Q8_0", 32, 36},     // 8
        {"Q8_1", 32, 40},     // 9
        {"Q2_K", 256, 84},    // 10
        {"Q3_K", 256, 110},   // 11
        {"Q4_K", 256, 144},   // 12
        {"Q5_K", 256, 170},   // 13
        {"Q6_K", 256, 210},   // 14
        {"Q8_K", 256, 240},   // 15
        {"IQ2_XXS", 256, 66}, // 16
        {"IQ2_XS", 256, 74},  // 17
        {"IQ3_XXS", 256, 98}, // 18
        {"IQ1_S", 256, 50},   // 19
        {"IQ4_NL", 32, 18},   // 20
        {"IQ3_S", 256, 110},  // 21
        {"IQ2_S", 256, 80},   // 22
        {"IQ4_XS", 256, 136}, // 23
        {"I8", 1, 1},         // 24
        {"I16", 1, 2},        // 25
        {"I32", 1, 4},        // 26
        {"I64", 1, 8},        // 27
        {"IQ1_M", 256, 56},   // 28
        {"BF16", 1, 2},       // 29
    };
    if (t < sizeof(table) / sizeof(table[0])) return table[t];
    return unknown;
}

uint64_t AlignUp(uint64_t value, uint64_t alignment) {
    if (alignment <= 1) return value;
    const uint64_t rem = value % alignment;
    return rem == 0 ? value : value + (alignment - rem);
}

// Little-endian reader over a FILE*. All offsets are absolute so a single
// instance can be reused for the KV pass and the tensor-info pass.
class Reader {
public:
    explicit Reader(FILE *f) : f_(f) {}

    bool ReadBytes(void *dst, size_t n) {
        if (n == 0) return true;
        if (std::fread(dst, 1, n, f_) != n) {
            fail_ = true;  // EOF or short read: everything after this is garbage
            return false;
        }
        return true;
    }

    bool Seek(uint64_t pos) {
#ifdef _WIN32
        return _fseeki64(f_, static_cast<long long>(pos), SEEK_SET) == 0;
#else
        return fseeko(f_, static_cast<off_t>(pos), SEEK_SET) == 0;
#endif
    }

    uint8_t U8() {
        uint8_t v = 0;
        if (!ReadBytes(&v, 1)) fail_ = true;
        return v;
    }
    int8_t I8() { return static_cast<int8_t>(U8()); }
    uint16_t U16() {
        uint8_t b[2] = {0, 0};
        if (fail_) return 0;
        ReadBytes(b, 2);
        return static_cast<uint16_t>(b[0] | (b[1] << 8));
    }
    int16_t I16() { return static_cast<int16_t>(U16()); }
    uint32_t U32() {
        uint8_t b[4] = {0, 0, 0, 0};
        if (fail_) return 0;
        ReadBytes(b, 4);
        return static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
               (static_cast<uint32_t>(b[2]) << 16) | (static_cast<uint32_t>(b[3]) << 24);
    }
    int32_t I32() { return static_cast<int32_t>(U32()); }
    float F32() {
        uint32_t bits = U32();
        float out;
        std::memcpy(&out, &bits, sizeof(out));
        return out;
    }
    uint64_t U64() {
        uint8_t b[8] = {0};
        if (fail_) return 0;
        ReadBytes(b, 8);
        uint64_t v = 0;
        for (int i = 7; i >= 0; --i) v = (v << 8) | b[i];
        return v;
    }
    int64_t I64() { return static_cast<int64_t>(U64()); }
    double F64() {
        uint64_t bits = U64();
        double out;
        std::memcpy(&out, &bits, sizeof(out));
        return out;
    }

    // Strings are (len, bytes); len is u32 in GGUF v1 and u64 from v2 on.
    bool String(std::string *out, int version) {
        const uint64_t len = version <= 1 ? U32() : U64();
        if (fail_ || len > kMaxStringLen) {
            fail_ = true;
            return false;
        }
        out->assign(len, '\0');
        if (len && !ReadBytes(&(*out)[0], static_cast<size_t>(len))) {
            fail_ = true;
            return false;
        }
        return true;
    }

    bool SkipBytes(uint64_t n) {
        if (n == 0) return true;
        const long cur = ftell(f_);
        if (cur < 0) {
            fail_ = true;
            return false;
        }
        // fseek is O(1) and avoids pulling hundreds of MB through fread.
        if (!Seek(static_cast<uint64_t>(cur) + n)) {
            fail_ = true;
            return false;
        }
        return true;
    }

    bool ok() const { return !fail_; }
    void Fail() { fail_ = true; }

private:
    FILE *f_;
    bool fail_ = false;
};

bool IsIntLike(GgufType t) {
    switch (t) {
        case GgufType::kUint8:
        case GgufType::kInt8:
        case GgufType::kUint16:
        case GgufType::kInt16:
        case GgufType::kUint32:
        case GgufType::kInt32:
        case GgufType::kUint64:
        case GgufType::kInt64:
        case GgufType::kBool:
            return true;
        default:
            return false;
    }
}

uint64_t ScalarBytes(GgufType t) {
    switch (t) {
        case GgufType::kUint8:
        case GgufType::kInt8:
        case GgufType::kBool:  // one byte on disk, as llama.cpp sizes it
            return 1;
        case GgufType::kUint16:
        case GgufType::kInt16:
            return 2;
        case GgufType::kUint32:
        case GgufType::kInt32:
        case GgufType::kFloat32:
            return 4;
        case GgufType::kUint64:
        case GgufType::kInt64:
        case GgufType::kFloat64:
            return 8;
        default:
            return 0;  // caller handles string / array
    }
}

// Returns false when the stream went bad or the type is one GGUF does not
// define (ids at or above kTypeCount fall through to the default branch).
bool ReadValue(Reader &r, int version, GgufType type, GgufValue *out, uint64_t depth) {
    out->type = type;
    switch (type) {
        case GgufType::kUint8: out->i = r.U8(); return true;
        case GgufType::kInt8:  out->i = r.I8(); return true;
        case GgufType::kUint16: out->i = r.U16(); return true;
        case GgufType::kInt16: out->i = r.I16(); return true;
        case GgufType::kUint32: out->i = r.U32(); return true;
        case GgufType::kInt32:  out->i = r.I32(); return true;
        case GgufType::kUint64: out->i = static_cast<int64_t>(r.U64()); return true;
        case GgufType::kInt64:  out->i = r.I64(); return true;
        case GgufType::kFloat32: out->d = r.F32(); return true;
        case GgufType::kFloat64: out->d = r.F64(); return true;
        case GgufType::kBool:   out->bool_value = r.U8() != 0; return true;
        case GgufType::kString: return r.String(&out->s, version);
        case GgufType::kArray: {
            const auto elem = static_cast<GgufType>(r.U32());
            const uint64_t len = r.U64();
            if (!r.ok() || len > kMaxArrayLen) return false;
            out->array_elem_type = elem;
            out->array_len = len;
            const uint64_t scalar = ScalarBytes(elem);
            // Arrays of numbers up to `kMaxArrayLen` that are tiny (token id
            // lists, eos token id arrays) are worth keeping; everything else
            // (vocab, scores, merges) is skipped in one seek when possible.
            const bool keep = IsIntLike(elem) && len <= 256;
            if (scalar > 0 && !keep) return r.SkipBytes(scalar * len);
            if (scalar > 0 && keep) {
                out->array_ints.reserve(static_cast<size_t>(len));
                for (uint64_t i = 0; i < len; ++i) {
                    GgufValue item;
                    if (!ReadValue(r, version, elem, &item, depth + 1)) return false;
                    out->array_ints.push_back(item.i);
                }
                return true;
            }
            if (elem == GgufType::kString) {
                for (uint64_t i = 0; i < len; ++i) {
                    std::string tmp;
                    if (!r.String(&tmp, version)) return false;
                }
                return true;
            }
            if (elem == GgufType::kArray) {
                if (depth > 4) return false;  // do not recurse on pathological input
                for (uint64_t i = 0; i < len; ++i) {
                    GgufValue item;
                    if (!ReadValue(r, version, elem, &item, depth + 1)) return false;
                }
                return true;
            }
            return false;  // unknown element type -> stop, treat header as truncated
        }
    }
    return false;
}

bool ReadValueSkipping(Reader &r, int version, GgufType type, uint64_t depth) {
    const uint64_t scalar = ScalarBytes(type);
    if (scalar > 0) return r.SkipBytes(scalar);
    if (type == GgufType::kString) {
        std::string tmp;
        return r.String(&tmp, version);
    }
    if (type == GgufType::kArray) {
        const auto elem = static_cast<GgufType>(r.U32());
        const uint64_t len = r.U64();
        if (!r.ok() || len > kMaxArrayLen) return false;
        const uint64_t child = ScalarBytes(elem);
        if (child > 0) return r.SkipBytes(child * len);
        if (elem == GgufType::kString) {
            for (uint64_t i = 0; i < len; ++i) {
                std::string tmp;
                if (!r.String(&tmp, version)) return false;
            }
            return true;
        }
        if (elem == GgufType::kArray && depth <= 4) {
            for (uint64_t i = 0; i < len; ++i) {
                if (!ReadValueSkipping(r, version, elem, depth + 1)) return false;
            }
            return true;
        }
    }
    return false;
}

bool KeyIsInteresting(const std::string &k, const std::string &arch) {
    static const char *prefixes[] = {
        "general.", "tokenizer.ggml.model", "tokenizer.ggml.pre", "tokenizer.ggml.bos",
        "tokenizer.ggml.eos", "tokenizer.ggml.padding", "tokenizer.ggml.eot",
        "tokenizer.ggml.add_bos", "tokenizer.chat_template", "tokenizer.ggml.tokens",
        "tokenizer.ggml.token_type", "tokenizer.ggml.scores", "tokenizer.ggml.merges",
    };
    for (const char *p : prefixes) {
        if (k.rfind(p, 0) == 0) return true;
    }
    // Everything under the model's own namespace (llama.*, qwen2.*, ...):
    // context_length, block_count, attention.head_count, embedding_length.
    return !arch.empty() && k.rfind(arch + ".", 0) == 0;
}

void ApplyKey(const std::string &key, const GgufValue &v, GgufProbeResult *res, int version) {
    auto &arch = res->architecture;
    if (key == "general.architecture") {
        res->architecture = v.s;
    } else if (key == "general.name") {
        res->name = v.s;
    } else if (key == "general.alignment") {
        res->alignment = static_cast<uint64_t>(v.i);
    } else if (key == "tokenizer.ggml.model") {
        res->tokenizer_model = v.s;
    } else if (key == "tokenizer.ggml.pre") {
        res->tokenizer_pre = v.s;
    } else if (key == "tokenizer.chat_template") {
        res->chat_template = v.s;
        res->has_chat_template = !v.s.empty();
    } else if (key == "tokenizer.ggml.add_bos_token") {
        res->add_bos_token = v.bool_value;
    } else if (key == "tokenizer.ggml.tokens") {
        // The declared {arch}.vocab_size wins when both are present; the token
        // array length is the fallback for files that omit it. Keeping both lets
        // callers flag a header whose declared vocab disagrees with its tokenizer.
        res->token_count = static_cast<int64_t>(v.array_len);
        if (res->n_vocab == 0) res->n_vocab = res->token_count;
    } else if (key == "tokenizer.ggml.bos_token_id") {
        res->bos_token_id = v.i;
    } else if (key == "tokenizer.ggml.eos_token_id") {
        res->eos_token_id = v.i;
    } else if (key == "tokenizer.ggml.padding_token_id") {
        res->padding_token_id = v.i;
    } else if (key == "tokenizer.ggml.eot_token_id") {
        res->eot_token_id = v.i;
    } else if (key == "tokenizer.ggml.bos_token_ids") {
        res->bos_token_ids = v.array_ints;
    } else if (!arch.empty()) {
        const std::string prefix = arch + ".";
        if (key.rfind(prefix, 0) != 0) return;
        const std::string tail = key.substr(prefix.size());
        if (tail == "context_length") res->context_length = v.i;
        else if (tail == "attention.head_count") res->n_head = v.i;
        else if (tail == "block_count") res->n_layer = v.i;
        else if (tail == "embedding_length") res->n_embd = v.i;
        else if (tail == "vocab_size") res->n_vocab = v.i;
    }
    (void)version;
}

}  // namespace

const char *DtypeToString(uint32_t dtype) { return Dtype(dtype).name; }

size_t DtypeBlockBytes(uint32_t dtype, size_t *elements_per_block) {
    const DtypeInfo &info = Dtype(dtype);
    if (elements_per_block) *elements_per_block = info.block;
    return info.type_size;
}

GgufProbeResult ProbeGguf(const std::string &path, size_t metadata_budget_bytes) {
    GgufProbeResult res;
    res.quantization = "unknown";  // every early return below keeps this

    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) {
        res.error = "cannot open file";
        return res;
    }

    Reader r(f);
    char magic[4] = {0};
    if (!r.ReadBytes(magic, 4) || std::memcmp(magic, "GGUF", 4) != 0) {
        res.error = std::memcmp(magic, "GGUF", 4) == 0 ? "short file" : "not a GGUF file (bad magic)";
        std::fclose(f);
        return res;
    }
    res.magic_present = true;

    res.version = r.U32();
    if (res.version < 1 || res.version > 3) {
        res.error = "unsupported GGUF version " + std::to_string(res.version);
        std::fclose(f);
        return res;
    }
    const int version = static_cast<int>(res.version);

    if (version <= 1) {
        res.n_tensors = r.U32();
        res.n_kv = r.U32();
    } else {
        res.n_tensors = r.U64();
        res.n_kv = r.U64();
    }
    if (!r.ok()) {
        res.error = "truncated header";
        std::fclose(f);
        return res;
    }
    // A GGUF with billions of tensors is a corrupt header, not a real model.
    if (res.n_tensors > 10'000'000ull || res.n_kv > 1'000'000ull) {
        res.error = "implausible tensor/KV counts";
        std::fclose(f);
        return res;
    }

    // ---- KV metadata ----
    uint64_t alignment_seen = 0;
    bool truncated = false;
    for (uint64_t i = 0; i < res.n_kv; ++i) {
        std::string key;
        if (!r.String(&key, version)) {
            truncated = true;
            break;
        }
        const uint32_t type_id = r.U32();
        if (!r.ok()) {
            truncated = true;
            break;
        }
        if (type_id >= static_cast<uint32_t>(GgufType::kTypeCount)) {
            // Unlike a truncation this cannot be reported as "partial": the
            // width of the value is unknown, so nothing after it can be read.
            res.error = "unsupported GGUF value type (id " + std::to_string(type_id) + ")";
            std::fclose(f);
            return res;
        }
        const auto type = static_cast<GgufType>(type_id);
        const long pos_after_type = ftell(f);
        if (pos_after_type < 0) {
            truncated = true;
            break;
        }
        // Stop rather than walk hundreds of megabytes of vocab on a tiny device.
        if (metadata_budget_bytes > 0 &&
            static_cast<uint64_t>(pos_after_type) > metadata_budget_bytes) {
            truncated = true;
            break;
        }
        if (KeyIsInteresting(key, res.architecture)) {
            GgufValue v;
            if (!ReadValue(r, version, type, &v, 0)) {
                truncated = true;
                break;
            }
            ApplyKey(key, v, &res, version);
            if (key == "general.alignment") alignment_seen = static_cast<uint64_t>(v.i);
        } else if (!ReadValueSkipping(r, version, type, 0)) {
            truncated = true;
            break;
        }
    }

    if (alignment_seen > 0) res.alignment = alignment_seen;
    if (res.alignment == 0 || (res.alignment & (res.alignment - 1)) != 0) {
        res.alignment = 32;  // spec default, must be a power of two
    }

    // ---- tensor info table ----
    // Only reachable when the KV walk finished: the table starts exactly where
    // the last KV pair ended, so a walk that stopped early (read budget, or a
    // cut file) leaves the pointer inside the metadata. Parsing "tensors" from
    // there invents a table that is not there and reports a good file as a
    // broken download.
    const bool table_readable = !truncated;
    const uint64_t tensor_table_start = static_cast<uint64_t>(ftell(f));
    std::vector<uint32_t> dtypes;
    dtypes.reserve(static_cast<size_t>(std::min<uint64_t>(res.n_tensors, 65536)));
    uint64_t params = 0;
    uint64_t weight_bytes = 0;
    bool tensors_truncated = false;

    const uint64_t table_entries = table_readable ? res.n_tensors : 0;
    for (uint64_t i = 0; i < table_entries; ++i) {
        std::string tname;
        if (!r.String(&tname, version)) {
            tensors_truncated = true;
            break;
        }
        const uint32_t n_dims = r.U32();
        if (!r.ok() || n_dims > 8) {
            tensors_truncated = true;
            break;
        }
        GgufTensorInfo info;
        info.name = std::move(tname);
        info.dims.reserve(n_dims);
        uint64_t elements = 1;
        for (uint32_t d = 0; d < n_dims; ++d) {
            const uint64_t dim = (version <= 1) ? r.U32() : r.U64();
            info.dims.push_back(dim);
            if (dim && elements <= kMaxArrayLen) elements *= dim;
        }
        info.dtype = r.U32();
        info.offset = r.U64();
        if (!r.ok()) {
            tensors_truncated = true;
            break;
        }
        dtypes.push_back(info.dtype);
        params += elements;
        size_t block = 1;
        const size_t block_bytes = DtypeBlockBytes(info.dtype, &block);
        if (block_bytes && block) {
            weight_bytes += ((elements + block - 1) / block) * block_bytes;
        }
        if (res.tensor_dtypes.size() < 64) {
            res.tensor_dtypes.emplace_back(info.name, info.dtype);
        }
    }

    if (!dtypes.empty()) {
        std::sort(dtypes.begin(), dtypes.end());
        uint32_t best = dtypes.front();
        uint32_t best_count = 1;
        uint32_t run = 1;
        for (size_t i = 1; i < dtypes.size(); ++i) {
            run = dtypes[i] == dtypes[i - 1] ? run + 1 : 1;
            if (run > best_count) {
                best_count = run;
                best = dtypes[i];
            }
        }
        // Embeddings/output layers are commonly kept at a higher precision
        // than the body; the majority type is what people call "the quant".
        res.quantization = Dtype(best).name;
    }
    if (res.quantization.empty()) res.quantization = "unknown";  // no tensors parsed

    res.param_count = params;
    res.estimated_weight_bytes = weight_bytes;

    // ---- data section ----
    // Tensor offsets are relative to the aligned end of the header, so that
    // boundary is what callers need for integrity checks.
    //
    // `tensor_count_seen` is reported even when the table was cut short: "3 of 5
    // tensors parsed" is the single most useful thing to tell a user whose
    // download stopped halfway, so it must not be thrown away here.
    res.tensor_count_seen = static_cast<uint64_t>(dtypes.size());
    if (!tensors_truncated && table_readable) {
        const long header_end_raw = ftell(f);
        if (header_end_raw >= 0) {
            const uint64_t header_end = static_cast<uint64_t>(header_end_raw);
            res.data_start = AlignUp(header_end, res.alignment);
            res.header_size = header_end - tensor_table_start;
        }
    }

    std::fclose(f);

    res.ok = !truncated && !tensors_truncated && res.n_tensors > 0;
    res.metadata_truncated = truncated;
    if (tensors_truncated) {
        res.error = "tensor table truncated or unreadable (partial download?)";
    } else if (truncated) {
        res.error = "metadata section exceeds read budget; partial header";
        res.ok = true;  // what we learned is still valid
    } else if (res.n_tensors == 0) {
        res.error = "no tensors";
    }
    (void)version;
    return res;
}

}  // namespace ogg
