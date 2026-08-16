/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include <cstddef>
#include <memory>
#include <span>
#include <vector>

#include "RealFft.h"

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
    std::unique_ptr<RealFft> fft_;
    std::vector<Spectrum> irSpectra_;
    std::vector<Spectrum> inputSpectra_;
    Spectrum result_;
    std::vector<float> transformInput_;
    std::vector<float> transformOutput_;
    std::vector<float> overlap_;
};

} // namespace mechana::reverb
