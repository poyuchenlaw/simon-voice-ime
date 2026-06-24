// JNI wrapper around llama.cpp for the on-device "smart punctuation" Punctuator.
//
// Exposes three calls to LlmPunctuator.java:
//   nativeLoad(path, nThreads)         -> load a GGUF model (one resident model)
//   nativeComplete(prompt, maxTokens, nThreads) -> greedy (temp 0) completion of a
//                                          pre-formatted prompt; returns assistant text
//   nativeFree()                       -> release model + backend
//
// The prompt formatting (Qwen2.5 ChatML system/user turns) is done in Java so the
// native layer stays a thin, model-agnostic text-in/text-out shim. Decoding is greedy
// (deterministic, temp 0) — we never want sampling variance for punctuation.
//
// §37: this runs entirely on-device. No network. The caller's text never leaves the phone.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "SmpunctLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
llama_model *       g_model = nullptr;
const llama_vocab * g_vocab = nullptr;
std::mutex          g_mutex;            // one inference at a time (IME is single-shot)
std::atomic<bool>   g_backend_inited{false};
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_simon_voiceime_correct_LlmPunctuator_nativeLoad(
        JNIEnv * env, jobject /*thiz*/, jstring jpath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) return JNI_TRUE;  // already loaded

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;

    if (!g_backend_inited.exchange(true)) {
        llama_backend_init();
        llama_log_set([](ggml_log_level lvl, const char * text, void *) {
            if (lvl >= GGML_LOG_LEVEL_ERROR) LOGW("llama: %s", text);
        }, nullptr);
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU-only on device
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("model load failed");
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);
    LOGI("model loaded (threads=%d)", (int) nThreads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_simon_voiceime_correct_LlmPunctuator_nativeComplete(
        JNIEnv * env, jobject /*thiz*/, jstring jprompt, jint maxTokens, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_vocab) return env->NewStringUTF("");

    const char * prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) return env->NewStringUTF("");
    std::string text(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);
    if (text.empty()) return env->NewStringUTF("");

    const int threads = nThreads > 0 ? (int) nThreads : 4;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = 1024;
    cp.n_batch        = 1024;
    cp.n_threads      = threads;
    cp.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(g_model, cp);
    if (!ctx) {
        LOGW("ctx init failed");
        return env->NewStringUTF("");
    }

    // Tokenize the (already chat-formatted) prompt. add_special=true, parse_special=true
    // so ChatML control tokens (<|im_start|> etc.) are recognised.
    int n_needed = -llama_tokenize(g_vocab, text.c_str(), (int) text.size(),
                                   nullptr, 0, true, true);
    if (n_needed <= 0) { llama_free(ctx); return env->NewStringUTF(""); }
    std::vector<llama_token> tokens(n_needed);
    int n_tok = llama_tokenize(g_vocab, text.c_str(), (int) text.size(),
                               tokens.data(), (int) tokens.size(), true, true);
    if (n_tok < 0) { llama_free(ctx); return env->NewStringUTF(""); }
    tokens.resize(n_tok);
    if (n_tok >= cp.n_ctx) { llama_free(ctx); return env->NewStringUTF(""); }

    // Greedy sampler: temperature 0, deterministic.
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());
    std::string out;
    int decoded = 0;
    bool ok = true;
    while (decoded < maxTokens) {
        if (llama_decode(ctx, batch) != 0) { ok = false; break; }
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;
        char buf[256];
        int n = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        batch = llama_batch_get_one(&id, 1);
        decoded++;
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    if (!ok && out.empty()) return env->NewStringUTF("");
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_simon_voiceime_correct_LlmPunctuator_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
    }
}
