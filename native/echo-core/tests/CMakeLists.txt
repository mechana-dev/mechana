# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_executable(mechana_echo_core_tests EchoEngineTest.cpp)
target_link_libraries(mechana_echo_core_tests PRIVATE mechana_echo_core)
target_compile_options(mechana_echo_core_tests PRIVATE
    $<$<CXX_COMPILER_ID:AppleClang,Clang,GNU>:-Wall;-Wextra;-Wpedantic;-Werror>)
add_test(NAME mechana_echo_core_tests COMMAND mechana_echo_core_tests)
