/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include <cstddef>
#include <memory>
#include <span>
#include <vector>

namespace mechana::reverb {

struct Spectrum final {
    std::vector<float> real;
    std::vector<float> imaginary;
};

class RealFft final {
public:
    explicit RealFft(std::size_t size);
    ~RealFft();
    RealFft(RealFft&&) noexcept;
    RealFft& operator=(RealFft&&) noexcept;
    RealFft(const RealFft&) = delete;
    RealFft& operator=(const RealFft&) = delete;

    [[nodiscard]] Spectrum createSpectrum() const;
    void forward(std::span<const float> input, Spectrum& output) noexcept;
    void inverse(const Spectrum& input, std::span<float> output) noexcept;
    void multiplyAccumulate(const Spectrum& left, const Spectrum& right, Spectrum& result) const noexcept;
    void clear(Spectrum& spectrum) const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace mechana::reverb
