/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once
#include "PluginProcessor.h"
class MechanaOctaveFuzzAudioProcessorEditor final : public juce::AudioProcessorEditor {
public:
    explicit MechanaOctaveFuzzAudioProcessorEditor(MechanaOctaveFuzzAudioProcessor&);
    void paint(juce::Graphics&) override;
    void resized() override;
private:
    using SliderAttachment = juce::AudioProcessorValueTreeState::SliderAttachment;
    MechanaOctaveFuzzAudioProcessor& processor_;
    juce::Label driveLabel_, toneLabel_, levelLabel_, octaveLabel_;
    juce::Slider drive_, tone_, level_, octave_;
    juce::ToggleButton bypass_ { "Bypass" };
    juce::TextButton reset_ { "Reset" };
    std::unique_ptr<SliderAttachment> driveAttachment_, toneAttachment_, levelAttachment_, octaveAttachment_;
    std::unique_ptr<juce::AudioProcessorValueTreeState::ButtonAttachment> bypassAttachment_;
};
