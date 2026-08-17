/*
 * Copyright (c) 2026 Mark Vita
 * Licensed under the Apache License, Version 2.0. See the repository LICENSE.
 */
#include "PluginProcessor.h"

#include "PluginEditor.h"
#include <BinaryData.h>
#include <mechana/reverb/ImpulseResponsePreparation.h>

#include <bit>
#include <sstream>

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

const juce::String MechanaReverbAudioProcessor::getName() const { return "Mechana Reverb"; }

MechanaReverbAudioProcessor::MechanaReverbAudioProcessor()
    : AudioProcessor(BusesProperties().withInput("Input", juce::AudioChannelSet::stereo(), true)
                                       .withOutput("Output", juce::AudioChannelSet::stereo(), true)),
      parameters_(*this, nullptr, "MechanaReverbState", createParameterLayout()) {
    for (const auto* parameter : shapingParameters)
        parameters_.addParameterListener(parameter, this);
    parameters_.state.setProperty("profileIndex", 0, nullptr);
    parameters_.state.setProperty("profileName", factoryProfiles.front().name, nullptr);
    preparationThread_ = std::jthread([this](const std::stop_token token) { preparationLoop(token); });
}

MechanaReverbAudioProcessor::~MechanaReverbAudioProcessor() {
    cancelPendingUpdate();
    preparationThread_.request_stop();
    preparationCondition_.notify_all();
    if (preparationThread_.joinable())
        preparationThread_.join();
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
    channelCount_ = static_cast<std::size_t>(getTotalNumOutputChannels());
    maximumBlockSize_ = static_cast<std::size_t>(samplesPerBlock);
    newRenderBuffer_.setSize(getTotalNumOutputChannels(), samplesPerBlock, false, false, true);
    oldRenderBuffer_.setSize(getTotalNumOutputChannels(), samplesPerBlock, false, false, true);
    fadeLength_ = std::max<std::size_t>(1, static_cast<std::size_t>(sampleRate * 0.020));
    auto fallback = std::make_shared<mechana::reverb::ReverbEngine>();
    fallback->prepare(sampleRate, channelCount_, maximumBlockSize_);
    {
        const std::lock_guard lock(preparationMutex_);
        engineLifetime_.push_back(fallback);
    }
    std::atomic_store(&preparedEngine_, fallback);
    renderingEngine_ = fallback;
    if (sourceImpulseResponse_.empty())
        loadFactoryImpulseResponse();
    else
        requestPreparedResponse();
    setLatencySamples(static_cast<int>(mechana::reverb::ReverbEngine::partitionSize));
}

void MechanaReverbAudioProcessor::releaseResources() {
    if (renderingEngine_ != nullptr)
        renderingEngine_->reset();
}

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
    const auto candidate = std::atomic_load(&preparedEngine_);
    if (candidate != nullptr && candidate != renderingEngine_) {
        fadingEngine_ = renderingEngine_;
        renderingEngine_ = candidate;
        fadeRemaining_ = fadeLength_;
    }
    if (renderingEngine_ == nullptr)
        return;
    if (fadingEngine_ == nullptr || fadeRemaining_ == 0) {
        renderingEngine_->process(buffer.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                                  static_cast<std::size_t>(buffer.getNumSamples()), values);
        return;
    }
    newRenderBuffer_.makeCopyOf(buffer, true);
    oldRenderBuffer_.makeCopyOf(buffer, true);
    renderingEngine_->process(newRenderBuffer_.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                              static_cast<std::size_t>(buffer.getNumSamples()), values);
    fadingEngine_->process(oldRenderBuffer_.getArrayOfWritePointers(), static_cast<std::size_t>(buffer.getNumChannels()),
                           static_cast<std::size_t>(buffer.getNumSamples()), values);
    for (int frame = 0; frame < buffer.getNumSamples(); ++frame) {
        const auto newGain = 1.0F - static_cast<float>(fadeRemaining_) / static_cast<float>(fadeLength_);
        for (int channel = 0; channel < buffer.getNumChannels(); ++channel)
            buffer.setSample(channel, frame, oldRenderBuffer_.getSample(channel, frame) * (1.0F - newGain)
                                                 + newRenderBuffer_.getSample(channel, frame) * newGain);
        if (fadeRemaining_ > 0)
            --fadeRemaining_;
    }
    if (fadeRemaining_ == 0)
        fadingEngine_.reset();
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
    tailSeconds_.store(static_cast<double>(frames) / reader->sampleRate);
    parameters_.state.setProperty("profileName", profileName, nullptr);
    if (sourcePath.isNotEmpty())
        parameters_.state.setProperty("profilePath", sourcePath, nullptr);
    requestPreparedResponse();
    return true;
}

