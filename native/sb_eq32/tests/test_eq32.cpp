#include "sb_eq32.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

int main() {
    for (float sr : {44100.f, 48000.f, 96000.f}) {
        sb::Eq32 eq;
        for (int run=0; run<100; ++run) {
            std::mt19937 rng(run + static_cast<int>(sr));
            std::uniform_real_distribution<float> d(-24.f,24.f);
            std::array<float,32> g{};
            for (auto& x : g) x=d(rng);
            eq.configure(sr,g);
            std::vector<float> pcm(4096*2);
            std::uniform_real_distribution<float> p(-1.f,1.f);
            for (auto& x:pcm) x=p(rng);
            eq.process(pcm.data(),4096,2);
            for (float x:pcm) if (!std::isfinite(x) || std::abs(x)>16.f) return 2;
        }
    }
    std::cout << "SB EQ32 native stress PASS: 300 configurations / 3 sample rates\n";
    return 0;
}
