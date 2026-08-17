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
#include <mechana/leslie/Models.h>

#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {
void require(const bool condition, const char* message) {
    if (!condition)
        throw std::runtime_error(message);
}
}

int main() {
    try {
        constexpr std::size_t sampleRate = 48'000;
        constexpr std::size_t blockSize = 128;
        mechana::leslie::LeslieEngine engine;
        engine.prepare(sampleRate, 2, blockSize);
        require(engine.latencySamples() == 0, "Leslie must not add reported host latency");

        std::vector<float> left(blockSize, 0.25F);
        std::vector<float> right(blockSize, -0.125F);
        float* channels[] { left.data(), right.data() };
        auto parameters = mechana::leslie::classicCabinetDefaults();
        parameters.bypass = true;
        engine.process(channels, 2, blockSize, parameters);
        require(std::all_of(left.begin(), left.end(), [](const float value) { return value == 0.25F; }),
                "bypass changed the left channel");
        require(std::all_of(right.begin(), right.end(), [](const float value) { return value == -0.125F; }),
                "bypass changed the right channel");

        engine.reset();
        parameters.bypass = false;
        parameters.rotorMode = mechana::leslie::RotorMode::fast;
        parameters.drive = 0.0F;
        for (std::size_t block = 0; block < sampleRate * 2 / blockSize; ++block) {
            for (std::size_t frame = 0; frame < blockSize; ++frame) {
                const auto sample = static_cast<float>(0.2 * std::sin((block * blockSize + frame) * 0.071));
                left[frame] = sample;
                right[frame] = sample;
            }
            engine.process(channels, 2, blockSize, parameters);
            require(std::all_of(left.begin(), left.end(), [](const float value) { return std::isfinite(value); }),
                    "Leslie produced a non-finite sample");
        }
        require(engine.hornSpeedRpm() > 390.0, "horn did not reach fast speed on schedule");
        require(engine.drumSpeedRpm() > 270.0 && engine.drumSpeedRpm() < engine.hornSpeedRpm(),
                "heavy drum did not accelerate more slowly than the horn");
        require(std::abs(left.back() - right.back()) > 1.0e-5F,
                "stereo microphone geometry did not create channel motion");
        std::cout << "Leslie core tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Leslie core test failure: " << error.what() << '\n';
        return 1;
    }
}

