/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once
#include <algorithm>
#include <cmath>

namespace mechana::audio {
class ParameterSmoother {
public:
    void prepare(double sampleRate, double rampMs = 10.0) noexcept {
        const auto samples = std::max(1.0, sampleRate * rampMs / 1000.0);
        coefficient_ = static_cast<float>(std::exp(-1.0 / samples));
    }
    void reset(float value) noexcept { current_ = target_ = value; }
    void setTarget(float value) noexcept { target_ = value; }
    [[nodiscard]] float next() noexcept {
        current_ = target_ + coefficient_ * (current_ - target_);
        return current_;
    }
    [[nodiscard]] float current() const noexcept { return current_; }
private:
    float current_{};
    float target_{};
    float coefficient_{};
};
}
