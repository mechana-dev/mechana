/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once
#include <algorithm>
#include <cmath>

namespace mechana::audio {
inline float softClip(float x) noexcept { return std::tanh(x); }
inline float hardClip(float x, float threshold = 1.0f) noexcept {
    threshold = std::max(threshold, 0.0001F);
    return std::clamp(x, -threshold, threshold);
}
inline float asymmetricClip(float x, float positive = 1.0f, float negative = 0.75f) noexcept {
    return x >= 0.0f ? std::tanh(x * positive) : std::tanh(x * negative);
}
inline float fullWave(float x) noexcept { return std::abs(x); }
inline float dcBlock(float input, float& previousInput, float& previousOutput, float pole = 0.995f) noexcept {
    const float output = input - previousInput + pole * previousOutput;
    previousInput = input;
    previousOutput = output;
    return output;
}
inline float clampUnit(float x) noexcept { return std::clamp(x, -1.0f, 1.0f); }

enum class Waveshape { soft, hard, asymmetric };
inline float waveshape(const float input, const Waveshape shape) noexcept {
    switch (shape) {
    case Waveshape::hard: return hardClip(input);
    case Waveshape::asymmetric: return asymmetricClip(input);
    case Waveshape::soft: return softClip(input);
    }
    return input;
}
}
