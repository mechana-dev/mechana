/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once

#include <algorithm>
#include <cmath>

namespace mechana::audio {
inline float decibelsToGain(const float decibels) noexcept {
    return std::pow(10.0F, decibels / 20.0F);
}

inline float equalPowerDry(const float mix) noexcept {
    return std::cos(std::clamp(mix, 0.0F, 1.0F) * 1.57079632679F);
}

inline float equalPowerWet(const float mix) noexcept {
    return std::sin(std::clamp(mix, 0.0F, 1.0F) * 1.57079632679F);
}

class PeakMeter final {
public:
    void reset() noexcept { peak_ = 0.0F; }
    void push(const float sample) noexcept { peak_ = std::max(peak_, std::abs(sample)); }
    [[nodiscard]] float peak() const noexcept { return peak_; }

private:
    float peak_ {};
};
} // namespace mechana::audio
