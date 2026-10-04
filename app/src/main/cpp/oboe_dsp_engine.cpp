#include "oboe_dsp_engine.h"
#include <algorithm>
#include <cmath>

using sb_dsp::OboeDspEngine;

OboeDspEngine::OboeDspEngine(int32_t sampleRate, int32_t channels, int32_t capacityFrames)
    : sampleRate_(sampleRate), channels_(channels), capacityFrames_(capacityFrames) {}

OboeDspEngine::~OboeDspEngine() {
    stop();
}

bool OboeDspEngine::start() {
    if (stream_ != nullptr) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(channels_)
        ->setSampleRate(sampleRate_)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setDataCallback(this);

    oboe::Result result = builder.openStream(&stream_);
    if (result != oboe::Result::OK) {
        stream_ = nullptr;
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(&stream_);
    }
    if (result != oboe::Result::OK || stream_ == nullptr) {
        stream_ = nullptr;
        return false;
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        stream_->close();
        stream_ = nullptr;
        return false;
    }
    return true;
}

void OboeDspEngine::stop() {
    if (stream_ == nullptr) return;
    stream_->requestStop();
    stream_->close();
    stream_ = nullptr;
}

void OboeDspEngine::update(float masterLinear, float headroomLinear, bool enabled) {
    config_.masterLinear.store(masterLinear, std::memory_order_release);
    config_.headroomLinear.store(headroomLinear, std::memory_order_release);
    config_.enabled.store(enabled, std::memory_order_release);
}

void OboeDspEngine::processFloat(float* data, int32_t samples) {
    if (data == nullptr || samples <= 0) return;
    if (!config_.enabled.load(std::memory_order_acquire)) return;

    const float gain =
        config_.masterLinear.load(std::memory_order_relaxed) *
        config_.headroomLinear.load(std::memory_order_relaxed);

    for (int32_t i = 0; i < samples; ++i) {
        data[i] = std::clamp(data[i] * gain, -0.9999f, 0.9999f);
    }
}

void OboeDspEngine::processShort(int16_t* data, int32_t samples) {
    if (data == nullptr || samples <= 0) return;
    if (!config_.enabled.load(std::memory_order_acquire)) return;

    const float gain =
        config_.masterLinear.load(std::memory_order_relaxed) *
        config_.headroomLinear.load(std::memory_order_relaxed);

    for (int32_t i = 0; i < samples; ++i) {
        const float x = static_cast<float>(data[i]) / 32768.0f;
        const float y = std::clamp(x * gain, -1.0f, 0.999969f);
        data[i] = static_cast<int16_t>(std::lrintf(y * 32767.0f));
    }
}

oboe::DataCallbackResult OboeDspEngine::onAudioReady(
    oboe::AudioStream*,
    void* audioData,
    int32_t numFrames) {

    // Oboe is the app-owned PCM backend. It does not receive another app's
    // PCM stream automatically. This callback therefore emits a silent
    // stream unless a producer is connected in a future PCM transport layer.
    auto* out = static_cast<float*>(audioData);
    const int32_t samples = numFrames * channels_;
    std::fill(out, out + samples, 0.0f);
    return oboe::DataCallbackResult::Continue;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_initEngine(
    JNIEnv*, jobject, jint sampleRate, jint channels, jint bufferCapacityFrames) {
    auto* engine = new OboeDspEngine(sampleRate, channels, bufferCapacityFrames);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_updateEngineConfig(
    JNIEnv* env, jobject, jlong ptr, jboolean masterEnabled, jboolean,
    jint, jfloatArray, jfloat pregainLinear, jfloat,
    jboolean, jfloat, jfloat, jfloat, jfloat headroomMarginLinear) {

    auto* engine = reinterpret_cast<OboeDspEngine*>(ptr);
    if (!engine) return;
    engine->update(pregainLinear, headroomMarginLinear, masterEnabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_processDirectBuffer(
    JNIEnv* env, jobject, jlong ptr, jobject directBuffer, jint frameCount) {
    auto* engine = reinterpret_cast<OboeDspEngine*>(ptr);
    if (!engine || !directBuffer || frameCount <= 0) return;
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!data) return;
    engine->processFloat(data, frameCount * 2);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_processShortArray(
    JNIEnv* env, jobject, jlong ptr, jshortArray inputOutput, jint offset, jint count) {
    auto* engine = reinterpret_cast<OboeDspEngine*>(ptr);
    if (!engine || !inputOutput || count <= 0) return;
    jshort* data = env->GetShortArrayElements(inputOutput, nullptr);
    if (!data) return;
    engine->processShort(reinterpret_cast<int16_t*>(data + offset), count);
    env->ReleaseShortArrayElements(inputOutput, data, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_startOboeStream(
    JNIEnv*, jobject, jlong ptr) {
    auto* engine = reinterpret_cast<OboeDspEngine*>(ptr);
    return engine && engine->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_stopOboeStream(
    JNIEnv*, jobject, jlong ptr) {
    auto* engine = reinterpret_cast<OboeDspEngine*>(ptr);
    if (engine) engine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_destroyEngine(
    JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<OboeDspEngine*>(ptr);
}
