/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "RealFft.h"

#include "FastFourierTransform.h"

#include <algorithm>
#include <bit>
#include <cassert>

#if defined(__APPLE__)
#include <Accelerate/Accelerate.h>
#endif

namespace mechana::reverb {

class RealFft::Impl final {
public:
    explicit Impl(const std::size_t requestedSize)
        : size(requestedSize), bins(requestedSize / 2 + 1), time(requestedSize), real(requestedSize), imaginary(requestedSize) {
#if defined(__APPLE__)
        log2Size = static_cast<vDSP_Length>(std::countr_zero(size));
        setup = vDSP_create_fftsetup(log2Size, kFFTRadix2);
#endif
    }

    ~Impl() {
#if defined(__APPLE__)
        if (setup != nullptr)
            vDSP_destroy_fftsetup(setup);
#endif
    }

    std::size_t size;
    std::size_t bins;
    std::vector<float> time;
    std::vector<float> real;
    std::vector<float> imaginary;
#if defined(__APPLE__)
    FFTSetup setup {};
    vDSP_Length log2Size {};
#endif
};

RealFft::RealFft(const std::size_t size) : impl_(std::make_unique<Impl>(size)) {
    assert(size >= 2 && (size & (size - 1)) == 0);
}
RealFft::~RealFft() = default;
RealFft::RealFft(RealFft&&) noexcept = default;
RealFft& RealFft::operator=(RealFft&&) noexcept = default;

Spectrum RealFft::createSpectrum() const {
    return { std::vector<float>(impl_->bins), std::vector<float>(impl_->bins) };
}

void RealFft::clear(Spectrum& spectrum) const noexcept {
    std::fill(spectrum.real.begin(), spectrum.real.end(), 0.0F);
    std::fill(spectrum.imaginary.begin(), spectrum.imaginary.end(), 0.0F);
}

void RealFft::forward(const std::span<const float> input, Spectrum& output) noexcept {
    std::fill(impl_->time.begin(), impl_->time.end(), 0.0F);
    std::copy(input.begin(), input.end(), impl_->time.begin());
#if defined(__APPLE__)
    DSPSplitComplex split { impl_->real.data(), impl_->imaginary.data() };
    for (std::size_t index = 0; index < impl_->size / 2; ++index) {
        split.realp[index] = impl_->time[index * 2];
        split.imagp[index] = impl_->time[index * 2 + 1];
    }
    vDSP_fft_zrip(impl_->setup, &split, 1, impl_->log2Size, FFT_FORWARD);
    output.real[0] = split.realp[0] * 0.5F;
    output.imaginary[0] = 0.0F;
    output.real[impl_->size / 2] = split.imagp[0] * 0.5F;
    output.imaginary[impl_->size / 2] = 0.0F;
    for (std::size_t bin = 1; bin < impl_->size / 2; ++bin) {
        output.real[bin] = split.realp[bin] * 0.5F;
        output.imaginary[bin] = split.imagp[bin] * 0.5F;
    }
#else
    std::copy(impl_->time.begin(), impl_->time.end(), impl_->real.begin());
    std::fill(impl_->imaginary.begin(), impl_->imaginary.end(), 0.0F);
    transform(impl_->real, impl_->imaginary, false);
    std::copy_n(impl_->real.begin(), impl_->bins, output.real.begin());
    std::copy_n(impl_->imaginary.begin(), impl_->bins, output.imaginary.begin());
#endif
}

void RealFft::inverse(const Spectrum& input, const std::span<float> output) noexcept {
#if defined(__APPLE__)
    DSPSplitComplex split { impl_->real.data(), impl_->imaginary.data() };
    split.realp[0] = input.real[0];
    split.imagp[0] = input.real[impl_->size / 2];
    for (std::size_t bin = 1; bin < impl_->size / 2; ++bin) {
        split.realp[bin] = input.real[bin];
        split.imagp[bin] = input.imaginary[bin];
    }
    vDSP_fft_zrip(impl_->setup, &split, 1, impl_->log2Size, FFT_INVERSE);
    const auto scale = 1.0F / static_cast<float>(impl_->size);
    vDSP_vsmul(split.realp, 1, &scale, split.realp, 1, impl_->size / 2);
    vDSP_vsmul(split.imagp, 1, &scale, split.imagp, 1, impl_->size / 2);
    for (std::size_t index = 0; index < impl_->size / 2; ++index) {
        impl_->time[index * 2] = split.realp[index];
        impl_->time[index * 2 + 1] = split.imagp[index];
    }
    std::copy_n(impl_->time.begin(), output.size(), output.begin());
#else
    std::fill(impl_->real.begin(), impl_->real.end(), 0.0F);
    std::fill(impl_->imaginary.begin(), impl_->imaginary.end(), 0.0F);
    for (std::size_t bin = 0; bin < impl_->bins; ++bin) {
        impl_->real[bin] = input.real[bin];
        impl_->imaginary[bin] = input.imaginary[bin];
    }
    for (std::size_t bin = 1; bin < impl_->size / 2; ++bin) {
        impl_->real[impl_->size - bin] = input.real[bin];
        impl_->imaginary[impl_->size - bin] = -input.imaginary[bin];
    }
    transform(impl_->real, impl_->imaginary, true);
    std::copy_n(impl_->real.begin(), output.size(), output.begin());
#endif
}

void RealFft::multiplyAccumulate(const Spectrum& left, const Spectrum& right, Spectrum& result) const noexcept {
    const auto bins = impl_->bins;
#if defined(__APPLE__)
    // Clang vectorizes this contiguous float loop; vDSP supplies the optimized FFT backend.
#endif
    for (std::size_t bin = 0; bin < bins; ++bin) {
        result.real[bin] += left.real[bin] * right.real[bin] - left.imaginary[bin] * right.imaginary[bin];
        result.imaginary[bin] += left.real[bin] * right.imaginary[bin] + left.imaginary[bin] * right.real[bin];
    }
}

} // namespace mechana::reverb
