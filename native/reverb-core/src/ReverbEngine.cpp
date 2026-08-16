/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/reverb/ReverbEngine.h>

#include "NonUniformConvolver.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <numbers>

namespace mechana::reverb {

class ReverbEngine::Impl final {
public:
    class Biquad final {
    public:
        void setIdentity() noexcept { b0_ = 1.0; b1_ = b2_ = a1_ = a2_ = 0.0; }
        void setCutoff(const double sampleRate, const double cutoff, const bool highPass) noexcept {
            if (cutoff <= 0.0 || cutoff >= sampleRate / 2.0) {
                setIdentity();
                return;
            }
            const auto omega = 2.0 * std::numbers::pi * cutoff / sampleRate;
            const auto cosine = std::cos(omega);
            const auto alpha = std::sin(omega) / (2.0 * std::sqrt(0.5));
            const auto a0 = 1.0 + alpha;
            b0_ = (highPass ? (1.0 + cosine) / 2.0 : (1.0 - cosine) / 2.0) / a0;
            b1_ = (highPass ? -(1.0 + cosine) : 1.0 - cosine) / a0;
            b2_ = b0_;
            a1_ = -2.0 * cosine / a0;
            a2_ = (1.0 - alpha) / a0;
        }
        float process(const float input) noexcept {
            const auto output = b0_ * input + b1_ * x1_ + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
            x2_ = x1_;
            x1_ = input;
            y2_ = y1_;
            y1_ = output;
            return static_cast<float>(output);
        }
        void reset() noexcept { x1_ = x2_ = y1_ = y2_ = 0.0; }

    private:
        double b0_ { 1.0 }, b1_ {}, b2_ {}, a1_ {}, a2_ {};
        double x1_ {}, x2_ {}, y1_ {}, y2_ {};
    };

    void prepare(const double sampleRate, const std::size_t channels, const std::size_t maximumBlockSize) {
        sampleRate_ = sampleRate;
        channelCount_ = std::clamp<std::size_t>(channels, 1, 2);
        dryDelay_.assign(channelCount_, std::vector<float>(partitionSize + maximumBlockSize + 1));
        preDelay_.assign(channelCount_, std::vector<float>(partitionSize + static_cast<std::size_t>(sampleRate_ * 2.0) + 1));
        lowCut_.resize(channelCount_);
        highCut_.resize(channelCount_);
        const std::vector<std::vector<float>> delta(channelCount_, std::vector<float> { 1.0F });
        setImpulseResponse(delta);
    }

    void setImpulseResponse(const std::vector<std::vector<float>>& channels) {
        convolvers_.resize(channelCount_);
        for (std::size_t channel = 0; channel < channelCount_; ++channel) {
            const auto& ir = channels[std::min(channel, channels.size() - 1)];
            convolvers_[channel].prepare(ir);
        }
        reset();
    }

    void reset() noexcept {
        dryPosition_ = 0;
        preDelayPosition_ = 0;
        for (auto& convolver : convolvers_)
            convolver.reset();
        for (auto& channel : dryDelay_)
            std::fill(channel.begin(), channel.end(), 0.0F);
        for (auto& channel : preDelay_)
            std::fill(channel.begin(), channel.end(), 0.0F);
        for (auto& filter : lowCut_)
            filter.reset();
        for (auto& filter : highCut_)
            filter.reset();
    }

    void process(float* const* channels, const std::size_t suppliedChannels, const std::size_t frames,
                 const Parameters& parameters) noexcept {
        const auto channelsToProcess = std::min(channelCount_, suppliedChannels);
        const auto preDelayFrames = std::min<std::size_t>(
            preDelay_.front().size() - 1,
            static_cast<std::size_t>(std::max(0.0F, parameters.preDelayMilliseconds) * sampleRate_ / 1000.0));
        if (parameters.wetLowCutHertz != currentLowCut_ || parameters.wetHighCutHertz != currentHighCut_) {
            currentLowCut_ = parameters.wetLowCutHertz;
            currentHighCut_ = parameters.wetHighCutHertz;
            for (auto& filter : lowCut_)
                filter.setCutoff(sampleRate_, currentLowCut_, true);
            for (auto& filter : highCut_)
                filter.setCutoff(sampleRate_, currentHighCut_, false);
        }
        for (std::size_t frame = 0; frame < frames; ++frame) {
            for (std::size_t channel = 0; channel < channelsToProcess; ++channel) {
                const auto input = channels[channel][frame];
                auto& dry = dryDelay_[channel];
                dry[dryPosition_] = input;
                const auto dryRead = (dryPosition_ + dry.size() - partitionSize) % dry.size();
                const auto alignedDry = dry[dryRead];
                auto wet = convolvers_[channel].processSample(input);
                wet = highCut_[channel].process(lowCut_[channel].process(wet));
                auto& delay = preDelay_[channel];
                delay[preDelayPosition_] = wet;
                const auto wetRead = (preDelayPosition_ + delay.size() - preDelayFrames) % delay.size();
                channels[channel][frame] = parameters.bypass
                                               ? alignedDry
                                               : alignedDry * parameters.dryLevel
                                                     + delay[wetRead] * parameters.wetLevel;
            }
            dryPosition_ = (dryPosition_ + 1) % dryDelay_.front().size();
            preDelayPosition_ = (preDelayPosition_ + 1) % preDelay_.front().size();
        }
    }

private:
    double sampleRate_ { 48'000.0 };
    std::size_t channelCount_ { 2 };
    std::size_t dryPosition_ {};
    std::size_t preDelayPosition_ {};
    float currentLowCut_ { -1.0F };
    float currentHighCut_ { -1.0F };
    std::vector<NonUniformConvolver> convolvers_;
    std::vector<std::vector<float>> dryDelay_;
    std::vector<std::vector<float>> preDelay_;
    std::vector<Biquad> lowCut_;
    std::vector<Biquad> highCut_;
};

ReverbEngine::ReverbEngine() : impl_(std::make_unique<Impl>()) {}
ReverbEngine::~ReverbEngine() = default;
ReverbEngine::ReverbEngine(ReverbEngine&&) noexcept = default;
ReverbEngine& ReverbEngine::operator=(ReverbEngine&&) noexcept = default;

void ReverbEngine::prepare(const double sampleRate, const std::size_t channels, const std::size_t maximumBlockSize) {
    impl_->prepare(sampleRate, channels, maximumBlockSize);
}

void ReverbEngine::setImpulseResponse(const std::vector<std::vector<float>>& channels) {
    if (channels.empty() || channels.front().empty())
        return;
    impl_->setImpulseResponse(channels);
}

void ReverbEngine::reset() noexcept { impl_->reset(); }

void ReverbEngine::process(float* const* channels, const std::size_t channelCount, const std::size_t frameCount,
                           const Parameters& parameters) noexcept {
    impl_->process(channels, channelCount, frameCount, parameters);
}

std::size_t ReverbEngine::latencySamples() const noexcept { return partitionSize; }

} // namespace mechana::reverb
