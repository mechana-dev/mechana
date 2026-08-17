# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

add_executable(mechana_leslie_benchmark LeslieBenchmark.cpp)
target_link_libraries(mechana_leslie_benchmark PRIVATE mechana_leslie_core)

