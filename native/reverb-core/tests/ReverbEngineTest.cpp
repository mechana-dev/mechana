/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/reverb/ImpulseResponsePreparation.h>
#include <mechana/reverb/ReverbEngine.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(const bool condition, const std::string& message) {
    if (!condition)
        throw std::runtime_error(message);
}

std::vector<float> render(const std::vector<float>& source, const std::vector<float>& ir) {
    mechana::reverb::ReverbEngine engine;
    engine.prepare(48'000.0, 1, 256);
    engine.setImpulseResponse({ ir });
    mechana::reverb::Parameters parameters;
    parameters.dryLevel = 0.0F;
    parameters.wetLevel = 1.0F;
    parameters.preDelayMilliseconds = 0.0F;
    std::vector<float> audio(source.size() + ir.size() + engine.latencySamples() + 512);
    std::copy(source.begin(), source.end(), audio.begin());
    for (std::size_t offset = 0; offset < audio.size();) {
        const auto frames = std::min<std::size_t>(73, audio.size() - offset);
        float* channel = audio.data() + offset;
        engine.process(&channel, 1, frames, parameters);
        offset += frames;
    }
    return audio;
}

void verifyTierBoundaries() {
    std::vector<float> ir(16'386);
    ir[0] = 1.0F;
    ir[2'047] = 0.5F;
    ir[2'048] = -0.25F;
    ir[16'383] = 0.125F;
    ir[16'384] = -0.0625F;
    const auto output = render({ 1.0F }, ir);
    constexpr std::array expectedOffsets { 0U, 2'047U, 2'048U, 16'383U, 16'384U };
    for (const auto offset : expectedOffsets)
        require(std::abs(output[mechana::reverb::ReverbEngine::partitionSize + offset] - ir[offset]) < 2.0e-5F,
                "non-uniform tier boundary changed impulse timing or gain");
}

void verifyNumericalEquivalence() {
    std::mt19937 random(42);
    std::uniform_real_distribution<float> values(-0.2F, 0.2F);
    std::vector<float> source(1'024), ir(4'096);
    std::generate(source.begin(), source.end(), [&] { return values(random); });
    std::generate(ir.begin(), ir.end(), [&] { return values(random); });
    const auto output = render(source, ir);
    auto maximumError = 0.0F;
    double errorEnergy = 0.0;
    double referenceEnergy = 0.0;
    for (std::size_t frame = 0; frame < source.size() + ir.size() - 1; ++frame) {
        double expected = 0.0;
        const auto first = frame >= ir.size() - 1 ? frame - (ir.size() - 1) : 0;
        const auto last = std::min(frame, source.size() - 1);
        for (auto input = first; input <= last; ++input)
            expected += static_cast<double>(source[input]) * ir[frame - input];
        const auto actual = output[mechana::reverb::ReverbEngine::partitionSize + frame];
        const auto error = static_cast<double>(actual) - expected;
        maximumError = std::max(maximumError, static_cast<float>(std::abs(error)));
        errorEnergy += error * error;
        referenceEnergy += expected * expected;
    }
    const auto relativeRms = std::sqrt(errorEnergy / referenceEnergy);
    require(maximumError < 2.0e-4F, "maximum optimized/reference error exceeds tolerance");
    require(relativeRms < 2.0e-5, "RMS optimized/reference error exceeds tolerance");
    std::cout << "equivalence max-error=" << maximumError << " relative-rms=" << relativeRms << '\n';
}

void verifyLongIrRealtimeBudget() {
    std::vector<float> ir(278'400);
    for (std::size_t frame = 0; frame < ir.size(); ++frame)
        ir[frame] = static_cast<float>(
            std::sin(frame * 0.017) * std::exp(-static_cast<double>(frame) / 70'000.0) * 0.001);
    std::vector<float> source(48'000 * 3);
    for (std::size_t frame = 0; frame < source.size(); ++frame)
        source[frame] = static_cast<float>(std::sin(frame * 0.031) * 0.1);
    const auto started = std::chrono::steady_clock::now();
    const auto output = render(source, ir);
    const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    const auto nonFinite = std::find_if(output.begin(), output.end(), [](const float sample) {
        return !std::isfinite(sample);
    });
    require(nonFinite == output.end(), "long-IR render produced a non-finite sample at frame "
                                             + std::to_string(std::distance(output.begin(), nonFinite)));
    require(elapsed < 3.0, "three seconds of 5.8-second-IR audio missed the real-time budget");
    std::cout << "long-ir 3-second render=" << elapsed << " sec\n";
}
} // namespace

int main() {
    try {
        const auto converted = mechana::reverb::resampleImpulseResponse({ { 1.0F, 0.0F, 0.0F, 0.0F } },
                                                                        48'000.0, 24'000.0);
        require(converted.size() == 1 && converted.front().size() == 2, "IR resampling length mismatch");

        mechana::reverb::ImpulseResponseParameters shaping;
        shaping.earlyLevel = 0.5F;
        shaping.lateLevel = 0.5F;
        shaping.decayLengthPercent = 50.0F;
        const auto shaped = mechana::reverb::prepareImpulseResponse(
            { { 1.0F, 0.5F, 0.25F, 0.125F } }, 48'000.0, 48'000.0, shaping, false);
        require(shaped.size() == 1 && shaped.front().size() == 2, "IR shaping length mismatch");
        require(std::abs(shaped.front().front() - 0.5F) < 1.0e-5F, "IR shaping gain mismatch");

        verifyTierBoundaries();
        verifyNumericalEquivalence();
        verifyLongIrRealtimeBudget();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
