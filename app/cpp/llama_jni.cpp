// JNI surface for com.offlinegguf.chat.inference.LlamaNative.
//
// Design notes that are easy to get wrong and are deliberate here:
//  * Sessions live in a registry guarded by a mutex instead of being cast
//    straight from a jlong, so a destroy() racing a running generation cannot
//    free the model underneath it.
//  * The prompt is converted from Java's UTF-16 to real UTF-8 by hand.
//    GetStringUTFChars() returns *modified* UTF-8 (surrogate pairs become 6
//    bytes), which tokenizes emoji and CJK-extension characters incorrectly.
//  * Token pieces cross the boundary as raw byte[] and are decoded on the Kotlin
//    side, because a single piece can end mid-code-point.
#include <jni.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include <android/log.h>

#include "gguf/gguf_probe.h"
#include "llama/llama_bridge.h"
#include "util/utf8.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OfflineGGUF", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OfflineGGUF", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OfflineGGUF", __VA_ARGS__)

namespace {

constexpr const char *kCallbackClass = "com/offlinegguf/chat/inference/GenerationCallback";

std::mutex g_registry_mu;
std::set<ogg::BridgeSession *> g_sessions;

bool Adopt(ogg::BridgeSession *session) {
    if (!session) return false;
    std::lock_guard<std::mutex> lock(g_registry_mu);
    return g_sessions.insert(session).second;
}

// Removes the handle from the registry. After this returns, no *new* call can
// resolve the handle; an in-flight generation keeps running until it settles.
bool Detach(ogg::BridgeSession *session) {
    std::lock_guard<std::mutex> lock(g_registry_mu);
    return g_sessions.erase(session) > 0;
}

bool IsLive(ogg::BridgeSession *session) {
    if (!session) return false;
    std::lock_guard<std::mutex> lock(g_registry_mu);
    return g_sessions.count(session) > 0;
}

ogg::BridgeSession *Resolve(jlong handle) {
    auto *session = reinterpret_cast<ogg::BridgeSession *>(handle);
    return IsLive(session) ? session : nullptr;
}

jstring NewStringFromUtf8(JNIEnv *env, const std::string &utf8) {
    if (utf8.empty()) return env->NewStringUTF("");
    // Round-trip through UTF-16 so valid UTF-8 (including 4-byte code points)
    // survives; env->NewStringUTF would need modified UTF-8.
    std::u16string utf16;
    utf16.reserve(utf8.size());
    size_t i = 0;
    const size_t n = utf8.size();
    auto byte = [&](size_t k) { return static_cast<unsigned char>(utf8[k]); };
    while (i < n) {
        const unsigned char c = byte(i);
        uint32_t cp = 0;
        size_t len = 0;
        if (c < 0x80) {
            cp = c;
            len = 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < n) {
            cp = ((c & 0x1Fu) << 6) | (byte(i + 1) & 0x3Fu);
            len = 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < n) {
            cp = ((c & 0x0Fu) << 12) | ((byte(i + 1) & 0x3Fu) << 6) | (byte(i + 2) & 0x3Fu);
            len = 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < n) {
            cp = ((c & 0x07u) << 18) | ((byte(i + 1) & 0x3Fu) << 12) |
                 ((byte(i + 2) & 0x3Fu) << 6) | (byte(i + 3) & 0x3Fu);
            len = 4;
        } else {
            utf16.push_back(u'\uFFFD');
            ++i;
            continue;
        }
        i += len;
        if (cp > 0x10FFFF) {
            utf16.push_back(u'\uFFFD');
        } else if (cp < 0x10000) {
            utf16.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                          static_cast<jsize>(utf16.size()));
}

std::string JStringToUtf8(JNIEnv *env, jstring text) {
    if (text == nullptr) return std::string();
    const jsize len = env->GetStringLength(text);
    const jchar *chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(len) + 1);
    auto append = [&out](uint32_t cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    };
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                append(0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00));
                ++i;
                continue;
            }
        }
        if ((c >= 0xD800 && c <= 0xDFFF) || (c >= 0xFDD0 && c <= 0xFDEF) ||
            (c & 0xFFFE) == 0xFFFE) {
            append(0xFFFD);
        } else {
            append(c);
        }
    }
    env->ReleaseStringChars(text, chars);
    return out;
}

