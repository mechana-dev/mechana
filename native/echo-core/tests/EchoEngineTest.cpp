/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/echo/EchoEngine.h>
#include <mechana/echo/Feedback.h>
#include <mechana/echo/Models.h>
#include <mechana/echo/Tail.h>
#include <mechana/audio/DryWetMixer.h>

#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(const bool condition, const std::string& message) {
    if (!condition)
        throw std::runtime_error(message);
}

std::vector<std::vector<float>> render(const std::size_t channels, const mechana::echo::Parameters& parameters) {
    constexpr std::size_t frames = 2'000;
    std::vector<std::vector<float>> audio(channels, std::vector<float>(frames));
    audio.front().front() = 1.0F;
    mechana::echo::EchoEngine engine;
    engine.prepare(1'000.0, channels, 128, 1.0);
    for (std::size_t offset = 0; offset < frames;) {
        const auto count = std::min<std::size_t>(73, frames - offset);
        std::vector<float*> pointers;
        pointers.reserve(channels);
        for (auto& channel : audio)
            pointers.push_back(channel.data() + offset);
        engine.process(pointers.data(), channels, count, parameters);
        offset += count;
    }
    return audio;
}

float highFrequencyProxy(const std::vector<float>& audio, const std::size_t start, const std::size_t count) {
    auto signalEnergy = 0.0F;
    auto differenceEnergy = 0.0F;
    for (std::size_t index = start + 1; index < start + count; ++index) {
        signalEnergy += audio[index] * audio[index];
        const auto difference = audio[index] - audio[index - 1];
        differenceEnergy += difference * difference;
    }
    return differenceEnergy / std::max(signalEnergy, 1.0e-20F);
}
} // namespace

