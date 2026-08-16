#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPOSITORY="${SCRIPT_DIR:h:h}"
TARGET="${SCRIPT_DIR}/target/effect-benchmarks"

build_architecture() {
	local architecture="$1"
	local build="${REPOSITORY}/native/build-effect-benchmarks-${architecture}"
	cmake -S "${REPOSITORY}/native" -B "${build}" -DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_OSX_ARCHITECTURES="${architecture}" -DMECHANA_BUILD_JUCE_PLUGIN=OFF \
		-DMECHANA_BUILD_TESTS=OFF -DMECHANA_BUILD_BENCHMARKS=ON
	cmake --build "${build}"
	mkdir -p "${TARGET}/${architecture}"
	for benchmark in "${build}"/**/mechana_*_benchmark(N.); do
		cp "${benchmark}" "${TARGET}/${architecture}/"
	done
}

cmake -E remove_directory "${TARGET}"
mkdir -p "${TARGET}"
cp "${REPOSITORY}/native/benchmarks/run-benchmarks.sh" "${TARGET}/Run Benchmarks.command"
cp "${REPOSITORY}/native/benchmarks/README.md" "${TARGET}/README.md"
cp "${REPOSITORY}/LICENSE" "${TARGET}/LICENSE"

if [[ "$(uname -m)" == "arm64" ]]; then
	build_architecture arm64
	build_architecture x86_64
else
	build_architecture x86_64
fi

/usr/bin/ditto -c -k --keepParent "${TARGET}" \
	"${SCRIPT_DIR}/target/Mechana-Effect-Benchmarks-macOS.zip"
print "Built ${SCRIPT_DIR}/target/Mechana-Effect-Benchmarks-macOS.zip"
