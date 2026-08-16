/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
#include <mechana/echo/EchoEngine.h>

#include <algorithm>
#include <cassert>
#include <cmath>
#include <numbers>
#include <vector>

namespace mechana::echo {
namespace {
constexpr float minimumDelaySamples = 1.0F;

float interpolate(const std::vector<float>& buffer, const double position) noexcept {
    const auto size = static_cast<double>(buffer.size());
    auto wrapped = std::fmod(position, size);
    if (wrapped < 0.0)
        wrapped += size;
    const auto index = static_cast<std::size_t>(wrapped);
    const auto fraction = static_cast<float>(wrapped - static_cast<double>(index));
    const auto previous = (index + buffer.size() - 1) % buffer.size();
    const auto next = (index + 1) % buffer.size();
    const auto following = (index + 2) % buffer.size();
    const auto y0 = buffer[previous];
    const auto y1 = buffer[index];
    const auto y2 = buffer[next];
    const auto y3 = buffer[following];
    const auto c0 = y1;
    const auto c1 = 0.5F * (y2 - y0);
    const auto c2 = y0 - 2.5F * y1 + 2.0F * y2 - 0.5F * y3;
    const auto c3 = 0.5F * (y3 - y0) + 1.5F * (y1 - y2);
    return ((c3 * fraction + c2) * fraction + c1) * fraction + c0;
}
} // namespace

class EchoEngine::Impl final {
public:
    void prepare(const double requestedSampleRate, const std::size_t requestedChannels,
                 const std::size_t requestedMaximumBlockSize, const double maximumDelaySeconds) {
        assert(requestedSampleRate > 0.0 && requestedChannels > 0 && requestedMaximumBlockSize > 0
               && maximumDelaySeconds > 0.0);
        sampleRate = requestedSampleRate;
        channelCount = requestedChannels;
        maximumBlockSize = requestedMaximumBlockSize;
        maximumDelaySamples = static_cast<float>(std::ceil(sampleRate * maximumDelaySeconds));
        const auto bufferSize = static_cast<std::size_t>(maximumDelaySamples) + 4;
        delayBuffers.assign(channelCount, std::vector<float>(bufferSize));
        delayed.assign(channelCount, 0.0F);
        colored.assign(channelCount, 0.0F);
        lowPassState.assign(channelCount, 0.0F);
        highPassInput.assign(channelCount, 0.0F);
        highPassOutput.assign(channelCount, 0.0F);
        smoothingCoefficient = static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * 0.020)));
        reset();
    }

    void reset() noexcept {
        for (auto& buffer : delayBuffers)
            std::fill(buffer.begin(), buffer.end(), 0.0F);
        std::fill(delayed.begin(), delayed.end(), 0.0F);
        std::fill(colored.begin(), colored.end(), 0.0F);
        std::fill(lowPassState.begin(), lowPassState.end(), 0.0F);
        std::fill(highPassInput.begin(), highPassInput.end(), 0.0F);
        std::fill(highPassOutput.begin(), highPassOutput.end(), 0.0F);
        writePosition = 0;
        modulationPhase = 0.0;
        controlsInitialized = false;
    }

    void process(float* const* channels, const std::size_t suppliedChannels, const std::size_t frames,
                 const Parameters& parameters) noexcept {
        assert(channels != nullptr && suppliedChannels == channelCount && frames <= maximumBlockSize);
        (void) suppliedChannels;
        const auto targetDelay = std::clamp(parameters.delayMilliseconds * static_cast<float>(sampleRate) / 1000.0F,
                                            minimumDelaySamples, maximumDelaySamples);
        if (!controlsInitialized) {
            smoothedDelaySamples = targetDelay;
            controlsInitialized = true;
        }
        const auto feedback = std::clamp(parameters.feedback, -0.99F, 0.99F);
        const auto wet = std::max(0.0F, parameters.wetLevel);
        const auto dry = std::max(0.0F, parameters.dryLevel);
        const auto depthSamples = std::clamp(parameters.modulationDepthMilliseconds
                                                 * static_cast<float>(sampleRate) / 1000.0F,
                                             0.0F, maximumDelaySamples * 0.25F);
        const auto phaseIncrement = 2.0 * std::numbers::pi * std::clamp(parameters.modulationRateHertz, 0.0F, 20.0F)
                                    / sampleRate;
        const auto lowPassCoefficient = cutoffCoefficient(parameters.feedbackHighCutHertz);
        const auto highPassCoefficient = highPassCutoffCoefficient(parameters.feedbackLowCutHertz);
        for (std::size_t frame = 0; frame < frames; ++frame) {
            smoothedDelaySamples += (targetDelay - smoothedDelaySamples) * smoothingCoefficient;
            auto modulation = static_cast<float>(std::sin(modulationPhase));
            if (parameters.character == Character::vintageTape)
                modulation = modulation * 0.78F
                             + static_cast<float>(std::sin(modulationPhase * 7.13 + 0.7)) * 0.15F
                             + static_cast<float>(std::sin(modulationPhase * 13.71 + 2.1)) * 0.07F;
            const auto modulatedDelay = std::clamp(smoothedDelaySamples + depthSamples * modulation,
                                                   minimumDelaySamples, maximumDelaySamples);
            modulationPhase += phaseIncrement;
            if (modulationPhase >= 2.0 * std::numbers::pi)
                modulationPhase -= 2.0 * std::numbers::pi;
            for (std::size_t channel = 0; channel < channelCount; ++channel)
                delayed[channel] = interpolate(delayBuffers[channel],
                                               static_cast<double>(writePosition) - modulatedDelay);
            for (std::size_t channel = 0; channel < channelCount; ++channel) {
                const auto sourceChannel = parameters.pingPong && channelCount == 2 ? 1 - channel : channel;
                auto repeat = delayed[sourceChannel];
                if (parameters.feedbackHighCutHertz > 0.0F) {
                    lowPassState[channel] = (1.0F - lowPassCoefficient) * repeat
                                            + lowPassCoefficient * lowPassState[channel];
                    repeat = lowPassState[channel];
                }
                if (parameters.feedbackLowCutHertz > 0.0F) {
                    const auto filtered = highPassCoefficient
                                          * (highPassOutput[channel] + repeat - highPassInput[channel]);
                    highPassInput[channel] = repeat;
                    highPassOutput[channel] = filtered;
                    repeat = filtered;
                }
                if (parameters.saturation > 0.0F) {
                    const auto drive = 1.0F + std::clamp(parameters.saturation, 0.0F, 1.0F) * 7.0F;
                    if (parameters.character == Character::analogMemory) {
                        const auto biased = repeat * drive + 0.035F;
                        repeat = (std::tanh(biased) - std::tanh(0.035F)) / std::tanh(drive);
                    } else {
                        repeat = std::tanh(repeat * drive) / std::tanh(drive);
                    }
                }
                colored[channel] = repeat;
                const auto input = channels[channel][frame];
                delayBuffers[channel][writePosition] = input + feedback * repeat;
                channels[channel][frame] = parameters.bypass ? input : input * dry + repeat * wet;
            }
            writePosition = (writePosition + 1) % delayBuffers.front().size();
        }
    }

