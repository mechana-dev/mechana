/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
#include <mechana/leslie/LeslieEngine.h>

#include <algorithm>
#include <cassert>
#include <cmath>
#include <numbers>
#include <vector>

namespace mechana::leslie {
namespace {
constexpr double hornSlowRpm = 44.0;
constexpr double hornFastRpm = 402.0;
constexpr double drumSlowRpm = 42.0;
constexpr double drumFastRpm = 372.0;

float interpolate(const std::vector<float>& buffer, const double position) noexcept {
    auto wrapped = std::fmod(position, static_cast<double>(buffer.size()));
    if (wrapped < 0.0)
        wrapped += static_cast<double>(buffer.size());
    const auto index = static_cast<std::size_t>(wrapped);
    const auto fraction = static_cast<float>(wrapped - static_cast<double>(index));
    const auto next = (index + 1) % buffer.size();
    return buffer[index] + (buffer[next] - buffer[index]) * fraction;
}

double targetSpeed(const RotorMode mode, const double slow, const double fast) noexcept {
    if (mode == RotorMode::fast)
        return fast;
    if (mode == RotorMode::slow)
        return slow;
    return 0.0;
}
} // namespace

class LeslieEngine::Impl final {
public:
    void prepare(const double requestedSampleRate, const std::size_t requestedChannels,
                 const std::size_t requestedMaximumBlockSize) {
        assert(requestedSampleRate > 0.0 && requestedChannels > 0 && requestedChannels <= 2
               && requestedMaximumBlockSize > 0);
        sampleRate = requestedSampleRate;
        channelCount = requestedChannels;
        maximumBlockSize = requestedMaximumBlockSize;
        const auto delaySize = static_cast<std::size_t>(std::ceil(sampleRate * 0.012)) + 4;
        hornDelay.assign(delaySize, 0.0F);
        drumDelay.assign(delaySize, 0.0F);
        crossoverState.assign(channelCount, 0.0F);
        controlSmoothing = 1.0 - std::exp(-1.0 / (sampleRate * 0.020));
        reset();
    }

    void reset() noexcept {
        std::fill(hornDelay.begin(), hornDelay.end(), 0.0F);
        std::fill(drumDelay.begin(), drumDelay.end(), 0.0F);
        std::fill(crossoverState.begin(), crossoverState.end(), 0.0F);
        writePosition = 0;
        hornPhase = 0.0;
        drumPhase = std::numbers::pi * 0.5;
        hornRpm = 0.0;
        drumRpm = 0.0;
        controlsInitialized = false;
    }

