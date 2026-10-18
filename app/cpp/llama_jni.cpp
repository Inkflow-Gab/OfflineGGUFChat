#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <android/log.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "llama_jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llama_jni", __VA_ARGS__)

#define THROW_RUNTIME(env, msg) \
    do { \
        jclass cls = env->FindClass("java/lang/RuntimeException"); \
        if (cls) env->ThrowNew(cls, msg); \
        LOGE("%s", msg); \
    } while (0)

struct NativeSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampling_context * sampling = nullptr;
    std::vector<llama_token> tokens;
    std::string last_error;
    volatile bool running = false;
    bool is_multimodal = false;
    int n_ctx = 512;
};

static NativeSession * get_session(JNIEnv * /* env */, jlong handle) {
    return reinterpret_cast<NativeSession *>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaCreateSession(
        JNIEnv * /* env */, jobject /* thiz */) {
    return reinterpret_cast<jlong>(new NativeSession());
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaDestroySession(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(nullptr, handle);
    if (!s) return;
    if (s->sampling) llama_sampling_free(s->sampling);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_free_model(s->model);
    delete s;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaLoadModel(
        JNIEnv * env, jobject /* thiz */, jlong handle, jstring path,
        jint n_ctx, jint n_gpu_layers, jint n_threads, jint n_batch,
        jboolean use_mmap, jboolean use_mlock) {

    NativeSession * s = get_session(env, handle);
    if (!s) { THROW_RUNTIME(env, "session null"); return JNI_FALSE; }

    const char * cpath = env->GetStringUTFChars(path, nullptr);
    if (!cpath) return JNI_FALSE;

    // Fast magic-byte check (only reads 4 bytes, works on 1 GB files)
    {
        int fd = open(cpath, O_RDONLY);
        bool ok = false;
        if (fd >= 0) {
            char magic[5] = {0};
            if (read(fd, magic, 4) == 4 && std::string(magic, 4) == "GGUF") ok = true;
            close(fd);
        }
        if (!ok) {
            s->last_error = "Not a valid GGUF file (bad magic bytes)";
            env->ReleaseStringUTFChars(path, cpath);
            return JNI_FALSE;
        }
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.use_mmap = use_mmap ? 1 : 0;   // demand-paged -> 1 GB GGUF OK on weak phones
    mp.use_mlock = use_mlock ? 1 : 0; // false on low-RAM devices

    s->model = llama_load_model_from_file(cpath, mp);
    env->ReleaseStringUTFChars(path, cpath);

    if (!s->model) {
        s->last_error = "llama_load_model_from_file failed (OOM or unsupported arch)";
        return JNI_FALSE;
    }

    s->n_ctx = n_ctx;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;

    s->ctx = llama_new_context_with_model(s->model, cp);
    if (!s->ctx) {
        s->last_error = "llama_new_context_with_model failed";
        llama_free_model(s->model);
        s->model = nullptr;
        return JNI_FALSE;
    }

    llama_sampling_params sp = llama_sampling_defaults();
    s->sampling = llama_sampling_init(sp);

    s->is_multimodal = (llama_model_has_vision(s->model) != 0);
    s->tokens.clear();
    s->running = false;
    s->last_error.clear();

    LOGI("Model loaded: %s (n_ctx=%d, mmap=%d, multimodal=%d)",
         cpath, n_ctx, (int)use_mmap, (int)s->is_multimodal);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaIsMultimodal(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(nullptr, handle);
    return s && s->is_multimodal ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaGetLastError(
        JNIEnv * env, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(env, handle);
    return env->NewStringUTF(s ? s->last_error.c_str() : "session null");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaNVocab(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(nullptr, handle);
    return s && s->model ? llama_n_vocab(s->model) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaNCtxTrain(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(nullptr, handle);
    return s && s->model ? llama_n_ctx_train(s->model) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaArch(
        JNIEnv * env, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(env, handle);
    return s && s->model ? env->NewStringUTF(llama_arch_name(s->model))
                         : env->NewStringUTF("unknown");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaGenerate(
        JNIEnv * env, jobject /* thiz */, jlong handle, jstring prompt,
        jint n_predict, jfloat temperature, jfloat top_p, jint top_k,
        jfloat repeat_penalty, jint seed, jobject callback) {

    NativeSession * s = get_session(env, handle);
    if (!s || !s->ctx) { THROW_RUNTIME(env, "no context"); return JNI_FALSE; }
    if (s->running) return JNI_FALSE;

    const char * cprompt = env->GetStringUTFChars(prompt, nullptr);
    if (!cprompt) return JNI_FALSE;

    // ---------- Tokenize ----------
    std::vector<llama_token> embd = llama_tokenize(s->model, cprompt, true);
    env->ReleaseStringUTFChars(prompt, cprompt);

    // Truncate front to fit context (keep system + recent tail)
    if (embd.size() > (size_t)s->n_ctx) {
        size_t drop = embd.size() - s->n_ctx + 8;
        embd.erase(embd.begin(), embd.begin() + drop);
    }

    // ---------- JNI callback method IDs ----------
    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID mid_token = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID mid_done  = env->GetMethodID(cb_cls, "onDone", "()V");
    jmethodID mid_err   = env->GetMethodID(cb_cls, "onError", "(Ljava/lang/String;)V");

    // ---------- Decode prompt ----------
    if (llama_decode(s->ctx, embd.data(), embd.size(), 0) != 0) {
        s->last_error = "llama_decode (prompt) failed";
        s->running = false;
        if (mid_err) env->CallVoidMethod(callback, mid_err, env->NewStringUTF(s->last_error.c_str()));
        return JNI_FALSE;
    }

    s->running = true;
    s->tokens = embd;

    // ---------- Sampling loop ----------
    llama_sampling_params sp = llama_sampling_defaults();
    sp.temp = temperature;
    sp.top_p = top_p;
    sp.top_k = top_k;
    sp.repeat_penalty = repeat_penalty;
    sp.seed = seed;

    int generated = 0;

    while (s->running && generated < n_predict) {
        llama_token tok = llama_sample_token(s->ctx, &sp, nullptr);

        if (tok == llama_token_eos(s->model)) break;

        s->tokens.push_back(tok);
        generated++;

        // Stream token text to Kotlin
        char buf[512];
        int n = llama_token_to_piece(s->ctx, tok, buf, sizeof(buf), 0);
        if (n > 0 && mid_token) {
            jstring jtok = env->NewStringUTF(std::string(buf, (size_t)n).c_str());
            env->CallVoidMethod(callback, mid_token, jtok);
            env->DeleteLocalRef(jtok);
        }

        // Feed sampled token back for next step
        if (llama_decode(s->ctx, &tok, 1, generated) != 0) {
            s->last_error = "llama_decode (step) failed";
            break;
        }
    }

    s->running = false;
    if (mid_done) env->CallVoidMethod(callback, mid_done);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaStop(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(nullptr, handle);
    if (s) s->running = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaGetTokenCount(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    NativeSession * s = get_session(nullptr, handle);
    return s ? (jint)s->tokens.size() : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlinegguf_chat_inference_LlamaNative_llamaSupportsMmap(
        JNIEnv * /* env */, jobject /* thiz */) {
    return JNI_TRUE;
}
