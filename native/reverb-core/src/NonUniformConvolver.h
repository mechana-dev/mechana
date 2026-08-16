/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include "PartitionedConvolver.h"

#include <cstddef>
#include <span>
#include <vector>

namespace mechana::reverb {

class NonUniformConvolver final {
public:
    static constexpr std::size_t latency = 128;

    void prepare(std::span<const float> impulseResponse);
    void reset() noexcept;
    float processSample(float input) noexcept;

private:
    struct Stage final {
        std::size_t offset {};
        std::size_t blockSize {};
        std::size_t delay {};
        std::size_t position {};
        PartitionedConvolver convolver;
        std::vector<float> input;
        std::vector<float> output;
    };

    std::vector<Stage> stages_;
    std::vector<float> outputRing_;
    std::size_t outputPosition_ {};
};

} // namespace mechana::reverb
