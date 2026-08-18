# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0

add_library(mechana_octave_fuzz_core
    src/OctaveFuzzEngine.cpp
)
target_include_directories(mechana_octave_fuzz_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(mechana_octave_fuzz_core PUBLIC mechana_audio_core)
target_compile_features(mechana_octave_fuzz_core PUBLIC cxx_std_20)
