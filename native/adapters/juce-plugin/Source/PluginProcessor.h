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

#include <juce_audio_formats/juce_audio_formats.h>
#include <juce_audio_processors/juce_audio_processors.h>
#include <mechana/reverb/ReverbEngine.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <optional>
#include <thread>
#include <unordered_map>

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
    const juce::String getName() const override;
    bool acceptsMidi() const override { return false; }
    bool producesMidi() const override { return false; }
    bool isMidiEffect() const override { return false; }
    double getTailLengthSeconds() const override { return tailSeconds_.load(); }
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
    [[nodiscard]] double sourceDurationSeconds() const noexcept { return sourceDurationSeconds_.load(); }
    bool selectFactoryProfile(int index);
    bool importImpulseResponse(const juce::File& file);

    static juce::AudioProcessorValueTreeState::ParameterLayout createParameterLayout();

private:
    void loadFactoryImpulseResponse();
    bool loadReader(std::unique_ptr<juce::AudioFormatReader> reader, const juce::String& profileName,
                    const juce::String& sourcePath);
    void requestPreparedResponse();
    void preparationLoop(std::stop_token stopToken);
    void parameterChanged(const juce::String&, float) override;
    void handleAsyncUpdate() override;

    struct PreparationRequest final {
        std::string key;
        std::vector<std::vector<float>> source;
        double sourceSampleRate {};
        double processingSampleRate {};
        std::size_t channelCount {};
        std::size_t maximumBlockSize {};
        mechana::reverb::ImpulseResponseParameters shaping;
    };

    std::shared_ptr<mechana::reverb::ReverbEngine> preparedEngine_;
    std::shared_ptr<mechana::reverb::ReverbEngine> renderingEngine_;
    std::shared_ptr<mechana::reverb::ReverbEngine> fadingEngine_;
    // The preparation thread retains published engines so that replacing or
    // fading one never triggers a large deallocation on the audio thread.
    std::vector<std::shared_ptr<mechana::reverb::ReverbEngine>> engineLifetime_;
    std::unordered_map<std::string, std::vector<std::vector<float>>> preparedCache_;
    std::mutex preparationMutex_;
    std::condition_variable_any preparationCondition_;
    std::optional<PreparationRequest> pendingPreparation_;
    std::jthread preparationThread_;
    juce::AudioBuffer<float> newRenderBuffer_;
    juce::AudioBuffer<float> oldRenderBuffer_;
    std::size_t fadeRemaining_ {};
    std::size_t fadeLength_ {};
    juce::AudioProcessorValueTreeState parameters_;
    double processingSampleRate_ { 48'000.0 };
    std::atomic<double> tailSeconds_ { 0.0 };
    std::atomic<double> sourceDurationSeconds_ { 0.82 };
    double sourceSampleRate_ { 48'000.0 };
    std::size_t maximumBlockSize_ { 512 };
    std::size_t channelCount_ { 2 };
    std::vector<std::vector<float>> sourceImpulseResponse_;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MechanaReverbAudioProcessor)
};
