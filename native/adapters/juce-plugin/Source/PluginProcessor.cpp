/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginProcessor.h"

#include "PluginEditor.h"
#include <mechana/reverb/ImpulseResponsePreparation.h>

namespace {
constexpr std::array shapingParameters { "early", "late", "attack", "decay" };

struct FactoryProfile final {
    const char* name;
    const char* data;
    int size;
};

const std::array factoryProfiles {
    FactoryProfile { "Small Room", FactoryIrData::smallroomir_wav, FactoryIrData::smallroomir_wavSize },
    FactoryProfile { "Medium Room", FactoryIrData::mediumroomir_wav, FactoryIrData::mediumroomir_wavSize },
    FactoryProfile { "Large Room (Short)", FactoryIrData::largeroomshortir_wav, FactoryIrData::largeroomshortir_wavSize },
    FactoryProfile { "Large Stone Church", FactoryIrData::largestonechurchir_wav, FactoryIrData::largestonechurchir_wavSize },
    FactoryProfile { "Vocal Plate", FactoryIrData::vocalplateir_wav, FactoryIrData::vocalplateir_wavSize },
    FactoryProfile { "Scott RVB First Pass", FactoryIrData::scottrvbfirstpassir_wav, FactoryIrData::scottrvbfirstpassir_wavSize }
};
} // namespace

MechanaReverbAudioProcessor::MechanaReverbAudioProcessor()
    : AudioProcessor(BusesProperties().withInput("Input", juce::AudioChannelSet::stereo(), true)
                                       .withOutput("Output", juce::AudioChannelSet::stereo(), true)),
      parameters_(*this, nullptr, "MechanaReverbState", createParameterLayout()) {
    for (const auto* parameter : shapingParameters)
        parameters_.addParameterListener(parameter, this);
    parameters_.state.setProperty("profileIndex", 0, nullptr);
    parameters_.state.setProperty("profileName", factoryProfiles.front().name, nullptr);
}

MechanaReverbAudioProcessor::~MechanaReverbAudioProcessor() {
    cancelPendingUpdate();
    for (const auto* parameter : shapingParameters)
        parameters_.removeParameterListener(parameter, this);
}

juce::AudioProcessorValueTreeState::ParameterLayout MechanaReverbAudioProcessor::createParameterLayout() {
    juce::AudioProcessorValueTreeState::ParameterLayout layout;
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "wet", 1 }, "Wet Level", juce::NormalisableRange<float> { 0.0F, 2.0F, 0.001F }, 0.35F));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "dry", 1 }, "Dry Level", juce::NormalisableRange<float> { 0.0F, 2.0F, 0.001F }, 1.0F));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "predelay", 1 }, "Pre-Delay",
        juce::NormalisableRange<float> { 0.0F, 200.0F, 0.1F }, 20.0F, "ms"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "early", 1 }, "Early Level", juce::NormalisableRange<float> { 0.0F, 2.0F, 0.001F }, 1.0F));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "late", 1 }, "Late Level", juce::NormalisableRange<float> { 0.0F, 2.0F, 0.001F }, 1.0F));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "attack", 1 }, "Attack",
        juce::NormalisableRange<float> { 0.0F, 5000.0F, 1.0F, 0.35F }, 0.0F, "ms"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "decay", 1 }, "Decay Length",
        juce::NormalisableRange<float> { 1.0F, 100.0F, 0.1F }, 100.0F, "%"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "lowcut", 1 }, "Wet Low-Cut",
        juce::NormalisableRange<float> { 0.0F, 2000.0F, 1.0F, 0.35F }, 0.0F, "Hz"));
    layout.add(std::make_unique<juce::AudioParameterFloat>(
        juce::ParameterID { "highcut", 1 }, "Wet High-Cut",
        juce::NormalisableRange<float> { 0.0F, 20000.0F, 1.0F, 0.35F }, 0.0F, "Hz"));
    layout.add(std::make_unique<juce::AudioParameterBool>(juce::ParameterID { "bypass", 1 }, "Bypass", false));
    return layout;
}

void MechanaReverbAudioProcessor::prepareToPlay(const double sampleRate, const int samplesPerBlock) {
    processingSampleRate_ = sampleRate;
    engine_.prepare(sampleRate, static_cast<std::size_t>(getTotalNumOutputChannels()),
                    static_cast<std::size_t>(samplesPerBlock));
    if (sourceImpulseResponse_.empty())
        loadFactoryImpulseResponse();
    else
        rebuildPreparedResponse();
    setLatencySamples(static_cast<int>(engine_.latencySamples()));
}

void MechanaReverbAudioProcessor::releaseResources() { engine_.reset(); }

bool MechanaReverbAudioProcessor::isBusesLayoutSupported(const BusesLayout& layouts) const {
    const auto output = layouts.getMainOutputChannelSet();
    return (output == juce::AudioChannelSet::mono() || output == juce::AudioChannelSet::stereo())
           && output == layouts.getMainInputChannelSet();
}

void MechanaReverbAudioProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer&) {
    juce::ScopedNoDenormals noDenormals;
    mechana::reverb::Parameters values;
    values.wetLevel = parameters_.getRawParameterValue("wet")->load();
    values.dryLevel = parameters_.getRawParameterValue("dry")->load();
    values.preDelayMilliseconds = parameters_.getRawParameterValue("predelay")->load();
    values.wetLowCutHertz = parameters_.getRawParameterValue("lowcut")->load();
    values.wetHighCutHertz = parameters_.getRawParameterValue("highcut")->load();
    values.bypass = parameters_.getRawParameterValue("bypass")->load() >= 0.5F;
    engine_.process(buffer.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                    static_cast<std::size_t>(buffer.getNumSamples()), values);
}

