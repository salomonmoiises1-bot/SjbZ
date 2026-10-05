#include "sb_eq32.h"
#include <jni.h>
#include <array>

extern "C" JNIEXPORT jlong JNICALL
Java_com_sb_sb_dsp_NativeEq32_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new sb::Eq32());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_sb_dsp_NativeEq32_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<sb::Eq32*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_sb_dsp_NativeEq32_nativeConfigure(JNIEnv* env, jclass, jlong handle, jfloat sample_rate, jfloatArray gains) {
    auto* eq = reinterpret_cast<sb::Eq32*>(handle);
    if (!eq || !gains || env->GetArrayLength(gains) < 32) return;
    std::array<float, sb::kBandCount> g{};
    env->GetFloatArrayRegion(gains, 0, 32, g.data());
    eq->configure(sample_rate, g);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_sb_dsp_NativeEq32_nativeProcess(JNIEnv* env, jclass, jlong handle, jfloatArray pcm, jint frames, jint channels) {
    auto* eq = reinterpret_cast<sb::Eq32*>(handle);
    if (!eq || !pcm || frames <= 0 || channels <= 0) return;
    const jsize needed = static_cast<jsize>(frames * channels);
    if (env->GetArrayLength(pcm) < needed) return;
    jfloat* p = env->GetFloatArrayElements(pcm, nullptr);
    if (!p) return;
    eq->process(p, static_cast<std::size_t>(frames), static_cast<std::size_t>(channels));
    env->ReleaseFloatArrayElements(pcm, p, 0);
}
