/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once

#include <array>
#include <cstddef>
#include <utility>

namespace mechana::audio {
// Streaming 2x oversampling for nonlinear processors. Two matched, linear-phase
// 17-tap FIRs provide image/alias rejection and a deterministic eight-sample
// base-rate latency. No allocation occurs while processing.
class TwoTimesOversampler final {
public:
    static constexpr std::size_t latencySamples = 8;

    void reset() noexcept {
        upHistory_.fill(0.0F);
        downHistory_.fill(0.0F);
        upPosition_ = downPosition_ = 0;
    }

    template <typename Processor>
    float process(const float input, Processor&& processor) noexcept {
        float output = 0.0F;
        for (std::size_t phase = 0; phase < 2; ++phase) {
            push(upHistory_, upPosition_, phase == 0 ? input * 2.0F : 0.0F);
            const float oversampled = convolve(upHistory_, upPosition_);
            const float shaped = std::forward<Processor>(processor)(oversampled);
            push(downHistory_, downPosition_, shaped);
            const float filtered = convolve(downHistory_, downPosition_);
            if (phase == 0)
                output = filtered;
        }
        return output;
    }

private:
    // Windowed-sinc low-pass, cutoff at one quarter of the 2x rate.
    static constexpr std::array<float, 17> coefficients_ {
        0.0F, -0.00523918F, 0.0F, 0.02321110F, 0.0F, -0.07610584F, 0.0F,
        0.30769878F, 0.50087029F, 0.30769878F, 0.0F, -0.07610584F, 0.0F,
        0.02321110F, 0.0F, -0.00523918F, 0.0F
    };

    static void push(std::array<float, 17>& history, std::size_t& position, const float sample) noexcept {
        history[position] = sample;
        position = (position + 1) % history.size();
    }
    static float convolve(const std::array<float, 17>& history, const std::size_t position) noexcept {
        float result = 0.0F;
        for (std::size_t tap = 0; tap < coefficients_.size(); ++tap)
            result += coefficients_[tap] * history[(position + history.size() - 1 - tap) % history.size()];
        return result;
    }

    std::array<float, 17> upHistory_ {};
    std::array<float, 17> downHistory_ {};
    std::size_t upPosition_ {};
    std::size_t downPosition_ {};
};
} // namespace mechana::audio