std::vector<std::string> JStringArrayToVector(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize n = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto *element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(JStringToUtf8(env, element));
        if (element) env->DeleteLocalRef(element);
    }
    return out;
}

// Index layout of the `ints`/`floats` arrays handed over by Kotlin. Kept in one
// place on both sides of the boundary; Kotlin asserts the length matches.
enum IntArg {
    kIntNPredict = 0,
    kIntTopK,
    kIntRepeatLastN,
    kIntSeed,
    kIntIgnoreEos,
    kIntCount
};

enum FloatArg {
    kFloatTemperature = 0,
    kFloatTopP,
    kFloatMinP,
    kFloatTypical,
    kFloatRepeatPenalty,
    kFloatPresencePenalty,
    kFloatFrequencyPenalty,
    kFloatCount
};

enum StatsSlot {
    kStatsGenerated = 0,
    kStatsPromptTokens,
    kStatsContextUsed,
    kStatsMicros,
    kStatsFlags,  // bit 0: truncated, bit 1: cancelled
    kStatsCount
};

struct Callbacks {
    JNIEnv *env = nullptr;
    jobject object = nullptr;
    jmethodID on_token = nullptr;
    jmethodID on_error = nullptr;
    bool failed = false;

    // Both callbacks are invoked on the calling thread, which is attached to
    // the JVM because the caller came from Kotlin.
    void EmitToken(const char *bytes, int len) {
        if (!on_token || failed) return;
        jbyteArray array = env->NewByteArray(len);
        if (!array) {
            failed = true;
            return;
        }
        env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte *>(bytes));
        env->CallVoidMethod(object, on_token, array);
        env->DeleteLocalRef(array);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            failed = true;  // Kotlin side cancelled; stop generating
        }
    }
};

void OnTokenBridge(void *user, const char *bytes, int len) {
    static_cast<Callbacks *>(user)->EmitToken(bytes, len);
}

void OnErrorBridge(void *user, const char *message) {
    auto *cb = static_cast<Callbacks *>(user);
    if (!cb->on_error || cb->failed) return;
    jstring text = cb->env->NewStringUTF(message ? message : "");
    cb->env->CallVoidMethod(cb->object, cb->on_error, text);
    if (text) cb->env->DeleteLocalRef(text);
    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionClear();
        cb->failed = true;
    }
}

}  // namespace

