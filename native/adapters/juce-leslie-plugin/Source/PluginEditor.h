/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include "PluginProcessor.h"

class MechanaLeslieAudioProcessorEditor final : public juce::AudioProcessorEditor {
public:
    explicit MechanaLeslieAudioProcessorEditor(MechanaLeslieAudioProcessor&);
    ~MechanaLeslieAudioProcessorEditor() override = default;
    void paint(juce::Graphics&) override;
    void resized() override;

private:
    using SliderAttachment = juce::AudioProcessorValueTreeState::SliderAttachment;
    static void configure(juce::Slider&, const juce::String& suffix);
    static void configurePercentage(juce::Slider&);
    void addControl(juce::Label&, juce::Slider&, const juce::String& label, const juce::String& suffix);

    MechanaLeslieAudioProcessor& processor_;
    juce::Label speedLabel_;
    juce::ComboBox speed_;
    juce::ToggleButton bypass_ { "Bypass" };
    juce::Label driveLabel_, hornLabel_, distanceLabel_, widthLabel_, crossoverLabel_, wetLabel_, dryLabel_;
    juce::Slider drive_, horn_, distance_, width_, crossover_, wet_, dry_;
    juce::TextButton reset_ { "Reset Leslie" };
    std::unique_ptr<juce::AudioProcessorValueTreeState::ComboBoxAttachment> speedAttachment_;
    std::unique_ptr<juce::AudioProcessorValueTreeState::ButtonAttachment> bypassAttachment_;
    std::unique_ptr<SliderAttachment> driveAttachment_, hornAttachment_, distanceAttachment_, widthAttachment_;
    std::unique_ptr<SliderAttachment> crossoverAttachment_, wetAttachment_, dryAttachment_;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MechanaLeslieAudioProcessorEditor)
};
