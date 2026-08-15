/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/reverb/ReverbEngine.h>
#include <mechana/reverb/ImpulseResponsePreparation.h>

#include <array>
#include <cassert>
#include <cmath>
#include <vector>

int main() {
    const auto converted = mechana::reverb::resampleImpulseResponse({ { 1.0F, 0.0F, 0.0F, 0.0F } },
                                                                    48'000.0, 24'000.0);
    assert(converted.size() == 1 && converted.front().size() == 2);

    mechana::reverb::ImpulseResponseParameters shaping;
    shaping.earlyLevel = 0.5F;
    shaping.lateLevel = 0.5F;
    shaping.decayLengthPercent = 50.0F;
    const auto shaped = mechana::reverb::prepareImpulseResponse(
        { { 1.0F, 0.5F, 0.25F, 0.125F } }, 48'000.0, 48'000.0, shaping, false);
    assert(shaped.size() == 1 && shaped.front().size() == 2);
    assert(std::abs(shaped.front().front() - 0.5F) < 1.0e-5F);

    mechana::reverb::ReverbEngine engine;
    engine.prepare(48'000.0, 1, 64);
    engine.setImpulseResponse({ { 1.0F, 0.5F } });
    mechana::reverb::Parameters parameters;
    parameters.dryLevel = 0.0F;
    parameters.wetLevel = 1.0F;
    parameters.preDelayMilliseconds = 0.0F;

    std::vector<float> audio(512);
    audio[0] = 1.0F;
    for (std::size_t offset = 0; offset < audio.size(); offset += 64) {
        float* channel = audio.data() + offset;
        engine.process(&channel, 1, 64, parameters);
    }
    assert(std::abs(audio[engine.latencySamples()] - 1.0F) < 1.0e-5F);
    assert(std::abs(audio[engine.latencySamples() + 1] - 0.5F) < 1.0e-5F);
    return 0;
}
