# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_executable(mechana_reverb_benchmark ReverbBenchmark.cpp)
target_link_libraries(mechana_reverb_benchmark PRIVATE mechana_reverb_core)
target_compile_options(mechana_reverb_benchmark PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>)
