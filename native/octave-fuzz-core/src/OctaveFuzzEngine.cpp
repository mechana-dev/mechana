/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include "mechana/fuzz/OctaveFuzzEngine.h"
#include "mechana/audio/Nonlinear.h"
#include <algorithm>
#include <cmath>

namespace mechana::fuzz {
void OctaveFuzzEngine::prepare(double sampleRate, const std::size_t channels) {
    sampleRate_ = std::max(1.0, sampleRate);
    states_.resize(std::max<std::size_t>(1, channels));
    for (auto* smoother : {&drive_, &tone_, &level_, &octave_}) smoother->prepare(sampleRate_, 8.0);
    drive_.reset(target_.drive);
    tone_.reset(target_.tone);
    level_.reset(target_.level);
    octave_.reset(target_.octave);
    reset();
}

void OctaveFuzzEngine::reset() noexcept {
    for (auto& state : states_) {
        state.oversampler.reset();
        state.dcBlocker.prepare(sampleRate_);
        state.dcBlocker.reset();
        state.toneFilter.reset();
    }
}

void OctaveFuzzEngine::setParameters(const Parameters& p) noexcept {
    target_ = p;
    drive_.setTarget(std::clamp(p.drive, 0.0f, 1.0f));
    tone_.setTarget(std::clamp(p.tone, 0.0f, 1.0f));
    level_.setTarget(std::clamp(p.level, 0.0f, 1.5f));
    octave_.setTarget(std::clamp(p.octave, 0.0f, 1.0f));
}

void OctaveFuzzEngine::process(float* const* channels, std::size_t channelCount, std::size_t frames) noexcept {
    if (target_.bypass || channels == nullptr) return;
    channelCount = std::min(channelCount, states_.size());
    for (std::size_t i = 0; i < frames; ++i) {
        const float drive = drive_.next();
        const float tone = tone_.next();
        const float level = level_.next();
        const float octave = octave_.next();
        const float gain = std::pow(10.0F, (6.0F + 30.0F * drive) / 20.0F);
        const float cutoff = 650.0F + tone * 9'350.0F;
        for (std::size_t ch = 0; ch < channelCount; ++ch) {
            auto& state = states_[ch];
            const float input = channels[ch][i];
            const float fuzz = state.oversampler.process(input, [gain](const float sample) noexcept {
                return mechana::audio::asymmetricClip(sample * gain, 1.0F, 0.82F);
            });
            const float octaveSignal = state.dcBlocker.process(mechana::audio::fullWave(fuzz));
            const float mixed = fuzz * (1.0F - octave) + octaveSignal * (octave * 1.35F);
            state.toneFilter.setCutoff(sampleRate_, cutoff);
            const float low = state.toneFilter.process(mixed);
            const float shaped = tone < 0.5F ? low : low + (mixed - low) * ((tone - 0.5F) * 2.0F);
            channels[ch][i] = mechana::audio::softClip(shaped * level * 0.9F);
        }
    }
}
}