    void process(float* const* channels, const std::size_t suppliedChannels, const std::size_t frames,
                 const Parameters& parameters) noexcept {
        assert(channels != nullptr && suppliedChannels == channelCount && frames <= maximumBlockSize);
        const auto targetCrossover = std::clamp(parameters.crossoverHertz, 200.0F,
                                                static_cast<float>(sampleRate * 0.45));
        const auto targetDrive = std::clamp(parameters.drive, 0.0F, 1.0F);
        const auto targetHornLevel = std::clamp(parameters.hornLevel, 0.0F, 1.0F);
        const auto targetDistance = std::clamp(parameters.microphoneDistance, 0.0F, 1.0F);
        const auto targetWidth = std::clamp(parameters.stereoWidth, 0.0F, 1.0F);
        const auto targetWet = std::max(0.0F, parameters.wetLevel);
        const auto targetDry = std::max(0.0F, parameters.dryLevel);
        if (!controlsInitialized) {
            smoothedCrossover = targetCrossover;
            smoothedDrive = targetDrive;
            smoothedHornLevel = targetHornLevel;
            smoothedDistance = targetDistance;
            smoothedWidth = targetWidth;
            smoothedWet = targetWet;
            smoothedDry = targetDry;
            controlsInitialized = true;
        }
        const auto hornTarget = targetSpeed(parameters.rotorMode, hornSlowRpm, hornFastRpm);
        const auto drumTarget = targetSpeed(parameters.rotorMode, drumSlowRpm, drumFastRpm);
        const auto hornTime = hornTarget > hornRpm ? 1.8 : 2.4;
        const auto drumTime = drumTarget > drumRpm ? 7.0 : 5.5;
        const auto hornRamp = 1.0 - std::exp(std::log(0.01) / (hornTime * sampleRate));
        const auto drumRamp = 1.0 - std::exp(std::log(0.01) / (drumTime * sampleRate));

        for (std::size_t frame = 0; frame < frames; ++frame) {
            smooth(smoothedCrossover, targetCrossover);
            smooth(smoothedDrive, targetDrive);
            smooth(smoothedHornLevel, targetHornLevel);
            smooth(smoothedDistance, targetDistance);
            smooth(smoothedWidth, targetWidth);
            smooth(smoothedWet, targetWet);
            smooth(smoothedDry, targetDry);
            const auto crossoverCoefficient = static_cast<float>(1.0 - std::exp(-2.0 * std::numbers::pi
                                                                                * smoothedCrossover / sampleRate));
            const auto drumLevel = 1.0F - smoothedHornLevel;
            const auto proximity = 1.0F - smoothedDistance;
            const auto baseDelay = sampleRate * (0.0014 + static_cast<double>(smoothedDistance) * 0.0022);
            const auto hornDepth = sampleRate * (0.00025 + static_cast<double>(proximity) * 0.00075);
            const auto drumDepth = sampleRate * (0.00012 + static_cast<double>(proximity) * 0.00032);
            const auto microphoneAngle = static_cast<double>(smoothedWidth) * std::numbers::pi * 0.48;
            hornRpm += (hornTarget - hornRpm) * hornRamp;
            drumRpm += (drumTarget - drumRpm) * drumRamp;
            hornPhase = wrapPhase(hornPhase + rpmToRadiansPerSample(hornRpm));
            drumPhase = wrapPhase(drumPhase - rpmToRadiansPerSample(drumRpm));

            float hornInput = 0.0F;
            float drumInput = 0.0F;
            for (std::size_t channel = 0; channel < channelCount; ++channel) {
                auto input = channels[channel][frame];
                if (smoothedDrive > 0.0F) {
                    const auto gain = 1.0F + smoothedDrive * 8.0F;
                    input = std::tanh(input * gain) / std::tanh(gain);
                }
                crossoverState[channel] += crossoverCoefficient * (input - crossoverState[channel]);
                drumInput += crossoverState[channel];
                hornInput += input - crossoverState[channel];
            }
            hornInput /= static_cast<float>(channelCount);
            drumInput /= static_cast<float>(channelCount);
            hornDelay[writePosition] = hornInput;
            drumDelay[writePosition] = drumInput;

            for (std::size_t channel = 0; channel < channelCount; ++channel) {
                const auto side = channelCount == 1 ? 0.0 : (channel == 0 ? -1.0 : 1.0);
                const auto hornAngle = hornPhase + side * microphoneAngle;
                const auto drumAngle = drumPhase + side * microphoneAngle * 0.72;
                const auto hornDelaySamples = baseDelay + hornDepth * std::sin(hornAngle);
                const auto drumDelaySamples = baseDelay + drumDepth * std::sin(drumAngle);
                const auto hornSignal = interpolate(hornDelay,
                    static_cast<double>(writePosition) - std::max(1.0, hornDelaySamples));
                const auto drumSignal = interpolate(drumDelay,
                    static_cast<double>(writePosition) - std::max(1.0, drumDelaySamples));
                const auto hornGain = 0.68F + 0.32F * proximity * static_cast<float>(std::cos(hornAngle));
                const auto drumGain = 0.78F + 0.22F * proximity * static_cast<float>(std::cos(drumAngle));
                const auto rotated = hornSignal * smoothedHornLevel * hornGain + drumSignal * drumLevel * drumGain;
                const auto input = channels[channel][frame];
                channels[channel][frame] = parameters.bypass ? input
                                                             : input * smoothedDry + rotated * smoothedWet * 1.65F;
            }
            writePosition = (writePosition + 1) % hornDelay.size();
        }
    }

    [[nodiscard]] double currentHornRpm() const noexcept { return hornRpm; }
    [[nodiscard]] double currentDrumRpm() const noexcept { return drumRpm; }

private:
    [[nodiscard]] double rpmToRadiansPerSample(const double rpm) const noexcept {
        return rpm * 2.0 * std::numbers::pi / (60.0 * sampleRate);
    }

    static double wrapPhase(double phase) noexcept {
        if (phase >= 2.0 * std::numbers::pi)
            phase -= 2.0 * std::numbers::pi;
        if (phase < 0.0)
            phase += 2.0 * std::numbers::pi;
        return phase;
    }

    void smooth(float& current, const float target) const noexcept {
        current += (target - current) * static_cast<float>(controlSmoothing);
    }

    double sampleRate { 48'000.0 };
    std::size_t channelCount {};
    std::size_t maximumBlockSize {};
    std::vector<float> hornDelay;
    std::vector<float> drumDelay;
    std::vector<float> crossoverState;
    std::size_t writePosition {};
    double hornPhase {};
    double drumPhase {};
    double hornRpm {};
    double drumRpm {};
    double controlSmoothing {};
    float smoothedCrossover {};
    float smoothedDrive {};
    float smoothedHornLevel {};
    float smoothedDistance {};
    float smoothedWidth {};
    float smoothedWet {};
    float smoothedDry {};
    bool controlsInitialized {};
};

LeslieEngine::LeslieEngine() : impl_(std::make_unique<Impl>()) {}
LeslieEngine::~LeslieEngine() = default;
LeslieEngine::LeslieEngine(LeslieEngine&&) noexcept = default;
LeslieEngine& LeslieEngine::operator=(LeslieEngine&&) noexcept = default;

void LeslieEngine::prepare(const double sampleRate, const std::size_t channelCount,
                           const std::size_t maximumBlockSize) {
    impl_->prepare(sampleRate, channelCount, maximumBlockSize);
}

void LeslieEngine::reset() noexcept { impl_->reset(); }

void LeslieEngine::process(float* const* channels, const std::size_t channelCount,
                           const std::size_t frameCount, const Parameters& parameters) noexcept {
    impl_->process(channels, channelCount, frameCount, parameters);
}

double LeslieEngine::hornSpeedRpm() const noexcept { return impl_->currentHornRpm(); }
double LeslieEngine::drumSpeedRpm() const noexcept { return impl_->currentDrumRpm(); }

} // namespace mechana::leslie
