/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once
#include <cstddef>
#include "mechana/audio/ParameterSmoother.h"

namespace mechana::fuzz {
struct Parameters {
    float drive = 0.65f;
    float tone = 0.5f;
    float level = 0.8f;
    float octave = 0.65f;
    bool bypass = false;
};

class OctaveFuzzEngine {
public:
    void prepare(double sampleRate, std::size_t channels);
    void reset() noexcept;
    void setParameters(const Parameters& parameters) noexcept;
    void process(float* const* channels, std::size_t channelCount, std::size_t frames) noexcept;

private:
    struct ChannelState {
        float dcIn{};
        float dcOut{};
        float toneState{};
    };
    double sampleRate_ = 48000.0;
    Parameters target_{};
    mechana::audio::ParameterSmoother drive_;
    mechana::audio::ParameterSmoother tone_;
    mechana::audio::ParameterSmoother level_;
    mechana::audio::ParameterSmoother octave_;
    ChannelState states_[2]{};
};
}
