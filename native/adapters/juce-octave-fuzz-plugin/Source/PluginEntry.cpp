/* Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0. */
#include "PluginProcessor.h"
juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter() { return new MechanaOctaveFuzzAudioProcessor(); }
