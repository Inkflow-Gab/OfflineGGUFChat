// Host-side test for the GGUF parser: build synthetic .gguf files byte by byte,
// then assert the probe reads back exactly what we wrote.
//
// The writer here is also executable documentation of the container layout, so
// app/src/test/.../GgufReaderTest.kt mirrors it on the Kotlin side.
//
//   ./scripts/check-native.sh          (builds + runs this, needs only g++)
#include "gguf/gguf_probe.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {

int g_failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << "FAIL " << __FILE__ << ":" << __LINE__ << "  " << #cond \
                      << std::endl;                                              \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

#define CHECK_EQ(a, b)                                                           \
    do {                                                                         \
        auto va = (a);                                                           \
        auto vb = (b);                                                           \
        if (!(va == vb)) {                                                       \
            std::cerr << "FAIL " << __FILE__ << ":" << __LINE__ << "  " << #a   \
                      << " == " << #b << "  (got " << va << ", want " << vb     \
                      << ")" << std::endl;                                       \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

// ---------------------------------------------------------------- writer

class Writer {
public:
    void U8(uint8_t v) { bytes_.push_back(static_cast<char>(v)); }
    void I32(int32_t v) {
        for (int i = 0; i < 4; ++i) bytes_.push_back(static_cast<char>((v >> (8 * i)) & 0xFF));
    }
    void U32(uint32_t v) {
        for (int i = 0; i < 4; ++i) bytes_.push_back(static_cast<char>((v >> (8 * i)) & 0xFF));
    }
    void U64(uint64_t v) {
        for (int i = 0; i < 8; ++i) bytes_.push_back(static_cast<char>((v >> (8 * i)) & 0xFF));
    }
    void F32(float v) {
        uint32_t bits;
        memcpy(&bits, &v, sizeof(bits));
        U32(bits);
    }
    void Str(const std::string &s) {
        U64(s.size());
        for (char c : s) bytes_.push_back(c);
    }
    void KvString(const std::string &k, const std::string &v) {
        Str(k);
        U32(8);  // GGUF_TYPE_STRING
        Str(v);
    }
    void KvU32(const std::string &k, uint32_t v) {
        Str(k);
        U32(4);  // GGUF_TYPE_UINT32
        U32(v);
    }
    void KvI32(const std::string &k, int32_t v) {
        Str(k);
        U32(5);  // GGUF_TYPE_INT32
        I32(v);
    }
    void KvBool(const std::string &k, bool v) {
        Str(k);
        U32(7);  // GGUF_TYPE_BOOL
        U8(v ? 1 : 0);  // one byte, exactly as llama.cpp writes it
    }
    void KvStringArray(const std::string &k, const std::vector<std::string> &v) {
        Str(k);
        U32(9);  // GGUF_TYPE_ARRAY
        U32(8);  // element type: string
        U64(v.size());
        for (const auto &s : v) Str(s);
    }
    void KvF32Array(const std::string &k, size_t count) {
        Str(k);
        U32(9);
        U32(6);  // float32
        U64(count);
        for (size_t i = 0; i < count; ++i) F32(0.5f);
    }
    void KvU32Array(const std::string &k, const std::vector<uint32_t> &v) {
        Str(k);
        U32(9);
        U32(4);
        U64(v.size());
        for (auto x : v) U32(x);
    }
    void Tensor(const std::string &name, const std::vector<uint64_t> &dims, uint32_t dtype,
                uint64_t offset) {
        Str(name);
        U32(static_cast<uint32_t>(dims.size()));
        for (auto d : dims) U64(d);
        U32(dtype);
        U64(offset);
    }
    void Pad(size_t alignment) {
        while (bytes_.size() % alignment != 0) bytes_.push_back('\0');
    }
    const std::string &bytes() const { return bytes_; }

private:
    std::string bytes_;
};

std::string WriteToTempFile(const std::string &name, const std::string &data) {
    std::string path = "/tmp/" + name;
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(data.data(), static_cast<std::streamsize>(data.size()));
    out.close();
    return path;
}

// ---------------------------------------------------------------- cases

// A 3-tensor Q4_K model with 16 KB of vocabulary noise in front of the keys we
// care about, which is what a real file looks like.
void TestValidModel() {
    Writer w;
    w.bytes();  // no-op, keeps the API obvious below
    std::string &body = const_cast<std::string &>(w.bytes());
    body.append("GGUF");
    w.U32(3);   // version
    w.U64(3);   // n_tensors
    w.U64(16);  // n_kv

    w.KvString("general.architecture", "llama");
    w.KvString("general.name", "Tiny Test Model");
    w.KvU32("general.alignment", 64);
    w.KvU32("llama.context_length", 4096);
    w.KvU32("llama.block_count", 2);
    w.KvU32("llama.attention.head_count", 8);
    w.KvU32("llama.embedding_length", 128);
    // Big arrays must be skipped without materialising them.
    w.KvStringArray("tokenizer.ggml.tokens", {"a", "b", "c", "d", "e"});
    w.KvF32Array("tokenizer.ggml.scores", 5000);
    w.KvU32Array("tokenizer.ggml.token_type", {1, 1, 3, 2, 2});
    w.KvString("tokenizer.ggml.model", "gpt2");
    w.KvString("tokenizer.ggml.pre", "llama-bpe");
    w.KvBool("tokenizer.ggml.add_bos_token", true);
    w.KvI32("tokenizer.ggml.bos_token_id", 1);
    w.KvI32("tokenizer.ggml.eos_token_id", 2);
    w.KvString("tokenizer.chat_template", "{% for message in messages %}{{ message.content }}{% endfor %}");

    // two Q4_K tensors + one F16 output layer
    w.Tensor("token_embd.weight", {128, 32000}, 1, 0);
    w.Tensor("blk.0.attn_q.weight", {128, 128}, 12, 1024);
    w.Tensor("output.weight", {128, 32000}, 1, 2048);

    const size_t pre_pad = w.bytes().size();
    w.Pad(64);
    CHECK(w.bytes().size() >= pre_pad);
    CHECK_EQ(w.bytes().size() % 64, static_cast<size_t>(0));

    const std::string path = WriteToTempFile("ogg_valid.gguf", w.bytes());
    const ogg::GgufProbeResult r = ogg::ProbeGguf(path, 8 << 20);

    CHECK(r.ok);
    CHECK(r.error.empty());
    CHECK(r.magic_present);
    CHECK_EQ(r.version, 3u);
    CHECK_EQ(r.n_tensors, static_cast<uint64_t>(3));
    CHECK_EQ(r.n_kv, static_cast<uint64_t>(16));
    CHECK_EQ(r.alignment, static_cast<uint64_t>(64));
    CHECK_EQ(r.data_start, static_cast<uint64_t>(w.bytes().size()));
    CHECK_EQ(r.architecture, std::string("llama"));
    CHECK_EQ(r.name, std::string("Tiny Test Model"));
    CHECK_EQ(r.context_length, static_cast<int64_t>(4096));
    CHECK_EQ(r.n_head, static_cast<int64_t>(8));
    CHECK_EQ(r.n_layer, static_cast<int64_t>(2));
    CHECK_EQ(r.n_embd, static_cast<int64_t>(128));
    CHECK_EQ(r.n_vocab, static_cast<int64_t>(5));
    CHECK_EQ(r.bos_token_id, static_cast<int64_t>(1));
    CHECK_EQ(r.eos_token_id, static_cast<int64_t>(2));
    CHECK(r.add_bos_token);
    CHECK(r.has_chat_template);
    CHECK_EQ(r.tokenizer_model, std::string("gpt2"));
    CHECK_EQ(r.quantization, std::string("F16"));  // 2 of 3 tensors are F16
    // 128*32000 + 128*128 + 128*32000 elements
    CHECK_EQ(r.param_count, static_cast<uint64_t>(128 * 32000 + 128 * 128 + 128 * 32000));
    // F16: 2 B/elem; Q4_K: 256 elems per 144 B block.
    const uint64_t expected_bytes =
        2ull * (128 * 32000) + (144ull / 256) * 0 + 2ull * (128 * 32000) +
        ((128ull * 128 + 255) / 256) * 144;
    CHECK_EQ(r.estimated_weight_bytes, expected_bytes);

    std::remove(path.c_str());
}

void TestNotGguf() {
    std::string data = "RIFF....not a model at all";
    const std::string path = WriteToTempFile("ogg_bad.gguf", data);
    const ogg::GgufProbeResult r = ogg::ProbeGguf(path, 1 << 20);
    CHECK(!r.ok);
    CHECK(!r.magic_present);
    CHECK(r.error.find("magic") != std::string::npos);
    std::remove(path.c_str());
}

void TestTruncatedHeader() {
    Writer w;
    std::string &body = const_cast<std::string &>(w.bytes());
    body.append("GGUF");
    w.U32(3);
    w.U64(2);  // promises two tensors...
    w.U64(1);
    w.KvString("general.architecture", "qwen2");
    // ...but the file ends here.
    const std::string path = WriteToTempFile("ogg_trunc.gguf", w.bytes());
    const ogg::GgufProbeResult r = ogg::ProbeGguf(path, 1 << 20);
    CHECK(!r.ok);
    CHECK(r.architecture == "qwen2");  // partial metadata is still reported
    CHECK(r.error.find("tensor") != std::string::npos);
    std::remove(path.c_str());
}

void TestUnsupportedVersion() {
    std::string data = "GGUF";
    data.push_back(9);
    data.push_back(0);
    data.push_back(0);
    data.push_back(0);
    const std::string path = WriteToTempFile("ogg_ver.gguf", data);
    const ogg::GgufProbeResult r = ogg::ProbeGguf(path, 1 << 20);
    CHECK(!r.ok);
    CHECK(r.error.find("version") != std::string::npos);
    std::remove(path.c_str());
}

void TestMissingFile() {
    const ogg::GgufProbeResult r = ogg::ProbeGguf("/tmp/ogg_does_not_exist.gguf", 1 << 20);
    CHECK(!r.ok);
    CHECK(r.error.find("open") != std::string::npos);
}

void TestDtypeTable() {
    size_t block = 0;
    CHECK_EQ(std::string(ogg::DtypeToString(0)), std::string("F32"));
    CHECK_EQ(std::string(ogg::DtypeToString(1)), std::string("F16"));
    CHECK_EQ(std::string(ogg::DtypeToString(2)), std::string("Q4_0"));
    CHECK_EQ(std::string(ogg::DtypeToString(8)), std::string("Q8_0"));
    CHECK_EQ(std::string(ogg::DtypeToString(12)), std::string("Q4_K"));
    CHECK_EQ(ogg::DtypeBlockBytes(12, &block), static_cast<size_t>(144));
    CHECK_EQ(block, static_cast<size_t>(256));
    CHECK_EQ(ogg::DtypeBlockBytes(2, &block), static_cast<size_t>(18));
    CHECK_EQ(block, static_cast<size_t>(32));
    CHECK_EQ(std::string(ogg::DtypeToString(9999)), std::string("?"));
}

}  // namespace

int main() {
    TestValidModel();
    TestNotGguf();
    TestTruncatedHeader();
    TestUnsupportedVersion();
    TestMissingFile();
    TestDtypeTable();

    if (g_failures == 0) {
        std::cout << "gguf_probe_test: all checks passed" << std::endl;
        return 0;
    }
    std::cout << "gguf_probe_test: " << g_failures << " failure(s)" << std::endl;
    return 1;
}
