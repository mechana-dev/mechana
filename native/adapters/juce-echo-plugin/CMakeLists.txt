# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

juce_add_plugin(MechanaEcho
    VERSION 0.1.0
    COMPANY_NAME "Mechana"
    BUNDLE_ID "dev.mechana.echo"
    PLUGIN_MANUFACTURER_CODE Mchn
    PLUGIN_CODE Echo
    FORMATS AU Standalone
    PRODUCT_NAME "Mechana Echo"
    DESCRIPTION "Modeled vintage tape and analog-memory echo"
    IS_SYNTH FALSE
    NEEDS_MIDI_INPUT FALSE
    NEEDS_MIDI_OUTPUT FALSE
    IS_MIDI_EFFECT FALSE
    EDITOR_WANTS_KEYBOARD_FOCUS FALSE
    COPY_PLUGIN_AFTER_BUILD FALSE)

target_sources(MechanaEcho PRIVATE Source/PluginProcessor.cpp Source/PluginProcessor.h)
target_compile_definitions(MechanaEcho PUBLIC JUCE_REPORT_APP_USAGE=0 JUCE_USE_CURL=0 JUCE_WEB_BROWSER=0)
target_link_libraries(MechanaEcho PRIVATE
    mechana_echo_core
    juce::juce_audio_processors
    juce::juce_audio_utils
    PUBLIC
    juce::juce_recommended_config_flags
    juce::juce_recommended_lto_flags
    juce::juce_recommended_warning_flags)
juce_generate_juce_header(MechanaEcho)
