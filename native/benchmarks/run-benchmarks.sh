#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

ROOT="${0:A:h}"
CYCLES="${MECHANA_BENCHMARK_CYCLES:-5}"
DURATION="${MECHANA_BENCHMARK_SECONDS:-2}"
SAMPLE_RATES=(44100 48000 88200 96000)
SUMMARY_LINES=()

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
		for sample_rate in "${SAMPLE_RATES[@]}"; do
			print "\n--- sample rate: ${sample_rate} Hz ---"
			local output=""
			output="$("${execution_prefix[@]}" "${benchmark}" --cycles "${CYCLES}" --seconds "${DURATION}" \
				--sample-rate "${sample_rate}")"
			print -r -- "${output}"
			local summary="${${(f)output}[-1]}"
			local minimum="" average="" median="" maximum=""
			for statistic in ${(z)${summary#SUMMARY }}; do
				case "${statistic}" in
					load-min=*) minimum="${statistic#load-min=}"; minimum="${minimum%%%}" ;;
					load-average=*) average="${statistic#load-average=}"; average="${average%%%}" ;;
					load-median=*) median="${statistic#load-median=}"; median="${median%%%}" ;;
					load-max=*) maximum="${statistic#load-max=}"; maximum="${maximum%%%}" ;;
				esac
			done
			SUMMARY_LINES+=("${architecture}|${sample_rate}|${minimum}|${maximum}|${average}|${median}")
		done
	done
	if (( found == 0 )); then
		print -u2 "No ${architecture} effect benchmarks were packaged."
		return 1
	fi
}

if [[ "$(uname -m)" == "arm64" ]]; then
	if [[ -d "${ROOT}/arm64" ]]; then
		run_architecture arm64
	fi
	if [[ -d "${ROOT}/x86_64" ]]; then
		if /usr/bin/pgrep oahd >/dev/null 2>&1 || arch -x86_64 /usr/bin/true >/dev/null 2>&1; then
			run_architecture x86_64
		else
			print "\nRosetta is unavailable; skipped packaged x86_64 benchmarks."
		fi
	fi
else
	if [[ -d "${ROOT}/x86_64" ]]; then
		run_architecture x86_64
	fi
fi

print "\n\n"
print "======================================================================"
print "DETAILED BENCHMARK SUMMARY"
print "Real-time deadline load across ${CYCLES} cycles (lower is better)"
print "======================================================================"
for report_architecture in arm64 x86_64; do
	has_summary=0
	for summary_line in "${SUMMARY_LINES[@]}"; do
		fields=("${(@s:|:)summary_line}")
		if [[ "${fields[1]}" == "${report_architecture}" ]]; then
			has_summary=1
			break
		fi
	done
	(( has_summary == 1 )) || continue
	if [[ "${report_architecture}" == "arm64" ]]; then
		print "\nAPPLE SILICON — NATIVE ARM64"
	else
		if [[ "$(uname -m)" == "arm64" ]]; then
			print "\nINTEL — X86_64 THROUGH ROSETTA"
		else
			print "\nINTEL — NATIVE X86_64"
		fi
	fi
	printf "%-14s %12s %12s %12s %12s\n" "Sample rate" "Minimum" "Maximum" "Average" "Median"
	printf "%-14s %12s %12s %12s %12s\n" "-----------" "-------" "-------" "-------" "------"
	for summary_line in "${SUMMARY_LINES[@]}"; do
		fields=("${(@s:|:)summary_line}")
		[[ "${fields[1]}" == "${report_architecture}" ]] || continue
		printf "%-14s %11s%% %11s%% %11s%% %11s%%\n" "${fields[2]} Hz" "${fields[3]}" "${fields[4]}" \
			"${fields[5]}" "${fields[6]}"
	done
done