extern "C" {

// ---------------------------------------------------------------- engine info

JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeEngineAvailable(JNIEnv *, jobject) {
    return ogg::BridgeAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeEngineVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(ogg::BridgeEngineVersion().c_str());
}

// Fast, allocation-light validity check used before touching a multi-GB file.
// Returns null when the header looks loadable, otherwise a human-readable
// reason. `budget_bytes` caps how much metadata is parsed.
JNIEXPORT jstring JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeProbeGguf(JNIEnv *env, jobject,
                                                                 jstring path,
                                                                 jlong budget_bytes) {
    const std::string file = JStringToUtf8(env, path);
    if (file.empty()) return env->NewStringUTF("empty path");
    const ogg::GgufProbeResult result =
        ogg::ProbeGguf(file, static_cast<size_t>(budget_bytes < 0 ? (8 << 20) : budget_bytes));
    if (result.ok && result.error.empty()) return nullptr;
    std::string message = result.error.empty() ? "unrecognised GGUF header" : result.error;
    message += " [version=" + std::to_string(result.version) +
               ", tensors=" + std::to_string(result.n_tensors) +
               ", kv=" + std::to_string(result.n_kv) +
               ", arch=" + (result.architecture.empty() ? "?" : result.architecture) +
               ", quant=" + (result.quantization.empty() ? "?" : result.quantization) + "]";
    return NewStringFromUtf8(env, message);
}

// -------------------------------------------------------------------- session

JNIEXPORT jlong JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeCreateSession(JNIEnv *, jobject) {
    ogg::BridgeSession *session = ogg::BridgeCreate();
    if (!Adopt(session)) {
        ogg::BridgeDestroy(session);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeDestroySession(JNIEnv *, jobject,
                                                                     jlong handle) {
    auto *session = reinterpret_cast<ogg::BridgeSession *>(handle);
    if (!Detach(session)) return;  // already gone or never ours
    ogg::BridgeRequestStop(session);
    ogg::BridgeWaitIdle(session, 15000);
    ogg::BridgeDestroy(session);
}

// ---------------------------------------------------------------------- load

JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeLoadModel(
    JNIEnv *env, jobject, jlong handle, jstring path, jint n_ctx, jint n_gpu_layers,
    jint n_threads, jint n_batch, jboolean use_mmap, jboolean use_mlock, jobjectArray lora_paths,
    jfloatArray lora_scales) {
    ogg::BridgeSession *session = Resolve(handle);
    if (!session) {
        LOGE("nativeLoadModel: invalid session handle");
        return JNI_FALSE;
    }

    ogg::BridgeLoadParams params;
    params.n_ctx = n_ctx;
    params.n_gpu_layers = n_gpu_layers;
    params.n_threads = n_threads;
    params.n_batch = n_batch;
    params.use_mmap = use_mmap == JNI_TRUE;
    params.use_mlock = use_mlock == JNI_TRUE;

    const std::vector<std::string> loras = JStringArrayToVector(env, lora_paths);
    if (!loras.empty() && lora_scales != nullptr) {
        const jsize count = env->GetArrayLength(lora_scales);
        jfloat *scales = env->GetFloatArrayElements(lora_scales, nullptr);
        for (size_t i = 0; i < loras.size(); ++i) {
            const float scale = scales ? scales[std::min<jsize>(i, count - 1)] : 1.0f;
            params.loras.emplace_back(loras[i], scale);
        }
        if (scales) env->ReleaseFloatArrayElements(lora_scales, scales, JNI_ABORT);
    }

    std::string error;
    const bool ok = ogg::BridgeLoad(session, JStringToUtf8(env, path), params, &error);
    if (!ok) LOGE("model load failed: %s", error.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeUnloadModel(JNIEnv *, jobject, jlong h) {
    if (ogg::BridgeSession *s = Resolve(h)) ogg::BridgeUnload(s);
}

JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeIsLoaded(JNIEnv *, jobject, jlong h) {
    ogg::BridgeSession *s = Resolve(h);
    return s && ogg::BridgeIsLoaded(s) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeLastError(JNIEnv *env, jobject,
                                                                jlong handle) {
    if (ogg::BridgeSession *s = Resolve(handle)) {
        return NewStringFromUtf8(env, ogg::BridgeLastError(s));
    }
    return env->NewStringUTF("invalid session handle");
}

// -------------------------------------------------------------- model report

#define FORWARD_INT(name, expr)                                          \
    JNIEXPORT jint JNICALL Java_com_offlinegguf_chat_inference_LlamaNative_##name( \
        JNIEnv *, jobject, jlong handle) {                               \
        ogg::BridgeSession *s = Resolve(handle);                         \
        return s ? static_cast<jint>(expr) : 0;                          \
    }

FORWARD_INT(nativeVocabSize, ogg::BridgeVocabSize(s))
FORWARD_INT(nativeNctxTrain, ogg::BridgeNCtxTrain(s))
FORWARD_INT(nativeContextSize, ogg::BridgeContextSize(s))
FORWARD_INT(nativeContextUsed, ogg::BridgeContextUsed(s))
#undef FORWARD_INT

#define FORWARD_STRING(name, expr)                                       \
    JNIEXPORT jstring JNICALL Java_com_offlinegguf_chat_inference_LlamaNative_##name( \
        JNIEnv *env, jobject, jlong handle) {                            \
        ogg::BridgeSession *s = Resolve(handle);                         \
        return NewStringFromUtf8(env, s ? (expr) : std::string());       \
    }

FORWARD_STRING(nativeModelDescription, ogg::BridgeModelDescription(s))
FORWARD_STRING(nativeChatTemplate, ogg::BridgeChatTemplate(s))
#undef FORWARD_STRING

JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeHasMultimodal(JNIEnv *, jobject, jlong h) {
    ogg::BridgeSession *s = Resolve(h);
    return s && ogg::BridgeHasMultimodalProjector(s) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeSupportsAdapters(JNIEnv *, jobject, jlong h) {
    ogg::BridgeSession *s = Resolve(h);
    return s && ogg::BridgeSupportsAdapters(s) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeParamCount(JNIEnv *, jobject, jlong h) {
    ogg::BridgeSession *s = Resolve(h);
    return s ? static_cast<jlong>(ogg::BridgeParamCount(s)) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeWeightBytes(JNIEnv *, jobject, jlong h) {
    ogg::BridgeSession *s = Resolve(h);
    return s ? static_cast<jlong>(ogg::BridgeWeightBytes(s)) : 0;
}

JNIEXPORT void JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeRewindContext(JNIEnv *, jobject, jlong h,
                                                                    jint keep) {
    if (ogg::BridgeSession *s = Resolve(h)) ogg::BridgeRewindContext(s, keep);
}

// ------------------------------------------------------------------ generate

JNIEXPORT jlongArray JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeGenerate(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jintArray ints, jfloatArray floats,
    jstring grammar, jintArray stop_tokens, jobject callback) {
    ogg::BridgeSession *session = Resolve(handle);
    if (!session) {
        LOGE("nativeGenerate: invalid session handle");
        return nullptr;
    }

    jint int_values[kIntCount] = {0};
    jfloat float_values[kFloatCount] = {0.0f};
    if (ints && env->GetArrayLength(ints) >= kIntCount) {
        env->GetIntArrayRegion(ints, 0, kIntCount, int_values);
    }
    if (floats && env->GetArrayLength(floats) >= kFloatCount) {
        env->GetFloatArrayRegion(floats, 0, kFloatCount, float_values);
    }

    ogg::BridgeSampleParams params;
    params.n_predict = int_values[kIntNPredict];
    params.top_k = int_values[kIntTopK];
    params.repeat_last_n = int_values[kIntRepeatLastN];
    params.seed = int_values[kIntSeed];
    params.ignore_eos = int_values[kIntIgnoreEos] != 0;
    params.temperature = float_values[kFloatTemperature];
    params.top_p = float_values[kFloatTopP];
    params.min_p = float_values[kFloatMinP];
    params.typical_p = float_values[kFloatTypical];
    params.repeat_penalty = float_values[kFloatRepeatPenalty];
    params.presence_penalty = float_values[kFloatPresencePenalty];
    params.frequency_penalty = float_values[kFloatFrequencyPenalty];

    if (stop_tokens) {
        const jsize n = env->GetArrayLength(stop_tokens);
        std::vector<jint> buffer(static_cast<size_t>(std::min<jsize>(n, 4096)));
        if (!buffer.empty()) {
            env->GetIntArrayRegion(stop_tokens, 0, static_cast<jsize>(buffer.size()),
                                   buffer.data());
            params.stop_tokens.assign(buffer.begin(), buffer.end());
        }
    }
    params.grammar = JStringToUtf8(env, grammar);

    Callbacks cb;
    cb.env = env;
    cb.object = callback;
    jclass cls = env->GetObjectClass(callback);
    if (cls) {
        cb.on_token = env->GetMethodID(cls, "onTokenBytes", "([B)V");
        cb.on_error = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }

    ogg::BridgeStats stats;
    const bool started = ogg::BridgeGenerate(session, JStringToUtf8(env, prompt), params,
                                             {&cb, &OnTokenBridge, nullptr, &OnErrorBridge},
                                             &stats);
    if (!started) return nullptr;

    jlongArray result = env->NewLongArray(kStatsCount);
    if (!result) return nullptr;
    jlong values[kStatsCount] = {
        stats.tokens_generated,
        stats.prompt_tokens,
        stats.context_used,
        stats.microseconds,
        (stats.truncated ? 1 : 0) | (stats.cancelled ? 2 : 0),
    };
    env->SetLongArrayRegion(result, 0, kStatsCount, values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (ogg::BridgeSession *s = Resolve(handle)) ogg::BridgeRequestStop(s);
}

JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_nativeIsBusy(JNIEnv *, jobject, jlong handle) {
    ogg::BridgeSession *s = Resolve(handle);
    return s && ogg::BridgeIsBusy(s) ? JNI_TRUE : JNI_FALSE;
}


}  // extern "C"
