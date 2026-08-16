#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
BUILD="${SCRIPT_DIR}/build-verify"
cmake -S "${SCRIPT_DIR}" -B "${BUILD}" -DCMAKE_BUILD_TYPE=Release \
	-DMECHANA_BUILD_JUCE_PLUGIN=OFF -DMECHANA_BUILD_TESTS=ON -DMECHANA_BUILD_BENCHMARKS=ON
cmake --build "${BUILD}"
ctest --test-dir "${BUILD}" --output-on-failure
"${SCRIPT_DIR:h}/packaging/macos/build-effect-benchmarks.sh" >/dev/null
print "\nNative effect performance samples (reported, not pass/fail thresholds):"
MECHANA_BENCHMARK_CYCLES=1 MECHANA_BENCHMARK_SECONDS=1 \
	"${SCRIPT_DIR:h}/packaging/macos/target/effect-benchmarks/Run Benchmarks.command"
