/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include <cstddef>
#include <span>
#include <vector>

namespace mechana::reverb {

class PartitionedConvolver final {
public:
    void prepare(std::span<const float> impulseResponse, std::size_t blockSize);
    void reset() noexcept;
    void process(std::span<const float> input, std::span<float> output) noexcept;

private:
    std::size_t blockSize_ {};
    std::size_t fftSize_ {};
    std::size_t ringIndex_ {};
    std::vector<std::vector<double>> irReal_;
    std::vector<std::vector<double>> irImaginary_;
    std::vector<std::vector<double>> inputReal_;
    std::vector<std::vector<double>> inputImaginary_;
    std::vector<double> resultReal_;
    std::vector<double> resultImaginary_;
    std::vector<double> overlap_;
};

} // namespace mechana::reverb
