/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginEditor.h"

MechanaReverbAudioProcessorEditor::MechanaReverbAudioProcessorEditor(MechanaReverbAudioProcessor& audioProcessor)
    : AudioProcessorEditor(&audioProcessor), processor_(audioProcessor) {
    title_.setText("Mechana Reverb", juce::dontSendNotification);
    title_.setFont(juce::FontOptions(26.0F, juce::Font::bold));
    profile_.setText("Captured response", juce::dontSendNotification);
    addAndMakeVisible(title_);
    addAndMakeVisible(profile_);
    addAndMakeVisible(profileSelector_);
    addAndMakeVisible(addProfile_);
    addAndMakeVisible(bypass_);
    addAndMakeVisible(resetMix_);
    addAndMakeVisible(resetCaptured_);
    addAndMakeVisible(resetEq_);
    addAndMakeVisible(wetLabel_);
    addAndMakeVisible(dryLabel_);
    addAndMakeVisible(preDelayLabel_);
    addAndMakeVisible(wet_);
    addAndMakeVisible(dry_);
    addAndMakeVisible(preDelay_);
    addControl(wetLabel_, wet_, "Wet", "");
    addControl(dryLabel_, dry_, "Dry", "");
    addControl(preDelayLabel_, preDelay_, "Pre-delay", " ms");
    addControl(earlyLabel_, early_, "Early", "");
    addControl(lateLabel_, late_, "Late", "");
    addControl(attackLabel_, attack_, "Attack", " ms");
    addControl(decayLabel_, decay_, "Decay", " %");
    addControl(lowCutLabel_, lowCut_, "Low-cut", " Hz");
    addControl(highCutLabel_, highCut_, "High-cut", " Hz");
    wetAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "wet", wet_);
    dryAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "dry", dry_);
    preDelayAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "predelay", preDelay_);
    earlyAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "early", early_);
    lateAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "late", late_);
    attackAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "attack", attack_);
    decayAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "decay", decay_);
    lowCutAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "lowcut", lowCut_);
    highCutAttachment_ = std::make_unique<Attachment>(processor_.parameters(), "highcut", highCut_);
    bypassAttachment_ = std::make_unique<juce::AudioProcessorValueTreeState::ButtonAttachment>(
        processor_.parameters(), "bypass", bypass_);
    refreshProfiles();
    profileSelector_.onChange = [this] {
        const auto selected = profileSelector_.getSelectedId() - 1;
        if (selected >= 0)
            processor_.selectFactoryProfile(selected);
    };
    addProfile_.onClick = [this] {
        fileChooser_ = std::make_unique<juce::FileChooser>("Add an impulse-response WAV", juce::File {}, "*.wav;*.wave");
        fileChooser_->launchAsync(juce::FileBrowserComponent::openMode | juce::FileBrowserComponent::canSelectFiles,
                                  [this](const juce::FileChooser& chooser) {
                                      const auto file = chooser.getResult();
                                      if (file.existsAsFile() && processor_.importImpulseResponse(file)) {
                                          profileSelector_.addItem(processor_.currentProfileName(), 1000);
                                          profileSelector_.setSelectedId(1000, juce::dontSendNotification);
                                      }
                                  });
    };
    resetMix_.onClick = [this] {
        setParameter("wet", 0.0F);
        setParameter("dry", 1.0F);
        setParameter("predelay", 0.0F);
    };
    resetCaptured_.onClick = [this] {
        setParameter("early", 1.0F);
        setParameter("late", 1.0F);
        setParameter("attack", 0.0F);
        setParameter("decay", 100.0F);
    };
    resetEq_.onClick = [this] {
        setParameter("lowcut", 0.0F);
        setParameter("highcut", 0.0F);
    };
    setSize(620, 720);
}

void MechanaReverbAudioProcessorEditor::setParameter(const juce::String& id, float value) {
    if (auto* parameter = processor_.parameters().getParameter(id)) {
        parameter->beginChangeGesture();
        parameter->setValueNotifyingHost(parameter->convertTo0to1(value));
        parameter->endChangeGesture();
    }
}

void MechanaReverbAudioProcessorEditor::configure(juce::Slider& slider, const juce::String& suffix) {
    slider.setSliderStyle(juce::Slider::LinearHorizontal);
    slider.setTextBoxStyle(juce::Slider::TextBoxRight, false, 86, 24);
    slider.setTextValueSuffix(suffix);
}

void MechanaReverbAudioProcessorEditor::addControl(juce::Label& label, juce::Slider& slider,
                                                    const juce::String& text, const juce::String& suffix) {
    addAndMakeVisible(label);
    addAndMakeVisible(slider);
    label.setText(text, juce::dontSendNotification);
    configure(slider, suffix);
}

void MechanaReverbAudioProcessorEditor::refreshProfiles() {
    profileSelector_.clear();
    const auto names = processor_.factoryProfileNames();
    for (int index = 0; index < names.size(); ++index)
        profileSelector_.addItem(names[index], index + 1);
    const auto current = processor_.currentProfileName();
    const auto factoryIndex = names.indexOf(current);
    if (factoryIndex >= 0)
        profileSelector_.setSelectedId(factoryIndex + 1, juce::dontSendNotification);
    else {
        profileSelector_.addItem(current, 1000);
        profileSelector_.setSelectedId(1000, juce::dontSendNotification);
    }
}

void MechanaReverbAudioProcessorEditor::paint(juce::Graphics& graphics) {
    graphics.fillAll(juce::Colour::fromRGB(32, 35, 40));
    graphics.setColour(juce::Colour::fromRGB(94, 165, 255));
    graphics.fillRect(0, 0, getWidth(), 5);
}

void MechanaReverbAudioProcessorEditor::resized() {
    auto area = getLocalBounds().reduced(24);
    title_.setBounds(area.removeFromTop(38));
    bypass_.setBounds(area.removeFromTop(28).removeFromRight(100));
    profile_.setBounds(area.removeFromTop(24));
    auto selector = area.removeFromTop(34);
    addProfile_.setBounds(selector.removeFromRight(90).reduced(4, 0));
    profileSelector_.setBounds(selector);
    area.removeFromTop(8);
    auto row = [&area](juce::Label& label, juce::Slider& slider) {
        auto line = area.removeFromTop(48);
        label.setBounds(line.removeFromLeft(90));
        slider.setBounds(line);
    };
    row(wetLabel_, wet_);
    row(dryLabel_, dry_);
    row(preDelayLabel_, preDelay_);
    resetMix_.setBounds(area.removeFromTop(30).removeFromRight(130));
    area.removeFromTop(4);
    row(earlyLabel_, early_);
    row(lateLabel_, late_);
    row(attackLabel_, attack_);
    row(decayLabel_, decay_);
    resetCaptured_.setBounds(area.removeFromTop(30).removeFromRight(130));
    area.removeFromTop(4);
    row(lowCutLabel_, lowCut_);
    row(highCutLabel_, highCut_);
    resetEq_.setBounds(area.removeFromTop(30).removeFromRight(130));
}
