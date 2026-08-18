# Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0.
add_executable(mechana_octave_fuzz_benchmark OctaveFuzzBenchmark.cpp)
target_link_libraries(mechana_octave_fuzz_benchmark PRIVATE mechana_octave_fuzz_core)
target_compile_options(mechana_octave_fuzz_benchmark PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>)
