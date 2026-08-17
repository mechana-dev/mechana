# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_executable(mechana_leslie_core_tests LeslieEngineTest.cpp)
target_link_libraries(mechana_leslie_core_tests PRIVATE mechana_leslie_core)
add_test(NAME mechana_leslie_core_tests COMMAND mechana_leslie_core_tests)

