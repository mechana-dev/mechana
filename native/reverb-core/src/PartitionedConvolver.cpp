/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PartitionedConvolver.h"

#include <algorithm>
#include <cassert>
#include <cmath>

namespace mechana::reverb {

void PartitionedConvolver::prepare(const std::span<const float> ir, const std::size_t blockSize) {
    blockSize_ = blockSize;
    fftSize_ = blockSize * 2;
    const auto count = std::max<std::size_t>(1, (ir.size() + blockSize - 1) / blockSize);
    fft_ = std::make_unique<RealFft>(fftSize_);
    irSpectra_.clear();
    inputSpectra_.clear();
    irSpectra_.reserve(count);
    inputSpectra_.reserve(count);
    for (std::size_t partition = 0; partition < count; ++partition) {
        irSpectra_.push_back(fft_->createSpectrum());
        inputSpectra_.push_back(fft_->createSpectrum());
    }
    result_ = fft_->createSpectrum();
    transformInput_.assign(fftSize_, 0.0F);
    transformOutput_.assign(fftSize_, 0.0F);
    overlap_.assign(blockSize_, 0.0F);
    for (std::size_t partition = 0; partition < count; ++partition) {
        const auto offset = partition * blockSize_;
        const auto frames = offset < ir.size() ? std::min(blockSize_, ir.size() - offset) : 0;
        std::fill(transformInput_.begin(), transformInput_.end(), 0.0F);
        for (std::size_t frame = 0; frame < frames; ++frame)
            transformInput_[frame] = ir[offset + frame];
        fft_->forward(transformInput_, irSpectra_[partition]);
    }
    reset();
}

void PartitionedConvolver::reset() noexcept {
    ringIndex_ = 0;
    pendingPartition_ = 0;
    processing_ = false;
    for (auto& spectrum : inputSpectra_)
        fft_->clear(spectrum);
    std::fill(overlap_.begin(), overlap_.end(), 0.0F);
}

void PartitionedConvolver::process(const std::span<const float> input, const std::span<float> output) noexcept {
    assert(input.size() == blockSize_ && output.size() == blockSize_);
    beginProcess(input);
    const auto completed = processPartitions(irSpectra_.size(), output);
    assert(completed);
    (void) completed;
}

void PartitionedConvolver::beginProcess(const std::span<const float> input) noexcept {
    assert(input.size() == blockSize_ && !processing_);
    std::fill(transformInput_.begin(), transformInput_.end(), 0.0F);
    std::copy(input.begin(), input.end(), transformInput_.begin());
    fft_->forward(transformInput_, inputSpectra_[ringIndex_]);
    fft_->clear(result_);
    pendingPartition_ = 0;
    processing_ = true;
}

bool PartitionedConvolver::processPartitions(const std::size_t maximumPartitions,
                                             const std::span<float> output) noexcept {
    assert(processing_ && output.size() == blockSize_ && maximumPartitions > 0);
    const auto end = std::min(irSpectra_.size(), pendingPartition_ + maximumPartitions);
    for (auto partition = pendingPartition_; partition < end; ++partition) {
        const auto inputPartition = (ringIndex_ + irSpectra_.size() - partition) % irSpectra_.size();
        fft_->multiplyAccumulate(inputSpectra_[inputPartition], irSpectra_[partition], result_);
    }
    pendingPartition_ = end;
    if (pendingPartition_ != irSpectra_.size())
        return false;
    fft_->inverse(result_, transformOutput_);
    for (std::size_t frame = 0; frame < blockSize_; ++frame) {
        output[frame] = transformOutput_[frame] + overlap_[frame];
        overlap_[frame] = transformOutput_[frame + blockSize_];
    }
    ringIndex_ = (ringIndex_ + 1) % irSpectra_.size();
    processing_ = false;
    return true;
}

std::size_t PartitionedConvolver::partitionCount() const noexcept {
    return irSpectra_.size();
}

} // namespace mechana::reverb
