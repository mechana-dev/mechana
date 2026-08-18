# Copyright (c) 2026 Mark Vita. Licensed under Apache-2.0.
add_executable(mechana_octave_fuzz_core_tests OctaveFuzzEngineTest.cpp)
target_link_libraries(mechana_octave_fuzz_core_tests PRIVATE mechana_octave_fuzz_core)
target_compile_options(mechana_octave_fuzz_core_tests PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>)
add_test(NAME mechana_octave_fuzz_core_tests COMMAND mechana_octave_fuzz_core_tests)