void MechanaReverbAudioProcessor::requestPreparedResponse() {
    if (sourceImpulseResponse_.empty())
        return;
    mechana::reverb::ImpulseResponseParameters shaping;
    shaping.earlyLevel = parameters_.getRawParameterValue("early")->load();
    shaping.lateLevel = parameters_.getRawParameterValue("late")->load();
    shaping.attackMilliseconds = parameters_.getRawParameterValue("attack")->load();
    shaping.decayLengthPercent = parameters_.getRawParameterValue("decay")->load();
    std::ostringstream key;
    key << currentProfileName() << ':' << sourceImpulseResponse_.front().size() << ':' << sourceSampleRate_ << ':'
        << processingSampleRate_ << ':' << channelCount_ << ':' << shaping.earlyLevel << ':' << shaping.lateLevel << ':'
        << shaping.attackMilliseconds << ':' << shaping.decayLengthPercent;
    std::uint64_t contentHash = 1'469'598'103'934'665'603ULL;
    for (const auto& channel : sourceImpulseResponse_)
        for (const auto sample : channel) {
            const auto bits = std::bit_cast<std::uint32_t>(sample);
            contentHash ^= bits;
            contentHash *= 1'099'511'628'211ULL;
        }
    key << ':' << contentHash;
    PreparationRequest request { key.str(), sourceImpulseResponse_, sourceSampleRate_, processingSampleRate_,
                                 channelCount_, maximumBlockSize_, shaping };
    {
        const std::lock_guard lock(preparationMutex_);
        pendingPreparation_ = std::move(request);
    }
    preparationCondition_.notify_one();
}

void MechanaReverbAudioProcessor::parameterChanged(const juce::String&, float) { triggerAsyncUpdate(); }

void MechanaReverbAudioProcessor::handleAsyncUpdate() { requestPreparedResponse(); }

void MechanaReverbAudioProcessor::preparationLoop(const std::stop_token stopToken) {
    while (!stopToken.stop_requested()) {
        std::optional<PreparationRequest> request;
        {
            std::unique_lock lock(preparationMutex_);
            preparationCondition_.wait(lock, stopToken, [this] { return pendingPreparation_.has_value(); });
            if (stopToken.stop_requested())
                return;
            request = std::move(pendingPreparation_);
            pendingPreparation_.reset();
        }
        if (!request.has_value())
            continue;
        std::vector<std::vector<float>> prepared;
        {
            const std::lock_guard lock(preparationMutex_);
            if (const auto found = preparedCache_.find(request->key); found != preparedCache_.end())
                prepared = found->second;
        }
        if (prepared.empty()) {
            prepared = mechana::reverb::prepareImpulseResponse(
                request->source, request->sourceSampleRate, request->processingSampleRate, request->shaping);
            if (prepared.empty())
                continue;
            const std::lock_guard lock(preparationMutex_);
            if (preparedCache_.size() >= 24)
                preparedCache_.erase(preparedCache_.begin());
            preparedCache_[request->key] = prepared;
        }
        auto engine = std::make_shared<mechana::reverb::ReverbEngine>();
        engine->prepare(request->processingSampleRate, request->channelCount, request->maximumBlockSize);
        engine->setImpulseResponse(prepared);
        tailSeconds_.store(static_cast<double>(prepared.front().size()) / request->processingSampleRate);
        {
            const std::lock_guard lock(preparationMutex_);
            engineLifetime_.push_back(engine);
        }
        std::atomic_store(&preparedEngine_, std::move(engine));
    }
}

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
