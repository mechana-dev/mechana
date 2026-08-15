/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#pragma once

#include "PluginProcessor.h"

class MechanaReverbAudioProcessorEditor final : public juce::AudioProcessorEditor {
public:
    explicit MechanaReverbAudioProcessorEditor(MechanaReverbAudioProcessor&);
    ~MechanaReverbAudioProcessorEditor() override = default;
    void paint(juce::Graphics&) override;
    void resized() override;

private:
    using Attachment = juce::AudioProcessorValueTreeState::SliderAttachment;
    static void configure(juce::Slider&, const juce::String& suffix);
    void addControl(juce::Label&, juce::Slider&, const juce::String& label, const juce::String& suffix);
    void refreshProfiles();
    void setParameter(const juce::String& id, float value);

    MechanaReverbAudioProcessor& processor_;
    juce::Label title_;
    juce::Label profile_;
    juce::ComboBox profileSelector_;
    juce::TextButton addProfile_ { "Add..." };
    juce::ToggleButton bypass_ { "Bypass" };
    juce::Label wetLabel_;
    juce::Label dryLabel_;
    juce::Label preDelayLabel_;
    juce::Slider wet_;
    juce::Slider dry_;
    juce::Slider preDelay_;
    juce::Label earlyLabel_, lateLabel_, attackLabel_, decayLabel_, lowCutLabel_, highCutLabel_;
    juce::Slider early_, late_, attack_, decay_, lowCut_, highCut_;
    juce::TextButton resetMix_ { "Reset Mix" };
    juce::TextButton resetCaptured_ { "Reset Captured" };
    juce::TextButton resetEq_ { "Reset EQ" };
    std::unique_ptr<Attachment> wetAttachment_;
    std::unique_ptr<Attachment> dryAttachment_;
    std::unique_ptr<Attachment> preDelayAttachment_;
    std::unique_ptr<Attachment> earlyAttachment_, lateAttachment_, attackAttachment_, decayAttachment_;
    std::unique_ptr<Attachment> lowCutAttachment_, highCutAttachment_;
    std::unique_ptr<juce::AudioProcessorValueTreeState::ButtonAttachment> bypassAttachment_;
    std::unique_ptr<juce::FileChooser> fileChooser_;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MechanaReverbAudioProcessorEditor)
};
