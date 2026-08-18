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
#include <mechana/echo/Models.h>

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 6) {
        std::cerr << "usage: render INPUT.f32 OUTPUT.f32 SAMPLE_RATE CHANNELS SOURCE_FRAMES\n";
        return 2;
    }
    const auto rate = std::atof(argv[3]);
    const auto channels = static_cast<std::size_t>(std::strtoul(argv[4], nullptr, 10));
    const auto sourceFrames = static_cast<std::size_t>(std::strtoull(argv[5], nullptr, 10));
    constexpr std::size_t block = 256;
    const auto tailFrames = static_cast<std::size_t>(rate * 6.0);
    std::vector<float> interleaved(sourceFrames * channels);
    std::ifstream input(argv[1], std::ios::binary);
    input.read(reinterpret_cast<char*>(interleaved.data()), static_cast<std::streamsize>(interleaved.size() * sizeof(float)));
    if (!input) {
        std::cerr << "could not read complete input\n";
        return 1;
    }
    std::vector<std::vector<float>> planar(channels, std::vector<float>(sourceFrames + tailFrames));
    for (std::size_t frame = 0; frame < sourceFrames; ++frame)
        for (std::size_t channel = 0; channel < channels; ++channel)
            planar[channel][frame] = interleaved[frame * channels + channel];
    auto parameters = mechana::echo::modelDefaults(mechana::echo::Model::analogMemory);
    parameters.delayMilliseconds = 350.0F;
    parameters.feedback = 0.36F;
    parameters.mix = 0.26F;
    mechana::echo::EchoEngine engine;
    engine.prepare(rate, channels, block);
    for (std::size_t offset = 0; offset < planar.front().size(); offset += block) {
        const auto count = std::min(block, planar.front().size() - offset);
        std::vector<float*> pointers(channels);
        for (std::size_t channel = 0; channel < channels; ++channel)
            pointers[channel] = planar[channel].data() + offset;
        engine.process(pointers.data(), channels, count, parameters);
    }
    std::ofstream output(argv[2], std::ios::binary);
    for (std::size_t frame = 0; frame < planar.front().size(); ++frame)
        for (std::size_t channel = 0; channel < channels; ++channel)
            output.write(reinterpret_cast<const char*>(&planar[channel][frame]), sizeof(float));
    return output ? 0 : 1;
}
