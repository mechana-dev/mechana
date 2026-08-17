/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginEditor.h"

MechanaLeslieAudioProcessorEditor::MechanaLeslieAudioProcessorEditor(MechanaLeslieAudioProcessor& audioProcessor)
    : AudioProcessorEditor(&audioProcessor), processor_(audioProcessor) {
    speedLabel_.setText("Rotor Speed", juce::dontSendNotification);
    speed_.addItem("Stopped", 1);
    speed_.addItem("Slow / Chorale", 2);
    speed_.addItem("Fast / Tremolo", 3);
    addAndMakeVisible(speedLabel_);
    addAndMakeVisible(speed_);
    addAndMakeVisible(bypass_);
    addAndMakeVisible(reset_);
    reset_.onClick = [this] { processor_.resetToDefaults(); };

    addControl(driveLabel_, drive_, "Drive", "");
    addControl(hornLabel_, horn_, "Horn Balance", "");
    addControl(distanceLabel_, distance_, "Mic Distance", "");
    addControl(widthLabel_, width_, "Stereo Width", "");
    addControl(crossoverLabel_, crossover_, "Crossover", " Hz");
    addControl(wetLabel_, wet_, "Wet Level", "");
    addControl(dryLabel_, dry_, "Dry Level", "");
    for (auto* slider : { &drive_, &horn_, &distance_, &width_, &wet_, &dry_ })
        configurePercentage(*slider);

    auto& parameters = processor_.parameters();
    speedAttachment_ = std::make_unique<juce::AudioProcessorValueTreeState::ComboBoxAttachment>(parameters, "speed", speed_);
    bypassAttachment_ = std::make_unique<juce::AudioProcessorValueTreeState::ButtonAttachment>(parameters, "bypass", bypass_);
    driveAttachment_ = std::make_unique<SliderAttachment>(parameters, "drive", drive_);
    hornAttachment_ = std::make_unique<SliderAttachment>(parameters, "horn", horn_);
    distanceAttachment_ = std::make_unique<SliderAttachment>(parameters, "distance", distance_);
    widthAttachment_ = std::make_unique<SliderAttachment>(parameters, "width", width_);
    crossoverAttachment_ = std::make_unique<SliderAttachment>(parameters, "crossover", crossover_);
    wetAttachment_ = std::make_unique<SliderAttachment>(parameters, "wet", wet_);
    dryAttachment_ = std::make_unique<SliderAttachment>(parameters, "dry", dry_);
    setSize(620, 500);
}

void MechanaLeslieAudioProcessorEditor::configure(juce::Slider& slider, const juce::String& suffix) {
    slider.setSliderStyle(juce::Slider::LinearHorizontal);
    slider.setTextBoxStyle(juce::Slider::TextBoxRight, false, 86, 24);
    slider.setTextValueSuffix(suffix);
}

void MechanaLeslieAudioProcessorEditor::configurePercentage(juce::Slider& slider) {
    slider.setTextValueSuffix(" %");
    slider.textFromValueFunction = [](const double value) { return juce::String(value * 100.0, 1); };
    slider.valueFromTextFunction = [](const juce::String& text) { return text.getDoubleValue() / 100.0; };
}

void MechanaLeslieAudioProcessorEditor::addControl(juce::Label& label, juce::Slider& slider,
                                                    const juce::String& text, const juce::String& suffix) {
    addAndMakeVisible(label);
    addAndMakeVisible(slider);
    label.setText(text, juce::dontSendNotification);
    configure(slider, suffix);
}

void MechanaLeslieAudioProcessorEditor::paint(juce::Graphics& graphics) {
    graphics.fillAll(juce::Colour::fromRGB(38, 45, 36));
    graphics.setColour(juce::Colour::fromRGB(201, 160, 76));
    graphics.fillRect(0, 0, getWidth(), 5);
}

void MechanaLeslieAudioProcessorEditor::resized() {
    auto area = getLocalBounds().reduced(24);
    auto topRow = area.removeFromTop(30);
    bypass_.setBounds(topRow.removeFromRight(100));
    reset_.setBounds(topRow.removeFromRight(130).reduced(4, 0));
    area.removeFromTop(8);
    auto speedRow = area.removeFromTop(34);
    speedLabel_.setBounds(speedRow.removeFromLeft(130));
    speed_.setBounds(speedRow);
    area.removeFromTop(8);
    auto row = [&area](juce::Label& label, juce::Slider& slider) {
        auto line = area.removeFromTop(48);
        label.setBounds(line.removeFromLeft(130));
        slider.setBounds(line);
    };
    row(driveLabel_, drive_);
    row(hornLabel_, horn_);
    row(distanceLabel_, distance_);
    row(widthLabel_, width_);
    row(crossoverLabel_, crossover_);
    row(wetLabel_, wet_);
    row(dryLabel_, dry_);
}

