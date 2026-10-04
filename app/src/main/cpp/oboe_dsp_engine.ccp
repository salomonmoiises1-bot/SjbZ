include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>

#define LOG_TAG "SbDspEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class SbAudioStreamCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        // Aquí es donde tu motor DSP procesará el audio en tiempo real (Buffers PCM)
        // Por defecto, generamos silencio o pasamos el buffer para evitar bloqueos
        auto *outputBuffer = static_cast<float *>(audioData);
        
        for (int32_t i = 0; i < numFrames * oboeStream->getChannelCount(); ++i) {
            outputBuffer[i] = 0.0f; // Rellenar con audio procesado / silencio temporalmente
        }
        
        return oboe::DataCallbackResult::Continue;
    }
};

static oboe::ManagedStream g_stream;
static SbAudioStreamCallback g_audioCallback;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_nativeStartEngine(JNIEnv *env, jobject thiz) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setCallback(&g_audioCallback);

    oboe::Result result = builder.openManagedStream(g_stream);
    if (result != oboe::Result::OK) {
        LOGE("Error al abrir el flujo de Oboe: %s", oboe::convertToText(result));
        return JNI_FALSE;
    }

    result = g_stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Error al iniciar el flujo de Oboe: %s", oboe::convertToText(result));
        return JNI_FALSE;
    }

    LOGI("Motor Oboe iniciado correctamente con ultra baja latencia.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sb_dsp_jni_NativeDspBridge_nativeStopEngine(JNIEnv *env, jobject thiz) {
    if (g_stream) {
        g_stream->requestStop();
        g_stream.reset();
        LOGI("Motor Oboe detenido.");
    }
}
