/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#pragma once
#include <juce_audio_processors/juce_audio_processors.h>
#include <mechana/fuzz/OctaveFuzzEngine.h>

class MechanaOctaveFuzzAudioProcessor final : public juce::AudioProcessor {
public:
    MechanaOctaveFuzzAudioProcessor();
    void prepareToPlay(double, int) override;
    void releaseResources() override;
    bool isBusesLayoutSupported(const BusesLayout&) const override;
    void processBlock(juce::AudioBuffer<float>&, juce::MidiBuffer&) override;
    juce::AudioProcessorEditor* createEditor() override;
    bool hasEditor() const override { return true; }
    const juce::String getName() const override { return "Mechana Octave Fuzz"; }
    bool acceptsMidi() const override { return false; }
    bool producesMidi() const override { return false; }
    bool isMidiEffect() const override { return false; }
    double getTailLengthSeconds() const override { return 0.0; }
    int getNumPrograms() override { return 1; }
    int getCurrentProgram() override { return 0; }
    void setCurrentProgram(int) override { resetToDefaults(); }
    const juce::String getProgramName(int index) override { return index == 0 ? "Mechana Octave Fuzz Default" : ""; }
    void changeProgramName(int, const juce::String&) override {}
    void getStateInformation(juce::MemoryBlock&) override;
    void setStateInformation(const void*, int) override;
    void resetToDefaults();
    juce::AudioProcessorValueTreeState& parameters() noexcept { return parameters_; }
private:
    static juce::AudioProcessorValueTreeState::ParameterLayout createParameterLayout();
    mechana::fuzz::Parameters currentParameters() const noexcept;
    mechana::fuzz::OctaveFuzzEngine engine_;
    juce::AudioProcessorValueTreeState parameters_;
    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MechanaOctaveFuzzAudioProcessor)
};
