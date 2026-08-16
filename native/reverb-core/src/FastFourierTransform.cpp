/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "FastFourierTransform.h"

#include <algorithm>
#include <cmath>
#include <numbers>

namespace mechana::reverb {

void transform(std::span<float> real, std::span<float> imaginary, const bool inverse) noexcept {
    const auto size = real.size();
    for (std::size_t index = 1, reversed = 0; index < size; ++index) {
        auto bit = size >> 1U;
        for (; (reversed & bit) != 0U; bit >>= 1U)
            reversed ^= bit;
        reversed ^= bit;
        if (index < reversed) {
            std::swap(real[index], real[reversed]);
            std::swap(imaginary[index], imaginary[reversed]);
        }
    }
    for (std::size_t length = 2; length <= size; length <<= 1U) {
        const auto angle = static_cast<float>((inverse ? 2.0 : -2.0) * std::numbers::pi / static_cast<double>(length));
        const auto stepReal = std::cos(angle);
        const auto stepImaginary = std::sin(angle);
        for (std::size_t start = 0; start < size; start += length) {
            auto twiddleReal = 1.0F;
            auto twiddleImaginary = 0.0F;
            for (std::size_t offset = 0; offset < length / 2; ++offset) {
                const auto even = start + offset;
                const auto odd = even + length / 2;
                const auto oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary;
                const auto oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal;
                const auto evenReal = real[even];
                const auto evenImaginary = imaginary[even];
                real[odd] = evenReal - oddReal;
                imaginary[odd] = evenImaginary - oddImaginary;
                real[even] = evenReal + oddReal;
                imaginary[even] = evenImaginary + oddImaginary;
                const auto nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary;
                twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal;
                twiddleReal = nextReal;
            }
        }
        if (length == size)
            break;
    }
    if (inverse)
        for (std::size_t index = 0; index < size; ++index) {
            real[index] /= static_cast<float>(size);
            imaginary[index] /= static_cast<float>(size);
        }
}

} // namespace mechana::reverb
