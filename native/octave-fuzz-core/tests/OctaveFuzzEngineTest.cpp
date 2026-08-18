/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include <mechana/audio/Filters.h>
#include <mechana/audio/Nonlinear.h>
#include <mechana/audio/Oversampling.h>
#include <mechana/audio/ParameterSmoother.h>
#include <mechana/audio/Utilities.h>
#include <mechana/fuzz/OctaveFuzzEngine.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <iostream>
#include <numbers>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(const bool condition, const std::string& message) {
    if (!condition)
        throw std::runtime_error(message);
}

float magnitude(const std::vector<float>& audio, const double sampleRate, const double frequency,
                const std::size_t start = 0) {
    std::complex<double> sum;
    const auto count = audio.size() - start;
    for (std::size_t i = start; i < audio.size(); ++i) {
        const auto phase = -2.0 * std::numbers::pi * frequency * static_cast<double>(i - start) / sampleRate;
        sum += static_cast<double>(audio[i]) * std::complex<double>(std::cos(phase), std::sin(phase));
    }
    return static_cast<float>(std::abs(sum) * 2.0 / static_cast<double>(count));
}

std::vector<std::vector<float>> render(const double sampleRate, const std::size_t channels,
                                       const mechana::fuzz::Parameters& parameters,
                                       const double frequency = 440.0) {
    constexpr std::size_t frames = 16'384;
    std::vector<std::vector<float>> audio(channels, std::vector<float>(frames));
    for (auto& channel : audio)
        for (std::size_t frame = 0; frame < frames; ++frame)
            channel[frame] = 0.2F * std::sin(static_cast<float>(2.0 * std::numbers::pi * frequency * frame / sampleRate));
    mechana::fuzz::OctaveFuzzEngine engine;
    engine.prepare(sampleRate, channels);
    engine.setParameters(parameters);
    for (std::size_t offset = 0; offset < frames;) {
        const auto count = std::min<std::size_t>(127, frames - offset);
        std::vector<float*> pointers;
        for (auto& channel : audio)
            pointers.push_back(channel.data() + offset);
        engine.process(pointers.data(), channels, count);
        offset += count;
    }
    return audio;
}
} // namespace

int main() {
    try {
        using namespace mechana::audio;
        require(std::abs(softClip(0.0F)) < 1.0e-7F && softClip(2.0F) < 1.0F, "soft clipping bounds failed");
        require(hardClip(2.0F, 0.5F) == 0.5F && hardClip(-2.0F, 0.5F) == -0.5F,
                "symmetric hard clipping failed");
        require(std::abs(asymmetricClip(1.0F) + asymmetricClip(-1.0F)) > 0.05F,
                "asymmetric clipping remained symmetric");
        require(fullWave(-0.75F) == 0.75F, "full-wave rectification failed");

        DcBlocker blocker;
        blocker.prepare(48'000.0);
        float tail = 0.0F;
        for (std::size_t i = 0; i < 48'000; ++i)
            tail = blocker.process(0.5F);
        require(std::abs(tail) < 1.0e-4F, "DC blocker did not reject a constant");

        ParameterSmoother smoother;
        smoother.prepare(1'000.0, 10.0);
        smoother.reset(0.0F);
        smoother.setTarget(1.0F);
        const float first = smoother.next();
        for (int i = 0; i < 200; ++i)
            (void)smoother.next();
        require(first > 0.0F && first < 1.0F && smoother.current() > 0.99F,
                "parameter smoothing ramp failed");
        require(std::abs(equalPowerDry(0.5F) - equalPowerWet(0.5F)) < 1.0e-6F,
                "equal-power mix utilities failed");

        TwoTimesOversampler oversampler;
        float impulsePeak = 0.0F;
        std::size_t impulsePeakIndex = 0;
        for (std::size_t i = 0; i < 32; ++i) {
            const float output = oversampler.process(i == 0 ? 1.0F : 0.0F, [](float x) { return x; });
            if (std::abs(output) > impulsePeak) {
                impulsePeak = std::abs(output);
                impulsePeakIndex = i;
            }
        }
        require(impulsePeakIndex == TwoTimesOversampler::latencySamples, "oversampling latency changed");
        require(impulsePeak > 0.2F && impulsePeak < 1.1F, "oversampling passband gain changed");

        std::vector<float> directAlias(16'384), oversampledAlias(16'384);
        oversampler.reset();
        for (std::size_t i = 0; i < directAlias.size(); ++i) {
            const float sample = 0.9F * std::sin(static_cast<float>(2.0 * std::numbers::pi * 9'000.0 * i / 48'000.0));
            directAlias[i] = hardClip(sample * 3.0F);
            oversampledAlias[i] = oversampler.process(sample, [](float x) { return hardClip(x * 3.0F); });
        }
        require(magnitude(oversampledAlias, 48'000.0, 21'000.0, 2'048)
                    < magnitude(directAlias, 48'000.0, 21'000.0, 2'048) * 0.75F,
                "oversampling did not reduce folded third-harmonic alias energy");

        mechana::fuzz::Parameters octaveParameters;
        octaveParameters.drive = 0.45F;
        octaveParameters.tone = 0.7F;
        octaveParameters.level = 0.8F;
        octaveParameters.octave = 0.0F;
        const auto fuzzOnly = render(48'000.0, 1, octaveParameters);
        octaveParameters.octave = 1.0F;
        const auto octave = render(48'000.0, 1, octaveParameters);
        require(magnitude(octave[0], 48'000.0, 880.0, 2'048) > magnitude(fuzzOnly[0], 48'000.0, 880.0, 2'048) * 2.0F,
                "octave control did not increase second-harmonic content");

        octaveParameters.bypass = true;
        const auto bypassed = render(48'000.0, 2, octaveParameters);
        for (std::size_t i = 0; i < bypassed[0].size(); ++i) {
            const float expected = 0.2F * std::sin(static_cast<float>(2.0 * std::numbers::pi * 440.0 * i / 48'000.0));
            require(bypassed[0][i] == expected && bypassed[1][i] == expected, "bypass was not transparent");
        }

        octaveParameters.bypass = false;
        const auto mono = render(48'000.0, 1, octaveParameters);
        const auto stereo = render(48'000.0, 2, octaveParameters);
        require(mono[0] == stereo[0] && stereo[0] == stereo[1], "mono/stereo routing was not equivalent");
        for (const auto sampleRate : { 44'100.0, 48'000.0, 88'200.0, 96'000.0 }) {
            const auto firstRender = render(sampleRate, 2, octaveParameters);
            const auto secondRender = render(sampleRate, 2, octaveParameters);
            require(firstRender == secondRender, "engine output was not deterministic");
            require(std::ranges::all_of(firstRender[0], [](float sample) { return std::isfinite(sample); }),
                    "engine produced non-finite output");
        }
        require(mechana::fuzz::OctaveFuzzEngine::latencySamples() == 8, "reported engine latency changed");
        std::cout << "audio primitives and octave-fuzz engine tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
