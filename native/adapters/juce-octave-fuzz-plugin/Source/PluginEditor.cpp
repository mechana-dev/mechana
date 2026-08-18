/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include "PluginEditor.h"
MechanaOctaveFuzzAudioProcessorEditor::MechanaOctaveFuzzAudioProcessorEditor(MechanaOctaveFuzzAudioProcessor& p)
    : AudioProcessorEditor(&p), processor_(p) {
    auto add = [this](juce::Label& label, juce::Slider& slider, const juce::String& text) {
        label.setText(text, juce::dontSendNotification);
        slider.setSliderStyle(juce::Slider::LinearHorizontal);
        slider.setTextBoxStyle(juce::Slider::TextBoxRight, false, 80, 24);
        addAndMakeVisible(label); addAndMakeVisible(slider);
    };
    add(driveLabel_, drive_, "Drive / Fuzz"); add(toneLabel_, tone_, "Tone");
    add(levelLabel_, level_, "Output / Level"); add(octaveLabel_, octave_, "Octave Blend");
    addAndMakeVisible(bypass_); addAndMakeVisible(reset_);
    reset_.onClick = [this] { processor_.resetToDefaults(); };
    auto& state = processor_.parameters();
    driveAttachment_ = std::make_unique<SliderAttachment>(state, "drive", drive_);
    toneAttachment_ = std::make_unique<SliderAttachment>(state, "tone", tone_);
    levelAttachment_ = std::make_unique<SliderAttachment>(state, "level", level_);
    octaveAttachment_ = std::make_unique<SliderAttachment>(state, "octave", octave_);
    bypassAttachment_ = std::make_unique<juce::AudioProcessorValueTreeState::ButtonAttachment>(state, "bypass", bypass_);
    setSize(560, 300);
}
void MechanaOctaveFuzzAudioProcessorEditor::paint(juce::Graphics& g) {
    g.fillAll(juce::Colour(0xff35283c)); g.setColour(juce::Colour(0xfff0a64a)); g.fillRect(0, 0, getWidth(), 5);
}
void MechanaOctaveFuzzAudioProcessorEditor::resized() {
    auto area = getLocalBounds().reduced(24); auto top = area.removeFromTop(32);
    bypass_.setBounds(top.removeFromRight(100)); reset_.setBounds(top.removeFromRight(100));
    auto row = [&area](juce::Label& label, juce::Slider& slider) {
        auto line = area.removeFromTop(52); label.setBounds(line.removeFromLeft(130)); slider.setBounds(line);
    };
    row(driveLabel_, drive_); row(toneLabel_, tone_); row(levelLabel_, level_); row(octaveLabel_, octave_);
}
