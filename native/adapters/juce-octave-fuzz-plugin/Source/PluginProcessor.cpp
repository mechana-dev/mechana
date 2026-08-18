/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include "PluginProcessor.h"
#include "PluginEditor.h"

namespace {
juce::AudioParameterFloatAttributes percent() {
    return juce::AudioParameterFloatAttributes().withLabel("%").withStringFromValueFunction(
        [](float value, int) { return juce::String(value * 100.0F, 1); }).withValueFromStringFunction(
        [](const juce::String& text) { return text.getFloatValue() / 100.0F; });
}
}
MechanaOctaveFuzzAudioProcessor::MechanaOctaveFuzzAudioProcessor()
    : AudioProcessor(BusesProperties().withInput("Input", juce::AudioChannelSet::stereo(), true)
                                      .withOutput("Output", juce::AudioChannelSet::stereo(), true)),
      parameters_(*this, nullptr, "MechanaOctaveFuzzState", createParameterLayout()) {}

juce::AudioProcessorValueTreeState::ParameterLayout MechanaOctaveFuzzAudioProcessor::createParameterLayout() {
    juce::AudioProcessorValueTreeState::ParameterLayout layout;
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "drive", 1 }, "Drive / Fuzz",
        juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F }, 0.65F, percent()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "tone", 1 }, "Tone",
        juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F }, 0.5F, percent()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "level", 1 }, "Output / Level",
        juce::NormalisableRange<float> { 0.0F, 1.5F, 0.001F }, 0.8F, percent()));
    layout.add(std::make_unique<juce::AudioParameterFloat>(juce::ParameterID { "octave", 1 }, "Octave Blend",
        juce::NormalisableRange<float> { 0.0F, 1.0F, 0.001F }, 0.65F, percent()));
    layout.add(std::make_unique<juce::AudioParameterBool>(juce::ParameterID { "bypass", 1 }, "Bypass", false));
    return layout;
}
void MechanaOctaveFuzzAudioProcessor::prepareToPlay(double sampleRate, int) {
    engine_.prepare(sampleRate, static_cast<std::size_t>(getTotalNumOutputChannels()));
    setLatencySamples(static_cast<int>(engine_.latencySamples()));
}
void MechanaOctaveFuzzAudioProcessor::releaseResources() { engine_.reset(); }
bool MechanaOctaveFuzzAudioProcessor::isBusesLayoutSupported(const BusesLayout& layout) const {
    const auto output = layout.getMainOutputChannelSet();
    return (output == juce::AudioChannelSet::mono() || output == juce::AudioChannelSet::stereo())
        && output == layout.getMainInputChannelSet();
}
mechana::fuzz::Parameters MechanaOctaveFuzzAudioProcessor::currentParameters() const noexcept {
    mechana::fuzz::Parameters result;
    result.drive = parameters_.getRawParameterValue("drive")->load();
    result.tone = parameters_.getRawParameterValue("tone")->load();
    result.level = parameters_.getRawParameterValue("level")->load();
    result.octave = parameters_.getRawParameterValue("octave")->load();
    result.bypass = parameters_.getRawParameterValue("bypass")->load() >= 0.5F;
    return result;
}
void MechanaOctaveFuzzAudioProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer&) {
    juce::ScopedNoDenormals noDenormals;
    engine_.setParameters(currentParameters());
    engine_.process(buffer.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                    static_cast<std::size_t>(buffer.getNumSamples()));
}
void MechanaOctaveFuzzAudioProcessor::resetToDefaults() {
    const std::array defaults { std::pair { "drive", 0.65F }, std::pair { "tone", 0.5F },
        std::pair { "level", 0.8F }, std::pair { "octave", 0.65F }, std::pair { "bypass", 0.0F } };
    for (const auto& [id, value] : defaults)
        if (auto* parameter = parameters_.getParameter(id))
            parameter->setValueNotifyingHost(parameter->convertTo0to1(value));
}
void MechanaOctaveFuzzAudioProcessor::getStateInformation(juce::MemoryBlock& data) {
    if (auto xml = parameters_.copyState().createXml()) copyXmlToBinary(*xml, data);
}
void MechanaOctaveFuzzAudioProcessor::setStateInformation(const void* data, int size) {
    if (auto xml = getXmlFromBinary(data, size); xml != nullptr && xml->hasTagName(parameters_.state.getType()))
        parameters_.replaceState(juce::ValueTree::fromXml(*xml));
}
juce::AudioProcessorEditor* MechanaOctaveFuzzAudioProcessor::createEditor() {
    return new MechanaOctaveFuzzAudioProcessorEditor(*this);
}
