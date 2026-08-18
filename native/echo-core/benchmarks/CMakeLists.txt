# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_executable(mechana_echo_benchmark EchoBenchmark.cpp)
target_link_libraries(mechana_echo_benchmark PRIVATE mechana_echo_core)
add_executable(mechana_echo_calibration_render EchoCalibrationRender.cpp)
target_link_libraries(mechana_echo_calibration_render PRIVATE mechana_echo_core)
target_compile_options(mechana_echo_benchmark PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>)
