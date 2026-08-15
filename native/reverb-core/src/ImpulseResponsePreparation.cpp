/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/reverb/ImpulseResponsePreparation.h>

#include <algorithm>
#include <cmath>
#include <numbers>

namespace mechana::reverb {
namespace {

float windowedSinc(const std::vector<float>& samples, const double position, const double cutoff) {
    const auto center = static_cast<long>(std::floor(position));
    auto sum = 0.0;
    auto weight = 0.0;
    for (long tap = -15; tap <= 16; ++tap) {
        const auto index = center + tap;
        if (index < 0 || index >= static_cast<long>(samples.size()))
            continue;
        const auto distance = position - static_cast<double>(index);
        const auto filteredDistance = cutoff * distance;
        const auto sinc = filteredDistance == 0.0
                              ? cutoff
                              : cutoff * std::sin(std::numbers::pi * filteredDistance)
                                    / (std::numbers::pi * filteredDistance);
        const auto window = 0.5 + 0.5 * std::cos(std::numbers::pi * distance / 16.0);
        const auto coefficient = sinc * window;
        sum += static_cast<double>(samples[static_cast<std::size_t>(index)]) * coefficient;
        weight += coefficient;
    }
    return weight == 0.0 ? 0.0F : static_cast<float>(sum / weight);
}

} // namespace

std::vector<std::vector<float>> resampleImpulseResponse(const std::vector<std::vector<float>>& channels,
                                                        const double sourceSampleRate,
                                                        const double targetSampleRate) {
    if (channels.empty() || sourceSampleRate <= 0.0 || targetSampleRate <= 0.0)
        return {};
    if (std::abs(sourceSampleRate - targetSampleRate) < 0.5)
        return channels;
    const auto outputFrames = static_cast<std::size_t>(
        std::llround(static_cast<double>(channels.front().size()) * targetSampleRate / sourceSampleRate));
    const auto cutoff = std::min(1.0, targetSampleRate / sourceSampleRate);
    std::vector<std::vector<float>> result(channels.size(), std::vector<float>(outputFrames));
    for (std::size_t channel = 0; channel < channels.size(); ++channel)
        for (std::size_t frame = 0; frame < outputFrames; ++frame) {
            const auto position = static_cast<double>(frame) * sourceSampleRate / targetSampleRate;
            result[channel][frame] = windowedSinc(channels[channel], position, cutoff);
        }
    return result;
}

std::vector<std::vector<float>> prepareImpulseResponse(const std::vector<std::vector<float>>& channels,
                                                       const double sourceSampleRate,
                                                       const double targetSampleRate,
                                                       const ImpulseResponseParameters& parameters,
                                                       const bool calibrate) {
    auto result = resampleImpulseResponse(channels, sourceSampleRate, targetSampleRate);
    if (result.empty() || result.front().empty())
        return result;

    const auto originalLength = result.front().size();
    const auto decayPercent = std::clamp(parameters.decayLengthPercent, 1.0F, 100.0F);
    const auto shapedLength = std::max<std::size_t>(
        1, static_cast<std::size_t>(std::llround(originalLength * decayPercent / 100.0)));
    const auto boundary = std::min(originalLength, static_cast<std::size_t>(targetSampleRate * 0.080));
    const auto transition = std::max<std::size_t>(1, static_cast<std::size_t>(targetSampleRate * 0.010));
    const auto attack = static_cast<std::size_t>(
        std::llround(std::max(0.0F, parameters.attackMilliseconds) * targetSampleRate / 1000.0));
    const auto decayFade = decayPercent < 100.0F
                               ? std::min(shapedLength, std::max<std::size_t>(2, targetSampleRate * 0.050))
                               : 0;
    for (auto& channel : result) {
        channel.resize(shapedLength);
        for (std::size_t frame = 0; frame < shapedLength; ++frame) {
            const auto blendStart = static_cast<double>(boundary) - static_cast<double>(transition) / 2.0;
            const auto earlyBlend = std::clamp((static_cast<double>(frame) - blendStart)
                                                   / static_cast<double>(transition),
                                               0.0, 1.0);
            const auto sectionGain = parameters.earlyLevel * (1.0 - earlyBlend)
                                     + parameters.lateLevel * earlyBlend;
            const auto attackGain = attack > 0 && frame < attack
                                        ? static_cast<double>(frame) / static_cast<double>(attack)
                                        : 1.0;
            const auto decayGain = decayFade > 0 && frame >= shapedLength - decayFade
                                       ? static_cast<double>(shapedLength - frame - 1)
                                             / static_cast<double>(decayFade - 1)
                                       : 1.0;
            channel[frame] = static_cast<float>(channel[frame] * sectionGain * attackGain * decayGain);
        }
    }

    if (calibrate) {
        auto energy = 0.0;
        auto peak = 0.0;
        for (const auto& channel : result) {
            auto channelEnergySquared = 0.0;
            for (const auto sample : channel) {
                channelEnergySquared += static_cast<double>(sample) * sample;
                peak = std::max(peak, std::abs(static_cast<double>(sample)));
            }
            energy = std::max(energy, std::sqrt(channelEnergySquared * 48'000.0 / targetSampleRate));
        }
        if (energy > 0.0) {
            const auto maximumBoost = std::pow(10.0, 12.0 / 20.0);
            const auto maximumPeak = std::pow(10.0, -1.0 / 20.0);
            const auto gain = std::min({ 1.0 / energy, maximumBoost, peak > 0.0 ? maximumPeak / peak : maximumBoost });
            for (auto& channel : result)
                for (auto& sample : channel)
                    sample = static_cast<float>(sample * gain);
        }
    }
    return result;
}

} // namespace mechana::reverb
