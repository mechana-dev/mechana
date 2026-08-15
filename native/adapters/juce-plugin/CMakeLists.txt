# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

include(FetchContent)
set(JUCE_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(JUCE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
FetchContent_Declare(JUCE
    GIT_REPOSITORY https://github.com/juce-framework/JUCE.git
    GIT_TAG 9.0.0
    GIT_SHALLOW TRUE
    SOURCE_DIR ${CMAKE_BINARY_DIR}/_deps/JUCE)
FetchContent_MakeAvailable(JUCE)

juce_add_binary_data(MechanaReverbFactoryIr
    NAMESPACE FactoryIrData
    SOURCES
    ${CMAKE_SOURCE_DIR}/../standalone-reverb-app/src/main/distribution/ir-profiles/large-room-short-ir.wav
    ${CMAKE_SOURCE_DIR}/../standalone-reverb-app/src/main/distribution/ir-profiles/large-stone-church-ir.wav
    ${CMAKE_SOURCE_DIR}/../standalone-reverb-app/src/main/distribution/ir-profiles/medium-room-ir.wav
    ${CMAKE_SOURCE_DIR}/../standalone-reverb-app/src/main/distribution/ir-profiles/scott-rvb-first-pass-ir.wav
    ${CMAKE_SOURCE_DIR}/../standalone-reverb-app/src/main/distribution/ir-profiles/small-room-ir.wav
    ${CMAKE_SOURCE_DIR}/../standalone-reverb-app/src/main/distribution/ir-profiles/vocal-plate-ir.wav)

juce_add_plugin(MechanaReverb
    VERSION 0.2.0
    COMPANY_NAME "Mechana"
    BUNDLE_ID "dev.mechana.reverb"
    PLUGIN_MANUFACTURER_CODE Mchn
    PLUGIN_CODE Rvrb
    FORMATS AU Standalone
    PRODUCT_NAME "Mechana Reverb"
    DESCRIPTION "Captured impulse-response convolution reverb"
    IS_SYNTH FALSE
    NEEDS_MIDI_INPUT FALSE
    NEEDS_MIDI_OUTPUT FALSE
    IS_MIDI_EFFECT FALSE
    EDITOR_WANTS_KEYBOARD_FOCUS FALSE
    COPY_PLUGIN_AFTER_BUILD FALSE)

target_sources(MechanaReverb PRIVATE
    Source/PluginEditor.cpp
    Source/PluginEditor.h
    Source/PluginProcessor.cpp
    Source/PluginProcessor.h)

target_compile_definitions(MechanaReverb PUBLIC
    JUCE_REPORT_APP_USAGE=0
    JUCE_USE_CURL=0
    JUCE_WEB_BROWSER=0)

target_link_libraries(MechanaReverb PRIVATE
    mechana_reverb_core
    MechanaReverbFactoryIr
    juce::juce_audio_formats
    juce::juce_audio_processors
    juce::juce_audio_utils
    juce::juce_gui_extra
    PUBLIC
    juce::juce_recommended_config_flags
    juce::juce_recommended_lto_flags
    juce::juce_recommended_warning_flags)

juce_generate_juce_header(MechanaReverb)
