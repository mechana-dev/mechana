/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginProcessor.h"

#include <mechana/echo/Models.h>

#include <cmath>

namespace {
constexpr std::array modelNames { "Echoplex-style Tape", "Deluxe Memory Man-style Analog" };
}

MechanaEchoAudioProcessor::MechanaEchoAudioProcessor()
    : AudioProcessor(BusesProperties().withInput("Input", juce::AudioChannelSet::stereo(), true)
                                       .withOutput("Output", juce::AudioChannelSet::stereo(), true)),
      parameters_(*this, nullptr, "MechanaEchoState", createParameterLayout()) {
    parameters_.addParameterListener("model", this);
}

MechanaEchoAudioProcessor::~MechanaEchoAudioProcessor() {
    cancelPendingUpdate();
    parameters_.removeParameterListener("model", this);
}

juce::AudioProcessorValueTreeState::ParameterLayout MechanaEchoAudioProcessor::createParameterLayout() {
    const auto tape = mechana::echo::modelDefaults(mechana::echo::Model::vintageTape);
    juce::AudioProcessorValueTreeState::ParameterLayout layout;
    layout.add(std::make_unique<juce::AudioParameterChoice>(juce::ParameterID { "model", 1 }, "Model",
                                                            juce::StringArray { modelNames.data(), 2 }, 0));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "delay", 1 }, "Delay",
                                                            juce::NormalisableRange<float> { 30.0F, 750.0F, 0.1F },
                                                            tape.delayMilliseconds, "ms"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "feedback", 1 }, "Feedback",
                                                            juce::NormalisableRange<float> { 0.0F, 0.98F, 0.001F },
                                                            tape.feedback));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "wet", 1 }, "Wet Level",
                                                            juce::NormalisableRange<float> { 0.0F, 1.5F, 0.001F },
                                                            tape.wetLevel));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "dry", 1 }, "Dry Level",
                                                            juce::NormalisableRange<float> { 0.0F, 1.5F, 0.001F },
                                                            tape.dryLevel));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "lowcut", 1 }, "Repeat Low-Cut",
                                                            juce::NormalisableRange<float> { 0.0F, 1000.0F, 1.0F },
                                                            tape.feedbackLowCutHertz, "Hz"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "highcut", 1 }, "Repeat High-Cut",
                                                            juce::NormalisableRange<float> { 1000.0F, 12000.0F, 1.0F },
                                                            tape.feedbackHighCutHertz, "Hz"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "saturation", 1 }, "Age / Drive",
                                                            juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F },
                                                            tape.saturation));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "rate", 1 }, "Modulation Rate",
                                                            juce::NormalisableRange<float> { 0.05F, 10.0F, 0.01F, 0.4F },
                                                            tape.modulationRateHertz, "Hz"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "depth", 1 }, "Modulation Depth",
                                                            juce::NormalisableRange<float> { 0.0F, 12.0F, 0.01F, 0.5F },
                                                            tape.modulationDepthMilliseconds, "ms"));
    layout.add(std::make_unique<juce::AudioParameterBool>(juce::ParameterID { "pingpong", 1 }, "Ping-Pong", false));
    layout.add(std::make_unique<juce::AudioParameterBool>(juce::ParameterID { "bypass", 1 }, "Bypass", false));
    return layout;
}

void MechanaEchoAudioProcessor::prepareToPlay(const double sampleRate, const int samplesPerBlock) {
    engine_.prepare(sampleRate, static_cast<std::size_t>(getTotalNumOutputChannels()),
                    static_cast<std::size_t>(samplesPerBlock));
    setLatencySamples(0);
}

void MechanaEchoAudioProcessor::releaseResources() { engine_.reset(); }

bool MechanaEchoAudioProcessor::isBusesLayoutSupported(const BusesLayout& layouts) const {
    const auto output = layouts.getMainOutputChannelSet();
    return (output == juce::AudioChannelSet::mono() || output == juce::AudioChannelSet::stereo())
           && output == layouts.getMainInputChannelSet();
}

