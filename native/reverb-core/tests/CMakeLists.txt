# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_executable(mechana_reverb_core_tests ReverbEngineTest.cpp)
target_link_libraries(mechana_reverb_core_tests PRIVATE mechana_reverb_core)
add_test(NAME mechana_reverb_core_tests COMMAND mechana_reverb_core_tests)
