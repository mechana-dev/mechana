/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginProcessor.h"
#include "PluginEditor.h"

#include <mechana/leslie/Models.h>

namespace {
juce::AudioParameterFloatAttributes percentageAttributes() {
    return juce::AudioParameterFloatAttributes()
        .withLabel("%")
        .withStringFromValueFunction([](const float value, int) { return juce::String(value * 100.0F, 1); })
        .withValueFromStringFunction([](const juce::String& text) { return text.getFloatValue() / 100.0F; });
}
}

const juce::String MechanaLeslieAudioProcessor::getName() const { return "Mechana Leslie"; }

MechanaLeslieAudioProcessor::MechanaLeslieAudioProcessor()
    : AudioProcessor(BusesProperties().withInput("Input", juce::AudioChannelSet::stereo(), true)
                                       .withOutput("Output", juce::AudioChannelSet::stereo(), true)),
      parameters_(*this, nullptr, "MechanaLeslieState", createParameterLayout()) {}

juce::AudioProcessorValueTreeState::ParameterLayout MechanaLeslieAudioProcessor::createParameterLayout() {
    const auto defaults = mechana::leslie::classicCabinetDefaults();
    juce::AudioProcessorValueTreeState::ParameterLayout layout;
    layout.add(std::make_unique<juce::AudioParameterChoice>(juce::ParameterID { "speed", 1 }, "Rotor Speed",
                                                            juce::StringArray { "Stopped", "Slow", "Fast" }, 1));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "drive", 1 }, "Drive",
                                                            juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F },
                                                            defaults.drive, percentageAttributes()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "horn", 1 }, "Horn Balance",
                                                            juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F },
                                                            defaults.hornLevel, percentageAttributes()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "distance", 1 }, "Mic Distance",
                                                            juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F },
                                                            defaults.microphoneDistance, percentageAttributes()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "width", 1 }, "Stereo Width",
                                                            juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F },
                                                            defaults.stereoWidth, percentageAttributes()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "crossover", 1 }, "Crossover",
                                                            juce::NormalisableRange<float> { 400.0F, 1'600.0F, 1.0F },
                                                            defaults.crossoverHertz, "Hz"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "wet", 1 }, "Wet Level",
                                                            juce::NormalisableRange<float> { 0.0F, 1.5F, 0.001F },
                                                            defaults.wetLevel, percentageAttributes()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "dry", 1 }, "Dry Level",
                                                            juce::NormalisableRange<float> { 0.0F, 1.5F, 0.001F },
                                                            defaults.dryLevel, percentageAttributes()));
    layout.add(std::make_unique<juce::AudioParameterBool>(juce::ParameterID { "bypass", 1 }, "Bypass", false));
    return layout;
}

void MechanaLeslieAudioProcessor::prepareToPlay(const double sampleRate, const int samplesPerBlock) {
    engine_.prepare(sampleRate, static_cast<std::size_t>(getTotalNumOutputChannels()),
                    static_cast<std::size_t>(samplesPerBlock));
    setLatencySamples(0);
}

void MechanaLeslieAudioProcessor::releaseResources() { engine_.reset(); }

bool MechanaLeslieAudioProcessor::isBusesLayoutSupported(const BusesLayout& layouts) const {
    const auto output = layouts.getMainOutputChannelSet();
    return (output == juce::AudioChannelSet::mono() || output == juce::AudioChannelSet::stereo())
           && output == layouts.getMainInputChannelSet();
}

mechana::leslie::Parameters MechanaLeslieAudioProcessor::currentParameters() const noexcept {
    mechana::leslie::Parameters result;
    const auto speed = static_cast<int>(parameters_.getRawParameterValue("speed")->load());
    result.rotorMode = speed == 0 ? mechana::leslie::RotorMode::stopped
                                 : speed == 2 ? mechana::leslie::RotorMode::fast
                                              : mechana::leslie::RotorMode::slow;
    result.drive = parameters_.getRawParameterValue("drive")->load();
    result.hornLevel = parameters_.getRawParameterValue("horn")->load();
    result.microphoneDistance = parameters_.getRawParameterValue("distance")->load();
    result.stereoWidth = parameters_.getRawParameterValue("width")->load();
    result.crossoverHertz = parameters_.getRawParameterValue("crossover")->load();
    result.wetLevel = parameters_.getRawParameterValue("wet")->load();
    result.dryLevel = parameters_.getRawParameterValue("dry")->load();
    result.bypass = parameters_.getRawParameterValue("bypass")->load() >= 0.5F;
    return result;
}

void MechanaLeslieAudioProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer&) {
    juce::ScopedNoDenormals noDenormals;
    engine_.process(buffer.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                    static_cast<std::size_t>(buffer.getNumSamples()), currentParameters());
}

void MechanaLeslieAudioProcessor::resetToDefaults() {
    const auto defaults = mechana::leslie::classicCabinetDefaults();
    const std::array values { std::pair { "speed", 1.0F }, std::pair { "drive", defaults.drive },
                              std::pair { "horn", defaults.hornLevel },
                              std::pair { "distance", defaults.microphoneDistance },
                              std::pair { "width", defaults.stereoWidth },
                              std::pair { "crossover", defaults.crossoverHertz },
                              std::pair { "wet", defaults.wetLevel }, std::pair { "dry", defaults.dryLevel },
                              std::pair { "bypass", 0.0F } };
    for (const auto& [id, value] : values)
        if (auto* parameter = parameters_.getParameter(id))
            parameter->setValueNotifyingHost(parameter->convertTo0to1(value));
}

void MechanaLeslieAudioProcessor::getStateInformation(juce::MemoryBlock& destination) {
    if (auto xml = parameters_.copyState().createXml())
        copyXmlToBinary(*xml, destination);
}

void MechanaLeslieAudioProcessor::setStateInformation(const void* data, const int size) {
    if (auto xml = getXmlFromBinary(data, size); xml != nullptr && xml->hasTagName(parameters_.state.getType()))
        parameters_.replaceState(juce::ValueTree::fromXml(*xml));
}

juce::AudioProcessorEditor* MechanaLeslieAudioProcessor::createEditor() {
    return new MechanaLeslieAudioProcessorEditor(*this);
}

