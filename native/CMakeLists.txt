# Copyright (c) 2026 Mark Vita
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.24)
project(MechanaReverbNative VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(MECHANA_BUILD_JUCE_PLUGIN "Build the temporary JUCE AU adapter" ON)
option(MECHANA_BUILD_TESTS "Build the JUCE-free core tests" ON)
option(MECHANA_BUILD_BENCHMARKS "Build the self-contained native DSP benchmark" ON)

add_subdirectory(reverb-core)
add_subdirectory(echo-core)

if(MECHANA_BUILD_TESTS)
    enable_testing()
    add_subdirectory(reverb-core/tests)
    add_subdirectory(echo-core/tests)
endif()

if(MECHANA_BUILD_BENCHMARKS)
    add_subdirectory(reverb-core/benchmarks)
endif()

if(MECHANA_BUILD_JUCE_PLUGIN)
    add_subdirectory(adapters/juce-plugin)
    add_subdirectory(adapters/juce-echo-plugin)
    add_subdirectory(apps/mechana-effects)
endif()
