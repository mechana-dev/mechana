/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
#pragma once

#include "Parameters.h"

#include <cstddef>
#include <memory>

namespace mechana::leslie {

class LeslieEngine final {
public:
    LeslieEngine();
    ~LeslieEngine();
    LeslieEngine(LeslieEngine&&) noexcept;
    LeslieEngine& operator=(LeslieEngine&&) noexcept;
    LeslieEngine(const LeslieEngine&) = delete;
    LeslieEngine& operator=(const LeslieEngine&) = delete;

    void prepare(double sampleRate, std::size_t channelCount, std::size_t maximumBlockSize);
    void reset() noexcept;
    void process(float* const* channels, std::size_t channelCount, std::size_t frameCount,
                 const Parameters& parameters) noexcept;

    [[nodiscard]] std::size_t latencySamples() const noexcept { return 0; }
    [[nodiscard]] double hornSpeedRpm() const noexcept;
    [[nodiscard]] double drumSpeedRpm() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace mechana::leslie

