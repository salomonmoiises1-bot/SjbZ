#pragma once
#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <vector>
#include <array>
#include <algorithm>

namespace sb_dsp {

// Template SPSC Lock-Free Ring Buffer optimizado para audio en tiempo real
template<typename T, size_t Capacity>
class LockFreeRingBuffer {
private:
    // Aseguramos una capacidad potencia de 2 para optimizar operaciones con máscaras (opcional, aquí usamos módulo seguro)
    std::array<T, Capacity> buffer_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};

public:
    LockFreeRingBuffer() = default;
    ~LockFreeRingBuffer() = default;

    // Escritura (Productor - Hilo principal / Java)
    bool push(const T* data, size_t size) {
        size_t current_head = head_.load(std::memory_order_relaxed);
        size_t current_tail = tail_.load(std::memory_order_acquire);
        
        size_t free_space = (Capacity + current_tail - current_head - 1) % Capacity;
        if (free_space < size) {
            return false; // Sin espacio suficiente
        }

        for (size_t i = 0; i < size; ++i) {
            buffer_[(current_head + i) % Capacity] = data[i];
        }

        head_.store((current_head + size) % Capacity, std::memory_order_release);
        return true;
    }

    // Lectura (Consumidor - Hilo de Oboe en tiempo real)
    bool pop(T* data, size_t size) {
        size_t current_head = head_.load(std::memory_order_acquire);
        size_t current_tail = tail_.load(std::memory_order_relaxed);

        size_t available = (Capacity + current_head - current_tail) % Capacity;
        if (available < size) {
            return false; // No hay suficientes datos disponibles
        }

        for (size_t i = 0; i < size; ++i) {
            data[i] = buffer_[(current_tail + i) % Capacity];
        }

        tail_.store((current_tail + size) % Capacity, std::memory_order_release);
        return true;
    }

    size_t availableRead() const {
        size_t current_head = head_.load(std::memory_order_acquire);
        size_t current_tail = tail_.load(std::memory_order_relaxed);
        return (Capacity + current_head - current_tail) % Capacity;
    }
};

// Estructura de Filtro Bicuadrático optimizada con funciones inline
struct NativeBiquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float s1L = 0.0f, s2L = 0.0f, s1R = 0.0f, s2R = 0.0f;

    inline void processStereo(float& left, float& right) {
        // Canal Izquierdo (Direct Form II Transposed)
        float outL = b0 * left + s1L;
        s1L = b1 * left - a1 * outL + s2L;
        s2L = b2 * left - a2 * outL;
        left = outL;

        // Canal Derecho
        float outR = b0 * right + s1R;
        s1R = b1 * right - a1 * outR + s2R;
        s2R = b2 * right - a2 * outR;
        right = outR;
    }
};

// Clase principal del motor Oboe integrada como DataCallback
class OboeDspEngine : public oboe::AudioStreamDataCallback {
private:
    oboe::ManagedStream stream_;
    NativeBiquad biquadFilter_;

public:
    OboeDspEngine() = default;
    ~OboeDspEngine() {
        stop();
    }

    bool start() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(oboe::ChannelCount::Stereo);
        builder.setCallback(this);

        auto result = builder.openManagedStream(stream_);
        if (result != oboe::Result::OK) {
            return false;
        }

        result = stream_->requestStart();
        return (result == oboe::Result::OK);
    }

    void stop() {
        if (stream_) {
            stream_->requestStop();
            stream_.reset();
        }
    }

    // Callback en tiempo real ejecutado por el hilo de alta prioridad de Oboe
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        auto *outputBuffer = static_cast<float *>(audioData);
        int32_t channelCount = oboeStream->getChannelCount();

        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t idx = i * channelCount;
            float sampleL = outputBuffer[idx];
            float sampleR = (channelCount > 1) ? outputBuffer[idx + 1] : sampleL;

            // Procesar audio estéreo con el filtro bicuadrático en tiempo real
            biquadFilter_.processStereo(sampleL, sampleR);

            outputBuffer[idx] = sampleL;
            if (channelCount > 1) {
                outputBuffer[idx + 1] = sampleR;
            }
        }

        return oboe::DataCallbackResult::Continue;
    }
};

} // namespace sb_dsp
