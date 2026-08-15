/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
#pragma once

#include <JuceHeader.h>
#include <mechana/reverb/ReverbEngine.h>

class MechanaReverbAudioProcessor final : public juce::AudioProcessor,
                                          private juce::AudioProcessorValueTreeState::Listener,
                                          private juce::AsyncUpdater {
public:
    MechanaReverbAudioProcessor();
    ~MechanaReverbAudioProcessor() override;

    void prepareToPlay(double sampleRate, int samplesPerBlock) override;
    void releaseResources() override;
    bool isBusesLayoutSupported(const BusesLayout& layouts) const override;
    void processBlock(juce::AudioBuffer<float>&, juce::MidiBuffer&) override;

    juce::AudioProcessorEditor* createEditor() override;
    bool hasEditor() const override { return true; }
    const juce::String getName() const override { return JucePlugin_Name; }
    bool acceptsMidi() const override { return false; }
    bool producesMidi() const override { return false; }
    bool isMidiEffect() const override { return false; }
    double getTailLengthSeconds() const override { return tailSeconds_; }
    int getNumPrograms() override { return 1; }
    int getCurrentProgram() override { return 0; }
    void setCurrentProgram(int) override {}
    const juce::String getProgramName(int) override { return {}; }
    void changeProgramName(int, const juce::String&) override {}
    void getStateInformation(juce::MemoryBlock&) override;
    void setStateInformation(const void*, int) override;

    juce::AudioProcessorValueTreeState& parameters() noexcept { return parameters_; }
    [[nodiscard]] juce::StringArray factoryProfileNames() const;
    [[nodiscard]] juce::String currentProfileName() const;
    bool selectFactoryProfile(int index);
    bool importImpulseResponse(const juce::File& file);

    static juce::AudioProcessorValueTreeState::ParameterLayout createParameterLayout();

private:
    void loadFactoryImpulseResponse();
    bool loadReader(std::unique_ptr<juce::AudioFormatReader> reader, const juce::String& profileName,
                    const juce::String& sourcePath);
    void rebuildPreparedResponse();
    void parameterChanged(const juce::String&, float) override;
    void handleAsyncUpdate() override;

    mechana::reverb::ReverbEngine engine_;
    juce::AudioProcessorValueTreeState parameters_;
    double processingSampleRate_ { 48'000.0 };
    double tailSeconds_ { 0.0 };
    double sourceSampleRate_ { 48'000.0 };
    std::vector<std::vector<float>> sourceImpulseResponse_;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MechanaReverbAudioProcessor)
};
