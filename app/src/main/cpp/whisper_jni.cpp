// JNI-мост между Kotlin и whisper.cpp
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_cancel{false};
static std::atomic<bool> g_backends_loaded{false};

struct CallbackData {
    JNIEnv *env;
    jobject cb;
    jmethodID on_segment;
    jmethodID on_progress;
    double offset;
};

static jbyteArray to_bytes(JNIEnv *env, const char *s) {
    // текст передаём байтами: в нём может оказаться обрезанный UTF-8,
    // на котором NewStringUTF уронил бы процесс
    jsize n = s ? (jsize) strlen(s) : 0;
    jbyteArray arr = env->NewByteArray(n);
    if (n > 0) env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(s));
    return arr;
}

static void on_new_segment(whisper_context *ctx, whisper_state *, int n_new, void *user_data) {
    auto *d = static_cast<CallbackData *>(user_data);
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; ++i) {
        const double t0 = whisper_full_get_segment_t0(ctx, i) * 0.01 + d->offset;
        const double t1 = whisper_full_get_segment_t1(ctx, i) * 0.01 + d->offset;
        jbyteArray text = to_bytes(d->env, whisper_full_get_segment_text(ctx, i));
        d->env->CallVoidMethod(d->cb, d->on_segment, (jdouble) t0, (jdouble) t1, text);
        d->env->DeleteLocalRef(text);
    }
}

static void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *d = static_cast<CallbackData *>(user_data);
    d->env->CallVoidMethod(d->cb, d->on_progress, (jint) progress);
}

static bool on_encoder_begin(whisper_context *, whisper_state *, void *) {
    return !g_cancel.load();
}

static bool on_abort(void *) {
    return g_cancel.load();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_transcriber_app_whisper_WhisperLib_loadBackends(JNIEnv *env, jobject, jstring dir) {
    if (g_backends_loaded.exchange(true)) return;
    const char *path = env->GetStringUTFChars(dir, nullptr);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(dir, path);
    LOGI("backends: %zu", ggml_backend_reg_count());
}

JNIEXPORT jstring JNICALL
Java_com_transcriber_app_whisper_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_transcriber_app_whisper_WhisperLib_initContext(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_transcriber_app_whisper_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT void JNICALL
Java_com_transcriber_app_whisper_WhisperLib_cancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_transcriber_app_whisper_WhisperLib_resetCancel(JNIEnv *, jobject) {
    g_cancel.store(false);
}

JNIEXPORT jint JNICALL
Java_com_transcriber_app_whisper_WhisperLib_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray samples, jstring language,
        jint threads, jdouble offset, jstring vad_model, jobject callback) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return -100;

    jclass cls = env->GetObjectClass(callback);
    CallbackData data{env, callback,
                      env->GetMethodID(cls, "onSegment", "(DD[B)V"),
                      env->GetMethodID(cls, "onProgress", "(I)V"),
                      offset};

    std::string lang = "auto";
    if (language) {
        const char *l = env->GetStringUTFChars(language, nullptr);
        if (l[0]) lang = l;
        env->ReleaseStringUTFChars(language, l);
    }
    std::string vad;
    if (vad_model) {
        const char *v = env->GetStringUTFChars(vad_model, nullptr);
        vad = v;
        env->ReleaseStringUTFChars(vad_model, v);
    }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang.c_str();
    p.translate = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.new_segment_callback = on_new_segment;
    p.new_segment_callback_user_data = &data;
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &data;
    p.encoder_begin_callback = on_encoder_begin;
    p.abort_callback = on_abort;
    if (!vad.empty()) {
        p.vad = true;
        p.vad_model_path = vad.c_str();
    }

    const jsize n = env->GetArrayLength(samples);
    jfloat *buf = env->GetFloatArrayElements(samples, nullptr);
    const int rc = whisper_full(ctx, p, buf, n);
    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
    env->DeleteLocalRef(cls);
    if (g_cancel.load()) return -200;
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_transcriber_app_whisper_WhisperLib_detectedLanguage(JNIEnv *env, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return env->NewStringUTF("");
    const int id = whisper_full_lang_id(ctx);
    const char *s = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s ? s : "");
}

}  // extern "C"