private:
    [[nodiscard]] float cutoffCoefficient(const float cutoff) const noexcept {
        const auto limited = std::clamp(cutoff, 1.0F, static_cast<float>(sampleRate * 0.49));
        return static_cast<float>(std::exp(-2.0 * std::numbers::pi * limited / sampleRate));
    }

    [[nodiscard]] float highPassCutoffCoefficient(const float cutoff) const noexcept {
        const auto limited = std::clamp(cutoff, 1.0F, static_cast<float>(sampleRate * 0.49));
        return static_cast<float>(1.0 / (1.0 + 2.0 * std::numbers::pi * limited / sampleRate));
    }

    double sampleRate { 48'000.0 };
    std::size_t channelCount {};
    std::size_t maximumBlockSize {};
    float maximumDelaySamples {};
    float smoothingCoefficient {};
    float smoothedDelaySamples {};
    std::size_t writePosition {};
    double modulationPhase {};
    bool controlsInitialized {};
    std::vector<std::vector<float>> delayBuffers;
    std::vector<float> delayed;
    std::vector<float> colored;
    std::vector<float> lowPassState;
    std::vector<float> highPassInput;
    std::vector<float> highPassOutput;
};

EchoEngine::EchoEngine() : impl_(std::make_unique<Impl>()) {}
EchoEngine::~EchoEngine() = default;
EchoEngine::EchoEngine(EchoEngine&&) noexcept = default;
EchoEngine& EchoEngine::operator=(EchoEngine&&) noexcept = default;

void EchoEngine::prepare(const double sampleRate, const std::size_t channelCount,
                         const std::size_t maximumBlockSize, const double maximumDelaySeconds) {
    impl_->prepare(sampleRate, channelCount, maximumBlockSize, maximumDelaySeconds);
}

void EchoEngine::reset() noexcept { impl_->reset(); }

void EchoEngine::process(float* const* channels, const std::size_t channelCount, const std::size_t frameCount,
                         const Parameters& parameters) noexcept {
    impl_->process(channels, channelCount, frameCount, parameters);
}

} // namespace mechana::echo
