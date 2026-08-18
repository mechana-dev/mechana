/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once

#include <algorithm>
#include <cmath>

namespace mechana::audio {
class DcBlocker final {
public:
    void prepare(const double sampleRate, const float cutoffHertz = 15.0F) noexcept {
        pole_ = std::exp(-6.28318530718F * cutoffHertz / static_cast<float>(std::max(1.0, sampleRate)));
    }
    void reset() noexcept { previousInput_ = previousOutput_ = 0.0F; }
    float process(const float input) noexcept {
        const float output = input - previousInput_ + pole_ * previousOutput_;
        previousInput_ = input;
        previousOutput_ = output;
        return output;
    }

private:
    float previousInput_ {};
    float previousOutput_ {};
    float pole_ { 0.995F };
};

class OnePoleLowPass final {
public:
    void setCutoff(const double sampleRate, const float cutoffHertz) noexcept {
        coefficient_ = 1.0F - std::exp(-6.28318530718F * std::clamp(cutoffHertz, 1.0F,
            static_cast<float>(sampleRate * 0.45)) / static_cast<float>(sampleRate));
    }
    void reset() noexcept { state_ = 0.0F; }
    float process(const float input) noexcept {
        state_ += coefficient_ * (input - state_);
        return state_;
    }

private:
    float coefficient_ { 1.0F };
    float state_ {};
};
} // namespace mechana::audio
