#include "sb_eq32.h"
#include <algorithm>
#include <cmath>

namespace sb {
namespace {
constexpr double kPi = 3.1415926535897932384626433832795;
constexpr float kMinGain = -24.f;
constexpr float kMaxGain = 24.f;
constexpr float kMinQ = 0.25f;
constexpr float kMaxQ = 20.f;
inline float clampf(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }
}

void Biquad::set(float sample_rate, float frequency_hz, float gain_db, float q) {
    sample_rate = std::max(8000.f, sample_rate);
    const float nyquist = sample_rate * 0.49f;
    frequency_hz = clampf(frequency_hz, 1.f, nyquist);
    gain_db = clampf(gain_db, kMinGain, kMaxGain);
    q = clampf(q, kMinQ, kMaxQ);

    const double A = std::pow(10.0, static_cast<double>(gain_db) / 40.0);
    const double w0 = 2.0 * kPi * static_cast<double>(frequency_hz) / static_cast<double>(sample_rate);
    const double alpha = std::sin(w0) / (2.0 * static_cast<double>(q));
    const double c = std::cos(w0);
    const double raw_b0 = 1.0 + alpha * A;
    const double raw_b1 = -2.0 * c;
    const double raw_b2 = 1.0 - alpha * A;
    const double raw_a0 = 1.0 + alpha / A;
    const double raw_a1 = -2.0 * c;
    const double raw_a2 = 1.0 - alpha / A;
    b0_ = raw_b0 / raw_a0;
    b1_ = raw_b1 / raw_a0;
    b2_ = raw_b2 / raw_a0;
    a1_ = raw_a1 / raw_a0;
    a2_ = raw_a2 / raw_a0;
}

float Biquad::process(float x) {
    const double xd = static_cast<double>(x);
    const double y = b0_ * xd + b1_ * x1_ + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
    x2_ = x1_; x1_ = xd; y2_ = y1_; y1_ = y;
    return static_cast<float>(std::clamp(y, -16.0, 16.0));
}

void Biquad::reset() { x1_=x2_=y1_=y2_=0.0; }

Eq32::Eq32() { configure(sample_rate_, gains_db_); }

void Eq32::configure(float sample_rate, const std::array<float, kBandCount>& gains_db) {
    sample_rate_ = std::max(8000.f, sample_rate);
    gains_db_ = gains_db;
    for (std::size_t i=0; i<kBandCount; ++i) {
        gains_db_[i] = clampf(gains_db_[i], kMinGain, kMaxGain);
        left_[i].set(sample_rate_, kFrequencies[i], gains_db_[i]);
        right_[i].set(sample_rate_, kFrequencies[i], gains_db_[i]);
    }
}

void Eq32::reset() {
    for (auto& f : left_) f.reset();
    for (auto& f : right_) f.reset();
}

void Eq32::process(float* interleaved, std::size_t frames, std::size_t channels) {
    if (!interleaved || frames == 0 || channels == 0) return;
    const std::size_t c = std::min<std::size_t>(channels, 2);
    for (std::size_t n=0; n<frames; ++n) {
        float l = interleaved[n*channels];
        for (auto& f : left_) l = f.process(l);
        interleaved[n*channels] = l;
        if (c > 1) {
            float r = interleaved[n*channels+1];
            for (auto& f : right_) r = f.process(r);
            interleaved[n*channels+1] = r;
        }
    }
}

} // namespace sb
