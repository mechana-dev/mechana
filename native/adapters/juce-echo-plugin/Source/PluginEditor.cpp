/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginEditor.h"

MechanaEchoAudioProcessorEditor::MechanaEchoAudioProcessorEditor(MechanaEchoAudioProcessor& audioProcessor)
    : AudioProcessorEditor(&audioProcessor), processor_(audioProcessor) {
    modelLabel_.setText("Model", juce::dontSendNotification);
    model_.addItem("Echoplex-style Tape", 1);
    model_.addItem("Deluxe Memory Man-style Analog", 2);
    addAndMakeVisible(modelLabel_);
    addAndMakeVisible(model_);
    addAndMakeVisible(bypass_);
    addAndMakeVisible(pingPong_);
    addAndMakeVisible(reset_);
    reset_.onClick = [this] { processor_.resetToCurrentModelDefaults(); };

    addControl(delayLabel_, delay_, "Delay", " ms");
    addControl(feedbackLabel_, feedback_, "Feedback", "");
    addControl(wetLabel_, wet_, "Wet Level", "");
    addControl(dryLabel_, dry_, "Dry Level", "");
    addControl(lowCutLabel_, lowCut_, "Repeat Low-Cut", " Hz");
    addControl(highCutLabel_, highCut_, "Repeat High-Cut", " Hz");
    addControl(saturationLabel_, saturation_, "Age / Drive", "");
    addControl(rateLabel_, rate_, "Modulation Rate", " Hz");
    addControl(depthLabel_, depth_, "Modulation Depth", "");
    configurePercentage(feedback_, 100.0);
    configurePercentage(wet_, 100.0);
    configurePercentage(dry_, 100.0);
    configurePercentage(depth_, 100.0 / 12.0);

    auto& parameters = processor_.parameters();
    modelAttachment_ = std::make_unique<juce::AudioProcessorValueTreeState::ComboBoxAttachment>(parameters, "model", model_);
    bypassAttachment_ =
        std::make_unique<juce::AudioProcessorValueTreeState::ButtonAttachment>(parameters, "bypass", bypass_);
    pingPongAttachment_ =
        std::make_unique<juce::AudioProcessorValueTreeState::ButtonAttachment>(parameters, "pingpong", pingPong_);
    delayAttachment_ = std::make_unique<SliderAttachment>(parameters, "delay", delay_);
    feedbackAttachment_ = std::make_unique<SliderAttachment>(parameters, "feedback", feedback_);
    wetAttachment_ = std::make_unique<SliderAttachment>(parameters, "wet", wet_);
    dryAttachment_ = std::make_unique<SliderAttachment>(parameters, "dry", dry_);
    lowCutAttachment_ = std::make_unique<SliderAttachment>(parameters, "lowcut", lowCut_);
    highCutAttachment_ = std::make_unique<SliderAttachment>(parameters, "highcut", highCut_);
    saturationAttachment_ = std::make_unique<SliderAttachment>(parameters, "saturation", saturation_);
    rateAttachment_ = std::make_unique<SliderAttachment>(parameters, "rate", rate_);
    depthAttachment_ = std::make_unique<SliderAttachment>(parameters, "depth", depth_);
    setSize(620, 610);
}

void MechanaEchoAudioProcessorEditor::configure(juce::Slider& slider, const juce::String& suffix) {
    slider.setSliderStyle(juce::Slider::LinearHorizontal);
    slider.setTextBoxStyle(juce::Slider::TextBoxRight, false, 86, 24);
    slider.setTextValueSuffix(suffix);
}

void MechanaEchoAudioProcessorEditor::configurePercentage(juce::Slider& slider, const double multiplier) {
    slider.setTextValueSuffix(" %");
    slider.textFromValueFunction = [multiplier](const double value) { return juce::String(value * multiplier, 1); };
    slider.valueFromTextFunction = [multiplier](const juce::String& text) {
        return text.getDoubleValue() / multiplier;
    };
}

void MechanaEchoAudioProcessorEditor::addControl(juce::Label& label, juce::Slider& slider,
                                                  const juce::String& text, const juce::String& suffix) {
    addAndMakeVisible(label);
    addAndMakeVisible(slider);
    label.setText(text, juce::dontSendNotification);
    configure(slider, suffix);
}

void MechanaEchoAudioProcessorEditor::paint(juce::Graphics& graphics) {
    graphics.fillAll(juce::Colour::fromRGB(43, 61, 67));
    graphics.setColour(juce::Colour::fromRGB(94, 165, 255));
    graphics.fillRect(0, 0, getWidth(), 5);
}

void MechanaEchoAudioProcessorEditor::resized() {
    auto area = getLocalBounds().reduced(24);
    auto topRow = area.removeFromTop(30);
    bypass_.setBounds(topRow.removeFromRight(100));
    reset_.setBounds(topRow.removeFromRight(130).reduced(4, 0));
    area.removeFromTop(8);
    auto modelRow = area.removeFromTop(34);
    modelLabel_.setBounds(modelRow.removeFromLeft(110));
    model_.setBounds(modelRow);
    area.removeFromTop(8);
    auto row = [&area](juce::Label& label, juce::Slider& slider) {
        auto line = area.removeFromTop(48);
        label.setBounds(line.removeFromLeft(130));
        slider.setBounds(line);
    };
    row(delayLabel_, delay_);
    row(feedbackLabel_, feedback_);
    row(wetLabel_, wet_);
    row(dryLabel_, dry_);
    row(lowCutLabel_, lowCut_);
    row(highCutLabel_, highCut_);
    row(saturationLabel_, saturation_);
    row(rateLabel_, rate_);
    row(depthLabel_, depth_);
    pingPong_.setBounds(area.removeFromTop(30).removeFromRight(120));
}