void MechanaReverbAudioProcessor::loadFactoryImpulseResponse() {
    const auto customPath = parameters_.state.getProperty("profilePath").toString();
    if (customPath.isNotEmpty()) {
        if (importImpulseResponse(juce::File(customPath)))
            return;
        parameters_.state.removeProperty("profilePath", nullptr);
    }
    selectFactoryProfile(static_cast<int>(parameters_.state.getProperty("profileIndex", 0)));
}

juce::StringArray MechanaReverbAudioProcessor::factoryProfileNames() const {
    juce::StringArray names;
    for (const auto& profile : factoryProfiles)
        names.add(profile.name);
    return names;
}

juce::String MechanaReverbAudioProcessor::currentProfileName() const {
    return parameters_.state.getProperty("profileName", factoryProfiles.front().name).toString();
}

bool MechanaReverbAudioProcessor::selectFactoryProfile(const int index) {
    if (!juce::isPositiveAndBelow(index, static_cast<int>(factoryProfiles.size())))
        return false;
    const auto& profile = factoryProfiles[static_cast<std::size_t>(index)];
    juce::AudioFormatManager formats;
    formats.registerBasicFormats();
    auto stream = std::make_unique<juce::MemoryInputStream>(profile.data, profile.size, false);
    auto reader = std::unique_ptr<juce::AudioFormatReader>(formats.createReaderFor(std::move(stream)));
    if (!loadReader(std::move(reader), profile.name, {}))
        return false;
    parameters_.state.setProperty("profileIndex", index, nullptr);
    parameters_.state.removeProperty("profilePath", nullptr);
    return true;
}

bool MechanaReverbAudioProcessor::importImpulseResponse(const juce::File& file) {
    if (!file.existsAsFile())
        return false;
    juce::AudioFormatManager formats;
    formats.registerBasicFormats();
    auto stream = file.createInputStream();
    if (stream == nullptr)
        return false;
    auto reader = std::unique_ptr<juce::AudioFormatReader>(formats.createReaderFor(std::move(stream)));
    return loadReader(std::move(reader), file.getFileNameWithoutExtension(), file.getFullPathName());
}

bool MechanaReverbAudioProcessor::loadReader(std::unique_ptr<juce::AudioFormatReader> reader,
                                             const juce::String& profileName, const juce::String& sourcePath) {
    if (reader == nullptr)
        return false;
    const auto maximumFrames = static_cast<juce::int64>(std::llround(30.0 * reader->sampleRate));
    const auto frames = static_cast<int>(std::min(reader->lengthInSamples, maximumFrames));
    juce::AudioBuffer<float> data(static_cast<int>(reader->numChannels), frames);
    if (!reader->read(&data, 0, frames, 0, true, true))
        return false;
    sourceImpulseResponse_.assign(static_cast<std::size_t>(data.getNumChannels()), {});
    for (int channel = 0; channel < data.getNumChannels(); ++channel)
        sourceImpulseResponse_[static_cast<std::size_t>(channel)].assign(data.getReadPointer(channel),
                                                                         data.getReadPointer(channel) + frames);
    sourceSampleRate_ = reader->sampleRate;
    tailSeconds_ = static_cast<double>(frames) / reader->sampleRate;
    parameters_.state.setProperty("profileName", profileName, nullptr);
    if (sourcePath.isNotEmpty())
        parameters_.state.setProperty("profilePath", sourcePath, nullptr);
    rebuildPreparedResponse();
    return true;
}

void MechanaReverbAudioProcessor::rebuildPreparedResponse() {
    if (sourceImpulseResponse_.empty())
        return;
    mechana::reverb::ImpulseResponseParameters shaping;
    shaping.earlyLevel = parameters_.getRawParameterValue("early")->load();
    shaping.lateLevel = parameters_.getRawParameterValue("late")->load();
    shaping.attackMilliseconds = parameters_.getRawParameterValue("attack")->load();
    shaping.decayLengthPercent = parameters_.getRawParameterValue("decay")->load();
    const auto prepared = mechana::reverb::prepareImpulseResponse(sourceImpulseResponse_, sourceSampleRate_,
                                                                   processingSampleRate_, shaping);
    suspendProcessing(true);
    engine_.setImpulseResponse(prepared);
    suspendProcessing(false);
    tailSeconds_ = static_cast<double>(prepared.front().size()) / processingSampleRate_;
}

void MechanaReverbAudioProcessor::parameterChanged(const juce::String&, float) { triggerAsyncUpdate(); }

void MechanaReverbAudioProcessor::handleAsyncUpdate() { rebuildPreparedResponse(); }

void MechanaReverbAudioProcessor::getStateInformation(juce::MemoryBlock& destination) {
    if (auto xml = parameters_.copyState().createXml())
        copyXmlToBinary(*xml, destination);
}

void MechanaReverbAudioProcessor::setStateInformation(const void* data, const int size) {
    if (auto xml = getXmlFromBinary(data, size); xml != nullptr)
        if (xml->hasTagName(parameters_.state.getType()))
            parameters_.replaceState(juce::ValueTree::fromXml(*xml));
    triggerAsyncUpdate();
}

juce::AudioProcessorEditor* MechanaReverbAudioProcessor::createEditor() {
    return new MechanaReverbAudioProcessorEditor(*this);
}

juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter() { return new MechanaReverbAudioProcessor(); }
