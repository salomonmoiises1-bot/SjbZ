#pragma once

#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>

namespace sb_dsp {

struct NativeConfig {
    std::atomic<bool> enabled{true};
    std::atomic<float> masterLinear{1.0f};
    std::atomic<float> headroomLinear{1.0f};
};

class OboeDspEngine final : public oboe::AudioStreamDataCallback {
public:
    OboeDspEngine(int32_t sampleRate, int32_t channels, int32_t capacityFrames);
    ~OboeDspEngine() override;

    bool start();
    void stop();
    void processFloat(float* data, int32_t samples);
    void processShort(int16_t* data, int32_t samples);
    void update(float masterLinear, float headroomLinear, bool enabled);

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

private:
    int32_t sampleRate_;
    int32_t channels_;
    int32_t capacityFrames_;
    oboe::AudioStream* stream_ = nullptr;
    NativeConfig config_;
};

} // namespace sb_dsp
