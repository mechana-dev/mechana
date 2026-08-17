/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include "PluginProcessor.h"

class MechanaEchoAudioProcessorEditor final : public juce::AudioProcessorEditor {
public:
    explicit MechanaEchoAudioProcessorEditor(MechanaEchoAudioProcessor&);
    ~MechanaEchoAudioProcessorEditor() override = default;
    void paint(juce::Graphics&) override;
    void resized() override;

private:
    using SliderAttachment = juce::AudioProcessorValueTreeState::SliderAttachment;
    static void configure(juce::Slider&, const juce::String& suffix);
    static void configurePercentage(juce::Slider&, double multiplier);
    void addControl(juce::Label&, juce::Slider&, const juce::String& label, const juce::String& suffix);

    MechanaEchoAudioProcessor& processor_;
    juce::Label modelLabel_;
    juce::ComboBox model_;
    juce::ToggleButton bypass_ { "Bypass" };
    juce::Label delayLabel_, feedbackLabel_, wetLabel_, dryLabel_, lowCutLabel_, highCutLabel_;
    juce::Label saturationLabel_, rateLabel_, depthLabel_;
    juce::Slider delay_, feedback_, wet_, dry_, lowCut_, highCut_, saturation_, rate_, depth_;
    juce::ToggleButton pingPong_ { "Ping-Pong" };
    juce::TextButton reset_ { "Reset Echo" };
    std::unique_ptr<juce::AudioProcessorValueTreeState::ComboBoxAttachment> modelAttachment_;
    std::unique_ptr<juce::AudioProcessorValueTreeState::ButtonAttachment> bypassAttachment_;
    std::unique_ptr<juce::AudioProcessorValueTreeState::ButtonAttachment> pingPongAttachment_;
    std::unique_ptr<SliderAttachment> delayAttachment_, feedbackAttachment_, wetAttachment_, dryAttachment_;
    std::unique_ptr<SliderAttachment> lowCutAttachment_, highCutAttachment_, saturationAttachment_;
    std::unique_ptr<SliderAttachment> rateAttachment_, depthAttachment_;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MechanaEchoAudioProcessorEditor)
};
