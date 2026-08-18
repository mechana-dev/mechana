# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0

add_library(mechana_audio_core INTERFACE)
target_include_directories(mechana_audio_core INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_features(mechana_audio_core INTERFACE cxx_std_20)
