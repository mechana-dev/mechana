# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_library(mechana_leslie_core STATIC src/LeslieEngine.cpp)
target_include_directories(mechana_leslie_core PUBLIC include)
target_compile_features(mechana_leslie_core PUBLIC cxx_std_20)
