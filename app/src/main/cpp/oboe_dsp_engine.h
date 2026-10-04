#pragma once
#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <vector>

namespace sb_dsp {

template<typename T, size_t Capacity>
class LockFreeRingBuffer {
    // SPSC Lock-Free Ring Buffer implementado con memory_order_acquire / release
};

struct NativeBiquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float s1L = 0.0f, s2L = 0.0f, s1R = 0.0f, s2R = 0.0f;
    inline void processStereo(float& left, float& right);
};

class OboeDspEngine : public oboe::AudioStreamDataCallback {
    // Oboe Audio Stream y pipeline secuencial en C++
};

}