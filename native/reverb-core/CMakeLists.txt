# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_library(mechana_reverb_core STATIC
    src/FastFourierTransform.cpp
    src/ImpulseResponsePreparation.cpp
    src/PartitionedConvolver.cpp
    src/ReverbEngine.cpp)

target_include_directories(mechana_reverb_core PUBLIC include)
target_compile_features(mechana_reverb_core PUBLIC cxx_std_20)
target_compile_options(mechana_reverb_core PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>)

set_target_properties(mechana_reverb_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON)
