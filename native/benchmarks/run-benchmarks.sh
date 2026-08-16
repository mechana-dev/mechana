#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

ROOT="${0:A:h}"
CYCLES="${MECHANA_BENCHMARK_CYCLES:-5}"
DURATION="${MECHANA_BENCHMARK_SECONDS:-2}"

run_architecture() {
	local architecture="$1"
	local execution_prefix=()
	if [[ "$(uname -m)" == "arm64" && "${architecture}" == "x86_64" ]]; then
		execution_prefix=(arch -x86_64)
	fi
	print "\n=== ${architecture} effect benchmarks ==="
	local found=0
	for benchmark in "${ROOT}/${architecture}"/mechana_*_benchmark(N); do
		found=1
		"${execution_prefix[@]}" "${benchmark}" --cycles "${CYCLES}" --seconds "${DURATION}"
	done
	if (( found == 0 )); then
		print -u2 "No ${architecture} effect benchmarks were packaged."
		return 1
	fi
}

if [[ "$(uname -m)" == "arm64" ]]; then
	run_architecture arm64
	if /usr/bin/pgrep oahd >/dev/null 2>&1 || arch -x86_64 /usr/bin/true >/dev/null 2>&1; then
		run_architecture x86_64
	else
		print "\nRosetta is unavailable; skipped x86_64 benchmarks."
	fi
else
	run_architecture x86_64
fi
