/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PartitionedConvolver.h"

#include "FastFourierTransform.h"

#include <algorithm>
#include <cassert>
#include <cmath>

namespace mechana::reverb {

void PartitionedConvolver::prepare(const std::span<const float> ir, const std::size_t blockSize) {
    blockSize_ = blockSize;
    fftSize_ = blockSize * 2;
    const auto count = std::max<std::size_t>(1, (ir.size() + blockSize - 1) / blockSize);
    irReal_.assign(count, std::vector<double>(fftSize_));
    irImaginary_.assign(count, std::vector<double>(fftSize_));
    inputReal_.assign(count, std::vector<double>(fftSize_));
    inputImaginary_.assign(count, std::vector<double>(fftSize_));
    resultReal_.assign(fftSize_, 0.0);
    resultImaginary_.assign(fftSize_, 0.0);
    overlap_.assign(blockSize_, 0.0);
    for (std::size_t partition = 0; partition < count; ++partition) {
        const auto offset = partition * blockSize_;
        const auto frames = offset < ir.size() ? std::min(blockSize_, ir.size() - offset) : 0;
        for (std::size_t frame = 0; frame < frames; ++frame)
            irReal_[partition][frame] = ir[offset + frame];
        transform(irReal_[partition], irImaginary_[partition], false);
    }
    reset();
}

void PartitionedConvolver::reset() noexcept {
    ringIndex_ = 0;
    for (auto& values : inputReal_)
        std::fill(values.begin(), values.end(), 0.0);
    for (auto& values : inputImaginary_)
        std::fill(values.begin(), values.end(), 0.0);
    std::fill(overlap_.begin(), overlap_.end(), 0.0);
}

void PartitionedConvolver::process(const std::span<const float> input, const std::span<float> output) noexcept {
    assert(input.size() == blockSize_ && output.size() == blockSize_);
    auto& currentReal = inputReal_[ringIndex_];
    auto& currentImaginary = inputImaginary_[ringIndex_];
    std::fill(currentReal.begin(), currentReal.end(), 0.0);
    std::fill(currentImaginary.begin(), currentImaginary.end(), 0.0);
    std::copy(input.begin(), input.end(), currentReal.begin());
    transform(currentReal, currentImaginary, false);
    std::fill(resultReal_.begin(), resultReal_.end(), 0.0);
    std::fill(resultImaginary_.begin(), resultImaginary_.end(), 0.0);
    for (std::size_t partition = 0; partition < irReal_.size(); ++partition) {
        const auto inputPartition = (ringIndex_ + irReal_.size() - partition) % irReal_.size();
        for (std::size_t bin = 0; bin < fftSize_; ++bin) {
            resultReal_[bin] += inputReal_[inputPartition][bin] * irReal_[partition][bin]
                                - inputImaginary_[inputPartition][bin] * irImaginary_[partition][bin];
            resultImaginary_[bin] += inputReal_[inputPartition][bin] * irImaginary_[partition][bin]
                                     + inputImaginary_[inputPartition][bin] * irReal_[partition][bin];
        }
    }
    transform(resultReal_, resultImaginary_, true);
    for (std::size_t frame = 0; frame < blockSize_; ++frame) {
        output[frame] = static_cast<float>(resultReal_[frame] + overlap_[frame]);
        overlap_[frame] = resultReal_[frame + blockSize_];
    }
    ringIndex_ = (ringIndex_ + 1) % irReal_.size();
}

} // namespace mechana::reverb
