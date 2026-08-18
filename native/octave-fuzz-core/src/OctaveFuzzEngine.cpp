/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include "mechana/fuzz/OctaveFuzzEngine.h"
#include "mechana/audio/Nonlinear.h"
#include <algorithm>
#include <cmath>

namespace mechana::fuzz {
void OctaveFuzzEngine::prepare(double sampleRate, std::size_t) {
    sampleRate_ = sampleRate;
    for (auto* smoother : {&drive_, &tone_, &level_, &octave_}) smoother->prepare(sampleRate_, 8.0);
    drive_.reset(target_.drive);
    tone_.reset(target_.tone);
    level_.reset(target_.level);
    octave_.reset(target_.octave);
    reset();
}

void OctaveFuzzEngine::reset() noexcept {
    states_[0] = {};
    states_[1] = {};
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
    channelCount = std::min<std::size_t>(channelCount, 2);
    for (std::size_t i = 0; i < frames; ++i) {
        const float drive = drive_.next();
        const float tone = tone_.next();
        const float level = level_.next();
        const float octave = octave_.next();
        const float gain = 1.0f + 24.0f * drive;
        const float cutoff = 700.0f + tone * 8500.0f;
        const float alpha = 1.0f - std::exp(-6.28318530718f * cutoff / static_cast<float>(sampleRate_));
        for (std::size_t ch = 0; ch < channelCount; ++ch) {
            auto& state = states_[ch];
            const float input = channels[ch][i];
            const float fuzz = mechana::audio::softClip(input * gain);
            const float rectified = mechana::audio::fullWave(fuzz) * 2.0f - 0.65f;
            const float octaveSignal = mechana::audio::dcBlock(rectified, state.dcIn, state.dcOut);
            const float mixed = fuzz * (1.0f - octave) + octaveSignal * octave;
            state.toneState += alpha * (mixed - state.toneState);
            const float shaped = tone < 0.5f
                ? state.toneState
                : state.toneState + (mixed - state.toneState) * ((tone - 0.5f) * 2.0f);
            channels[ch][i] = mechana::audio::clampUnit(shaped * level);
        }
    }
}
}
