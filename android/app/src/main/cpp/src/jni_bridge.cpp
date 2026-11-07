#include <jni.h>

#include <cstdint>

#include "audio_engine.h"

// JNI 桥。Kotlin 侧声明在 object com.echolink.app.audio.NativeAudioEngine 里，
// 所以这些是实例方法（第二个参数是 jobject，不使用）。
// 约定：所有 native 方法都不抛 Java 异常，失败一律用返回码表达。

namespace {
inline echolink::AudioEngine* engineOf(jlong handle) {
    return reinterpret_cast<echolink::AudioEngine*>(handle);
}
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeApiVersion(JNIEnv* /*env*/, jobject /*self*/) {
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeStatsCount(JNIEnv* /*env*/, jobject /*self*/) {
    return echolink::kStatsCount;
}

JNIEXPORT jlong JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeCreate(JNIEnv* /*env*/, jobject /*self*/) {
    auto* engine = new echolink::AudioEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeDestroy(JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    delete engineOf(handle);
}

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeStart(
        JNIEnv* /*env*/, jobject /*self*/, jlong handle, jint sampleRate, jboolean exclusive) {
    auto* engine = engineOf(handle);
    return engine == nullptr ? -1 : engine->start(sampleRate, exclusive == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    return engine == nullptr ? -1 : engine->stop();
}

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeReopen(JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    return engine == nullptr ? -1 : engine->reopen();
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativePush(
        JNIEnv* env, jobject /*self*/, jlong handle, jlong refFrameIndex, jobject buffer,
        jint offsetBytes, jint frames) {
    auto* engine = engineOf(handle);
    if (engine == nullptr || buffer == nullptr || frames <= 0) {
        return;
    }

    // 直接取 DirectByteBuffer 的裸地址，避免每个包再拷一次（200 包/秒）
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) {
        return;
    }

    auto* pcm = reinterpret_cast<const int16_t*>(base + offsetBytes);
    engine->pushFrames(refFrameIndex, pcm, frames);
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeSetTargetWaterFrames(
        JNIEnv* /*env*/, jobject /*self*/, jlong handle, jint frames) {
    auto* engine = engineOf(handle);
    if (engine != nullptr) {
        engine->setTargetWaterFrames(frames);
    }
}

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeGetTargetWaterFrames(
        JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    return engine == nullptr ? 0 : engine->targetWaterFrames();
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeSetResampleRatio(
        JNIEnv* /*env*/, jobject /*self*/, jlong handle, jdouble ratio) {
    auto* engine = engineOf(handle);
    if (engine != nullptr) {
        engine->setResampleRatio(ratio);
    }
}

JNIEXPORT jdouble JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeGetResampleRatio(
        JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    return engine == nullptr ? 1.0 : engine->resampleRatio();
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativePlayBeep(JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    if (engine != nullptr) {
        engine->playBeep();
    }
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeRequestPrefill(JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    if (engine != nullptr) {
        engine->requestPrefill();
    }
}

JNIEXPORT jint JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeSetBufferSizeInBursts(
        JNIEnv* /*env*/, jobject /*self*/, jlong handle, jint bursts) {
    auto* engine = engineOf(handle);
    return engine == nullptr ? -1 : engine->setBufferSizeInBursts(bursts);
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeResetRing(JNIEnv* /*env*/, jobject /*self*/, jlong handle) {
    auto* engine = engineOf(handle);
    if (engine != nullptr) {
        engine->resetRing();
    }
}

JNIEXPORT void JNICALL
Java_com_echolink_app_audio_NativeAudioEngine_nativeGetStats(
        JNIEnv* env, jobject /*self*/, jlong handle, jdoubleArray out) {
    auto* engine = engineOf(handle);
    if (engine == nullptr || out == nullptr) {
        return;
    }

    const jsize length = env->GetArrayLength(out);
    if (length < echolink::kStatsCount) {
        return;
    }

    // Kotlin 侧复用一个预分配的数组，这里只做一次区域拷贝
    jdouble buffer[echolink::kStatsCount];
    engine->fillStats(buffer, echolink::kStatsCount);
    env->SetDoubleArrayRegion(out, 0, echolink::kStatsCount, buffer);
}

}  // extern "C"
