/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once

#include "ParameterSmoother.h"

#include <algorithm>

namespace mechana::audio {

struct DryWetGains final {
    float dry;
    float wet;
};

class DryWetMixer final {
public:
    void prepare(const double sampleRate, const double rampMilliseconds = 10.0) noexcept {
        smoother_.prepare(sampleRate, rampMilliseconds);
    }
    void reset(const float mix) noexcept { smoother_.reset(clamp(mix)); }
    void setMix(const float mix) noexcept { smoother_.setTarget(clamp(mix)); }
    [[nodiscard]] DryWetGains next() noexcept {
        const auto wet = smoother_.next();
        return { 1.0F - wet, wet };
    }
    [[nodiscard]] float currentMix() const noexcept { return smoother_.current(); }

private:
    [[nodiscard]] static float clamp(const float mix) noexcept { return std::clamp(mix, 0.0F, 1.0F); }
    ParameterSmoother smoother_;
};

} // namespace mechana::audio
