#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace sb {

constexpr std::size_t kBandCount = 32;
constexpr float kDefaultQ = 4.318f;

class Biquad {
public:
    void set(float sample_rate, float frequency_hz, float gain_db, float q = kDefaultQ);
    float process(float x);
    void reset();
private:
    double b0_ = 1.0, b1_ = 0.0, b2_ = 0.0;
    double a1_ = 0.0, a2_ = 0.0;
    double x1_ = 0.0, x2_ = 0.0, y1_ = 0.0, y2_ = 0.0;
};

class Eq32 {
public:
    Eq32();
    void configure(float sample_rate, const std::array<float, kBandCount>& gains_db);
    void reset();
    void process(float* interleaved, std::size_t frames, std::size_t channels);
    std::array<float, kBandCount> gains() const { return gains_db_; }
private:
    static constexpr std::array<float, kBandCount> kFrequencies = {
        20.f,25.f,31.f,40.f,50.f,63.f,80.f,100.f,125.f,160.f,200.f,250.f,
        315.f,400.f,500.f,630.f,800.f,1000.f,1250.f,1600.f,2000.f,2500.f,
        3150.f,4000.f,5000.f,6300.f,8000.f,10000.f,12500.f,14000.f,16000.f,20000.f
    };
    float sample_rate_ = 48000.f;
    std::array<float, kBandCount> gains_db_{};
    std::array<Biquad, kBandCount> left_{};
    std::array<Biquad, kBandCount> right_{};
};

} // namespace sb
