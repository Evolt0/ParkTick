#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "hyper_lpr_sdk.h"

namespace {
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
    }
}

struct Pixels {
    JNIEnv* env;
    jobject bitmap;
    void* data = nullptr;
    Pixels(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &data) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Unable to lock photograph pixels");
    }
    ~Pixels() { AndroidBitmap_unlockPixels(env, bitmap); }
};
struct BufferDeleter {
    void operator()(HLPR_DataBuffer* buffer) const { if (buffer) HLPR_ReleaseDataBuffer(buffer); }
};
using Buffer = std::unique_ptr<HLPR_DataBuffer, BufferDeleter>;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_evolto_parktick_recognition_NativeBridge_create(JNIEnv* env, jobject, jstring directory) {
    const char* path = env->GetStringUTFChars(directory, nullptr);
    if (!path) return 0;
    std::string model_path(path);
    env->ReleaseStringUTFChars(directory, path);
    P_HLPR_Context context = nullptr;
    try {
        HLPR_ContextConfiguration config{};
        config.models_path = model_path.data();
        config.max_num = 5;
        config.threads = 2;
        config.use_half = false;
        config.box_conf_threshold = 0.25f;
        config.nms_threshold = 0.5f;
        config.rec_confidence_threshold = 0.75f;
        config.det_level = DETECT_LEVEL_HIGH;
        context = HLPR_CreateContext(&config);
        if (!context || HLPR_ContextQueryStatus(context) != Ok) {
            if (context) HLPR_ReleaseContext(context);
            fail(env, "Offline plate model initialization failed");
            return 0;
        }
        return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
    } catch (const std::exception& error) {
        if (context) HLPR_ReleaseContext(context);
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_evolto_parktick_recognition_NativeBridge_recognize(JNIEnv* env, jobject, jlong handle, jobject bitmap) {
    auto context = reinterpret_cast<P_HLPR_Context>(static_cast<intptr_t>(handle));
    if (!context || HLPR_ContextQueryStatus(context) != Ok) { fail(env, "Plate recognizer is not initialized"); return nullptr; }
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
            throw std::runtime_error("Expected an RGBA photograph");
        Pixels pixels(env, bitmap);
        // Android RGBA_8888 pixels are not the old Java int[]/BGRA entry point.
        std::vector<uint8_t> packed;
        auto* data = static_cast<uint8_t*>(pixels.data);
        const size_t row_bytes = static_cast<size_t>(info.width) * 4;
        if (info.stride != row_bytes) {
            packed.resize(row_bytes * info.height);
            for (uint32_t row = 0; row < info.height; ++row)
                std::memcpy(packed.data() + row * row_bytes, data + row * info.stride, row_bytes);
            data = packed.data();
        }
        HLPR_ImageData image{data, static_cast<int>(info.width), static_cast<int>(info.height), STREAM_RGBA, CAMERA_ROTATION_0};
        Buffer buffer(HLPR_CreateDataBuffer(&image));
        if (!buffer) throw std::runtime_error("Unable to allocate recognition input");
        HLPR_PlateResultList results{};
        if (HLPR_ContextUpdateStream(context, buffer.get(), &results) != Ok)
            throw std::runtime_error("Offline recognition failed");
        jclass candidate = env->FindClass("com/evolto/parktick/recognition/PlateCandidate");
        if (!candidate) return nullptr;
        jmethodID constructor = env->GetMethodID(candidate, "<init>", "(Ljava/lang/String;FIFFFF)V");
        if (!constructor) { env->DeleteLocalRef(candidate); return nullptr; }
        jobjectArray output = env->NewObjectArray(static_cast<jsize>(results.plate_size), candidate, nullptr);
        if (!output) { env->DeleteLocalRef(candidate); return nullptr; }
        for (unsigned long i = 0; i < results.plate_size; ++i) {
            const auto& result = results.plates[i];
            jstring code = env->NewStringUTF(result.code);
            if (!code) break;
            jobject value = env->NewObject(candidate, constructor, code, result.text_confidence,
                static_cast<jint>(result.type), result.x1, result.y1, result.x2, result.y2);
            env->DeleteLocalRef(code);
            if (!value) break;
            env->SetObjectArrayElement(output, static_cast<jsize>(i), value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) break;
        }
        env->DeleteLocalRef(candidate);
        return env->ExceptionCheck() ? nullptr : output;
    } catch (const std::exception& error) {
        fail(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_evolto_parktick_recognition_NativeBridge_destroy(JNIEnv*, jobject, jlong handle) {
    auto context = reinterpret_cast<P_HLPR_Context>(static_cast<intptr_t>(handle));
    if (context) HLPR_ReleaseContext(context);
}
