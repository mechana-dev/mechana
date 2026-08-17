/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include <mechana/echo/EchoEngine.h>
#include <mechana/echo/Models.h>
#include <mechana/echo/Tail.h>

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
} // namespace

int main() {
    try {
        mechana::echo::Parameters parameters;
        parameters.delayMilliseconds = 100.0F;
        parameters.dryLevel = 1.0F;
        parameters.wetLevel = 1.0F;
        parameters.feedback = 0.5F;
        auto output = render(1, parameters);
        require(std::abs(output[0][0] - 1.0F) < 1.0e-6F, "dry impulse changed");
        require(std::abs(output[0][100] - 1.0F) < 1.0e-5F, "first echo timing or gain changed");
        require(std::abs(output[0][200] - 0.5F) < 1.0e-5F, "feedback repeat timing or gain changed");
        require(std::abs(output[0][300] - 0.25F) < 1.0e-5F, "second feedback repeat changed");

        parameters.feedback = 0.0F;
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
        require(std::abs(tape.wetLevel - 0.26F) < 1.0e-6F && std::abs(memory.wetLevel - 0.26F) < 1.0e-6F,
                "modeled first-repeat level should match the listening-calibrated default");

        auto tailParameters = tape;
        tailParameters.delayMilliseconds = 750.0F;
        tailParameters.feedback = 0.48F;
        const auto conservativeTail = mechana::echo::reportedTailSeconds(tailParameters);
        require(conservativeTail >= 12.0 && conservativeTail <= 14.0,
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
