/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
#include <juce_audio_utils/juce_audio_utils.h>
#include <juce_gui_extra/juce_gui_extra.h>

#include "../../../adapters/juce-echo-plugin/Source/PluginProcessor.h"
#include "../../../adapters/juce-plugin/Source/PluginProcessor.h"
#include "../../../adapters/juce-octave-fuzz-plugin/Source/PluginProcessor.h"

namespace {
class EffectsComponent final : public juce::AudioAppComponent {
public:
    EffectsComponent() : tabs(juce::TabbedButtonBar::TabsAtTop) {
        addAndMakeVisible(tabs);
        tabs.addTab("Reverb", juce::Colour(0xff24333a), reverb.createEditor(), true);
        tabs.addTab("Echo", juce::Colour(0xff3a3024), echo.createEditor(), true);
        tabs.addTab("Octave Fuzz", juce::Colour(0xff35283c), octaveFuzz.createEditor(), true);
        setSize(1'080, 820);
        setAudioChannels(2, 2);
    }

    ~EffectsComponent() override { shutdownAudio(); }

    void prepareToPlay(const int samplesPerBlockExpected, const double sampleRate) override {
        reverb.setPlayConfigDetails(2, 2, sampleRate, samplesPerBlockExpected);
        echo.setPlayConfigDetails(2, 2, sampleRate, samplesPerBlockExpected);
        octaveFuzz.setPlayConfigDetails(2, 2, sampleRate, samplesPerBlockExpected);
        reverb.prepareToPlay(sampleRate, samplesPerBlockExpected);
        echo.prepareToPlay(sampleRate, samplesPerBlockExpected);
        octaveFuzz.prepareToPlay(sampleRate, samplesPerBlockExpected);
    }

    void releaseResources() override {
        reverb.releaseResources();
        echo.releaseResources();
        octaveFuzz.releaseResources();
    }

    void getNextAudioBlock(const juce::AudioSourceChannelInfo& info) override {
        if (info.buffer == nullptr)
            return;
        juce::MidiBuffer midi;
        auto block = juce::AudioBuffer<float>(info.buffer->getArrayOfWritePointers(), info.buffer->getNumChannels(),
                                              info.startSample, info.numSamples);
        reverb.processBlock(block, midi);
        echo.processBlock(block, midi);
        octaveFuzz.processBlock(block, midi);
    }

    void resized() override { tabs.setBounds(getLocalBounds()); }

private:
    MechanaReverbAudioProcessor reverb;
    MechanaEchoAudioProcessor echo;
    MechanaOctaveFuzzAudioProcessor octaveFuzz;
    juce::TabbedComponent tabs;
};

class MainWindow final : public juce::DocumentWindow {
public:
    MainWindow()
        : DocumentWindow("Mechana Effects", juce::Colour(0xff171b1e), DocumentWindow::allButtons) {
        setUsingNativeTitleBar(true);
        setContentOwned(new EffectsComponent(), true);
        setResizable(true, true);
        centreWithSize(getWidth(), getHeight());
        setVisible(true);
    }

    void closeButtonPressed() override { juce::JUCEApplication::getInstance()->systemRequestedQuit(); }
};

class EffectsApplication final : public juce::JUCEApplication {
public:
    const juce::String getApplicationName() override { return "Mechana Effects"; }
    const juce::String getApplicationVersion() override { return "0.1.0"; }
    void initialise(const juce::String&) override { window = std::make_unique<MainWindow>(); }
    void shutdown() override { window.reset(); }

private:
    std::unique_ptr<MainWindow> window;
};
} // namespace

START_JUCE_APPLICATION(EffectsApplication)