int main() {
    try {
        mechana::echo::Parameters parameters;
        parameters.delayMilliseconds = 100.0F;
        parameters.mix = 1.0F;
        parameters.feedback = 0.5F;
        auto output = render(1, parameters);
        require(std::abs(output[0][0]) < 1.0e-6F, "100% wet mix leaked dry impulse");
        require(std::abs(output[0][100] - 1.0F) < 1.0e-5F, "first echo timing or gain changed");
        const auto coefficient = mechana::echo::feedbackCoefficient(0.5F);
        require(std::abs(output[0][200] - coefficient) < 1.0e-5F, "feedback mapping changed");
        require(std::abs(output[0][300] - coefficient * coefficient) < 1.0e-5F, "second feedback repeat changed");

        parameters.feedback = 0.0F;
        parameters.mix = 0.0F;
        output = render(1, parameters);
        require(std::abs(output[0][0] - 1.0F) < 1.0e-6F && std::abs(output[0][100]) < 1.0e-6F,
                "0% mix must be dry only");
        parameters.mix = 0.5F;
        output = render(1, parameters);
        require(std::abs(output[0][0] - 0.5F) < 1.0e-6F && std::abs(output[0][100] - 0.5F) < 1.0e-5F,
                "50% mix must use a linear crossfade");

        mechana::audio::DryWetMixer mixer;
        mixer.prepare(1'000.0, 10.0);
        mixer.reset(0.0F);
        mixer.setMix(1.0F);
        auto previousWet = 0.0F;
        for (int sample = 0; sample < 200; ++sample) {
            const auto gains = mixer.next();
            require(gains.wet >= previousWet && gains.wet - previousWet < 0.1F,
                    "Mix automation was not click-smoothed");
            require(std::abs(gains.dry + gains.wet - 1.0F) < 1.0e-6F,
                    "smoothed Mix violated linear coefficient sum");
            previousWet = gains.wet;
        }
        require(previousWet > 0.99F, "Mix smoothing did not converge");

        parameters.mix = 1.0F;
        parameters.pingPong = true;
        output = render(2, parameters);
        require(std::abs(output[0][100]) < 1.0e-6F, "ping-pong left the first echo on its source channel");
        require(std::abs(output[1][100] - 1.0F) < 1.0e-5F, "ping-pong did not move the first echo");

        parameters.feedback = 0.6F;
        parameters.modulationRateHertz = 2.0F;
        parameters.modulationDepthMilliseconds = 3.0F;
        parameters.feedbackHighCutHertz = 300.0F;
        parameters.feedbackLowCutHertz = 20.0F;
        parameters.saturation = 0.5F;
        output = render(2, parameters);
        for (const auto& channel : output)
            require(std::ranges::all_of(channel, [](const float sample) { return std::isfinite(sample); }),
                    "optional processing produced non-finite audio");

        const auto tape = mechana::echo::modelDefaults(mechana::echo::Model::vintageTape);
        const auto memory = mechana::echo::modelDefaults(mechana::echo::Model::analogMemory);
        require(tape.feedbackHighCutHertz > memory.feedbackHighCutHertz,
                "analog-memory model should roll off more high end than vintage tape");
        require(tape.modulationDepthMilliseconds > 0.0F && memory.modulationDepthMilliseconds > 0.0F,
                "modeled echoes must include subtle modulation");
        require(std::abs(tape.mix - 0.26F) < 1.0e-6F && std::abs(memory.mix - 0.26F) < 1.0e-6F,
                "modeled mix should match the listening-calibrated default");
        require(std::abs(mechana::echo::feedbackCoefficient(0.36F) - 0.419F) < 0.01F,
                "mid-30% feedback should decay about 7.6 dB per unfiltered repeat");

        auto degradation = memory;
        degradation.delayMilliseconds = 100.0F;
        degradation.mix = 1.0F;
        degradation.feedback = 0.36F;
        degradation.modulationDepthMilliseconds = 0.0F;
        output = render(1, degradation);
        require(std::abs(output[0][200]) < std::abs(output[0][100]) * 0.6F,
                "analog feedback generation did not become softer");
        require(std::ranges::all_of(output[0], [](const float sample) { return std::isfinite(sample) && std::abs(sample) <= 2.0F; }),
                "analog feedback became unstable");

        constexpr std::size_t darkeningFrames = 8'000;
        constexpr std::size_t darkeningDelay = 960;
        constexpr std::size_t burstFrames = 512;
        std::vector<float> darkening(darkeningFrames);
        for (std::size_t index = 0; index < burstFrames; ++index)
            darkening[index] = index % 2 == 0 ? 0.2F : -0.2F;
        auto darkeningParameters = memory;
        darkeningParameters.delayMilliseconds = 20.0F;
        darkeningParameters.feedback = 0.72F;
        darkeningParameters.mix = 1.0F;
        darkeningParameters.modulationDepthMilliseconds = 0.0F;
        mechana::echo::EchoEngine darkeningEngine;
        darkeningEngine.prepare(48'000.0, 1, 128, 1.0);
        for (std::size_t offset = 0; offset < darkeningFrames; offset += 128) {
            const auto count = std::min<std::size_t>(128, darkeningFrames - offset);
            auto* pointer = darkening.data() + offset;
            darkeningEngine.process(&pointer, 1, count, darkeningParameters);
        }
        const auto firstHighFrequency = highFrequencyProxy(darkening, darkeningDelay, burstFrames);
        const auto thirdHighFrequency = highFrequencyProxy(darkening, darkeningDelay * 3, burstFrames);
        require(thirdHighFrequency < firstHighFrequency * 0.75F,
                "Analog Memory repeat generations did not progressively darken");

        auto deterministicParameters = memory;
        deterministicParameters.delayMilliseconds = 100.0F;
        deterministicParameters.mix = 1.0F;
        const auto deterministicA = render(1, deterministicParameters);
        const auto deterministicB = render(1, deterministicParameters);
        require(deterministicA == deterministicB, "Analog Memory clock wander was not deterministic after reset");

        constexpr std::size_t continuityFrames = 4'096;
        std::vector<float> continuity(continuityFrames);
        continuity.front() = 0.5F;
        mechana::echo::EchoEngine continuityEngine;
        continuityEngine.prepare(48'000.0, 1, 128, 1.0);
        auto continuityParameters = memory;
        continuityParameters.delayMilliseconds = 20.0F;
        continuityParameters.mix = 1.0F;
        continuityParameters.modulationDepthMilliseconds = 0.0F;
        for (std::size_t offset = 0; offset < continuityFrames; offset += 128) {
            if (offset == continuityFrames / 2) {
                continuityParameters.modulationDepthMilliseconds = 8.0F;
                continuityParameters.modulationRateHertz = 6.0F;
            }
            auto* pointer = continuity.data() + offset;
            continuityEngine.process(&pointer, 1, 128, continuityParameters);
        }
        auto maximumStep = 0.0F;
        for (std::size_t index = continuityFrames / 2 - 16; index < continuityFrames / 2 + 512; ++index)
            maximumStep = std::max(maximumStep, std::abs(continuity[index] - continuity[index - 1]));
        require(maximumStep < 0.5F, "modulation parameter change introduced a click-sized discontinuity");

        auto tailParameters = tape;
        tailParameters.delayMilliseconds = 750.0F;
        tailParameters.feedback = 0.48F;
        const auto conservativeTail = mechana::echo::reportedTailSeconds(tailParameters);
        require(conservativeTail >= 14.0 && conservativeTail <= 16.0,
                "reported Echo tail should reach -100 dB plus one safety repeat");
        tailParameters.feedback = 0.98F;
        require(mechana::echo::reportedTailSeconds(tailParameters) == 30.0,
                "reported Echo tail should retain its maximum bound");
        tailParameters.bypass = true;
        require(mechana::echo::reportedTailSeconds(tailParameters) == 0.0,
                "bypassed Echo should not report a tail");

        mechana::echo::EchoEngine engine;
        require(engine.latencySamples() == 0, "echo engine introduced latency");
        std::cout << "echo core timing, feedback, stereo, optional processing, and latency tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
