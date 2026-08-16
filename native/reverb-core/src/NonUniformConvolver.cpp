/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "NonUniformConvolver.h"

#include <algorithm>
#include <array>
#include <cassert>

namespace mechana::reverb {
namespace {
struct Tier final {
    std::size_t offset;
    std::size_t end;
    std::size_t blockSize;
};

constexpr std::array tiers {
    Tier { 0, 2'048, 128 },
    Tier { 2'048, 16'384, 512 },
    Tier { 16'384, static_cast<std::size_t>(-1), 2'048 },
};
} // namespace

void NonUniformConvolver::prepare(const std::span<const float> impulseResponse) {
    stages_.clear();
    stages_.reserve(tiers.size());
    auto maximumScheduledDistance = latency;
    for (const auto tier : tiers) {
        if (tier.offset >= impulseResponse.size())
            break;
        const auto end = std::min(tier.end, impulseResponse.size());
        Stage stage;
        stage.offset = tier.offset;
        stage.blockSize = tier.blockSize;
        stage.delay = tier.offset + latency - tier.blockSize;
        stage.input.assign(tier.blockSize, 0.0F);
        stage.output.assign(tier.blockSize, 0.0F);
        stage.convolver.prepare(impulseResponse.subspan(tier.offset, end - tier.offset), tier.blockSize);
        const auto slices = std::max<std::size_t>(1, tier.blockSize / latency);
        stage.partitionsPerSlice = (stage.convolver.partitionCount() + slices - 1) / slices;
        maximumScheduledDistance = std::max(maximumScheduledDistance, stage.delay + stage.blockSize + 1);
        stages_.push_back(std::move(stage));
    }
    outputRing_.assign(maximumScheduledDistance + 1, 0.0F);
    reset();
}

void NonUniformConvolver::reset() noexcept {
    outputPosition_ = 0;
    std::fill(outputRing_.begin(), outputRing_.end(), 0.0F);
    for (auto& stage : stages_) {
        stage.position = 0;
        stage.samplesUntilWork = 0;
        stage.processing = false;
        std::fill(stage.input.begin(), stage.input.end(), 0.0F);
        std::fill(stage.output.begin(), stage.output.end(), 0.0F);
        stage.convolver.reset();
    }
}

float NonUniformConvolver::processSample(const float input) noexcept {
    const auto result = outputRing_[outputPosition_];
    outputRing_[outputPosition_] = 0.0F;
    for (auto& stage : stages_) {
        if (stage.processing && --stage.samplesUntilWork == 0) {
            processSlice(stage);
            stage.samplesUntilWork = latency;
        }
        stage.input[stage.position++] = input;
        if (stage.position != stage.blockSize)
            continue;
        assert(!stage.processing);
        stage.convolver.beginProcess(stage.input);
        stage.processing = true;
        stage.scheduledFirst = (outputPosition_ + 1 + stage.delay) % outputRing_.size();
        stage.samplesUntilWork = latency;
        processSlice(stage);
        stage.position = 0;
    }
    outputPosition_ = (outputPosition_ + 1) % outputRing_.size();
    return result;
}

void NonUniformConvolver::processSlice(Stage& stage) noexcept {
    if (!stage.convolver.processPartitions(stage.partitionsPerSlice, stage.output))
        return;
    for (std::size_t frame = 0; frame < stage.blockSize; ++frame)
        outputRing_[(stage.scheduledFirst + frame) % outputRing_.size()] += stage.output[frame];
    stage.processing = false;
}

} // namespace mechana::reverb