mechana::echo::Parameters MechanaEchoAudioProcessor::currentParameters() const noexcept {
    mechana::echo::Parameters result;
    const auto model = static_cast<int>(parameters_.getRawParameterValue("model")->load());
    result.character = model == 0 ? mechana::echo::Character::vintageTape : mechana::echo::Character::analogMemory;
    result.delayMilliseconds = parameters_.getRawParameterValue("delay")->load();
    result.feedback = parameters_.getRawParameterValue("feedback")->load();
    result.wetLevel = parameters_.getRawParameterValue("wet")->load();
    result.dryLevel = parameters_.getRawParameterValue("dry")->load();
    result.feedbackLowCutHertz = parameters_.getRawParameterValue("lowcut")->load();
    result.feedbackHighCutHertz = parameters_.getRawParameterValue("highcut")->load();
    result.saturation = parameters_.getRawParameterValue("saturation")->load();
    result.modulationRateHertz = parameters_.getRawParameterValue("rate")->load();
    result.modulationDepthMilliseconds = parameters_.getRawParameterValue("depth")->load();
    result.pingPong = parameters_.getRawParameterValue("pingpong")->load() >= 0.5F;
    result.bypass = parameters_.getRawParameterValue("bypass")->load() >= 0.5F;
    return result;
}

void MechanaEchoAudioProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer&) {
    juce::ScopedNoDenormals noDenormals;
    engine_.process(buffer.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                    static_cast<std::size_t>(buffer.getNumSamples()), currentParameters());
}

double MechanaEchoAudioProcessor::getTailLengthSeconds() const {
    const auto values = currentParameters();
    if (values.feedback <= 0.001F)
        return values.delayMilliseconds / 1000.0;
    return std::min(30.0, values.delayMilliseconds / 1000.0 * std::log(0.001) / std::log(values.feedback));
}

int MechanaEchoAudioProcessor::getCurrentProgram() { return static_cast<int>(parameters_.getRawParameterValue("model")->load()); }
void MechanaEchoAudioProcessor::setCurrentProgram(const int index) { applyModelDefaults(index); }
const juce::String MechanaEchoAudioProcessor::getProgramName(const int index) {
    return juce::isPositiveAndBelow(index, 2) ? modelNames[static_cast<std::size_t>(index)] : "";
}

void MechanaEchoAudioProcessor::parameterChanged(const juce::String& id, float) {
    if (id == "model" && !applyingModel_)
        triggerAsyncUpdate();
}
void MechanaEchoAudioProcessor::handleAsyncUpdate() { applyModelDefaults(getCurrentProgram()); }

void MechanaEchoAudioProcessor::applyModelDefaults(const int model) {
    const auto selected = model == 0 ? mechana::echo::Model::vintageTape : mechana::echo::Model::analogMemory;
    const auto defaults = mechana::echo::modelDefaults(selected);
    applyingModel_ = true;
    const std::array values { std::pair { "model", static_cast<float>(model) },
                              std::pair { "delay", defaults.delayMilliseconds },
                              std::pair { "feedback", defaults.feedback }, std::pair { "wet", defaults.wetLevel },
                              std::pair { "dry", defaults.dryLevel },
                              std::pair { "lowcut", defaults.feedbackLowCutHertz },
                              std::pair { "highcut", defaults.feedbackHighCutHertz },
                              std::pair { "saturation", defaults.saturation },
                              std::pair { "rate", defaults.modulationRateHertz },
                              std::pair { "depth", defaults.modulationDepthMilliseconds } };
    for (const auto& [id, value] : values)
        if (auto* parameter = parameters_.getParameter(id))
            parameter->setValueNotifyingHost(parameter->convertTo0to1(value));
    applyingModel_ = false;
}

void MechanaEchoAudioProcessor::getStateInformation(juce::MemoryBlock& destination) {
    if (auto xml = parameters_.copyState().createXml())
        copyXmlToBinary(*xml, destination);
}
void MechanaEchoAudioProcessor::setStateInformation(const void* data, const int size) {
    if (auto xml = getXmlFromBinary(data, size); xml != nullptr && xml->hasTagName(parameters_.state.getType()))
        parameters_.replaceState(juce::ValueTree::fromXml(*xml));
}

juce::AudioProcessorEditor* MechanaEchoAudioProcessor::createEditor() {
    return new juce::GenericAudioProcessorEditor(*this);
}

juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter() { return new MechanaEchoAudioProcessor(); }
