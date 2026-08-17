# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

juce_add_gui_app(MechanaEffects
    VERSION 0.1.0
    COMPANY_NAME "Mechana"
    BUNDLE_ID "dev.mechana.effects"
    PRODUCT_NAME "Mechana Effects")

target_sources(MechanaEffects PRIVATE Source/Main.cpp)
target_include_directories(MechanaEffects PRIVATE
    ${CMAKE_SOURCE_DIR}/adapters/juce-plugin/Source
    ${CMAKE_SOURCE_DIR}/adapters/juce-echo-plugin/Source)
target_compile_definitions(MechanaEffects PRIVATE JUCE_REPORT_APP_USAGE=0 JUCE_USE_CURL=0 JUCE_WEB_BROWSER=0)
target_link_libraries(MechanaEffects PRIVATE
    MechanaReverb
    MechanaEcho
    mechana_reverb_core
    mechana_echo_core
    juce::juce_audio_utils
    juce::juce_gui_extra
    PUBLIC
    juce::juce_recommended_config_flags
    juce::juce_recommended_lto_flags
    juce::juce_recommended_warning_flags)
juce_generate_juce_header(MechanaEffects)
