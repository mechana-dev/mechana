#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPOSITORY="${SCRIPT_DIR:h:h}"
TARGET="${SCRIPT_DIR}/target/native-effects"
SIGNING_IDENTITY="${MACOS_SIGNING_IDENTITY:--}"
ARM64_BUILD="${MECHANA_ARM64_BUILD:-${REPOSITORY}/native/build-echo-suite}"
X86_64_BUILD="${MECHANA_X86_64_BUILD:-${REPOSITORY}/native/build-echo-suite-x86_64}"

create_benchmark_app() {
	local architecture="$1"
	local benchmarks="$2"
	local app="${benchmarks}/Run Benchmarks.app"
	local contents="${app}/Contents"
	local executable="${contents}/MacOS/Run Benchmarks"
	local resources="${contents}/Resources"

	mkdir -p "${contents}/MacOS" "${resources}"
	xcrun swiftc -O -parse-as-library -target "${architecture}-apple-macosx12.0" -framework AppKit \
		"${SCRIPT_DIR}/BenchmarkLauncher.swift" -o "${executable}"
	cp "${REPOSITORY}/native/benchmarks/run-benchmarks.sh" "${resources}/run-benchmarks.sh"
	cp -R "${benchmarks}/${architecture}" "${resources}/${architecture}"
	cat >"${contents}/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>Run Benchmarks</string>
<key>CFBundleIdentifier</key><string>dev.mechana.effect-benchmarks.${architecture}</string>
<key>CFBundleName</key><string>Mechana Effect Benchmarks</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSMinimumSystemVersion</key><string>12.0</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
EOF
}

sign_path() {
	local path="$1"
	local signing_arguments=(--force --deep --sign "${SIGNING_IDENTITY}")
	if [[ "${SIGNING_IDENTITY}" != "-" ]]; then
		signing_arguments+=(--options runtime --timestamp)
	fi
	/usr/bin/codesign --remove-signature "${path}" 2>/dev/null || true
	/usr/bin/codesign "${signing_arguments[@]}" "${path}"
	/usr/bin/codesign --verify --deep --strict "${path}"
}

package_architecture() {
	local architecture="$1"
	local build="$2"
	local suffix="$3"
	local staging="${TARGET}/${architecture}"
	local archives="${SCRIPT_DIR}/target/${architecture}"
	local echo_source="${build}/adapters/juce-echo-plugin/MechanaEcho_artefacts/Release/AU/Mechana Echo.component"
	local leslie_source="${build}/adapters/juce-leslie-plugin/MechanaLeslie_artefacts/Release/AU/Mechana Leslie.component"
	local reverb_source="${build}/adapters/juce-plugin/MechanaReverb_artefacts/Release/AU/Mechana Reverb.component"
	local fuzz_source="${build}/adapters/juce-octave-fuzz-plugin/MechanaOctaveFuzz_artefacts/Release/AU/Mechana Octave Fuzz.component"
	local echo_marker="${staging}/echo-au/Mechana Echo.component/Contents/Resources/MechanaBuild.txt"

	# Rebuild the AU from this checkout immediately before copying it. This prevents a
	# newer packaging run from silently reusing an older component from the build tree.
	cmake --build "${build}" --config Release --target MechanaEcho_AU

	mkdir -p "${archives}" "${staging}/echo-au" "${staging}/leslie-au" "${staging}/reverb-au" \
		"${staging}/octave-fuzz-au" "${staging}/benchmarks/${architecture}"
	/usr/bin/ditto "${echo_source}" "${staging}/echo-au/Mechana Echo.component"
	/usr/bin/ditto "${leslie_source}" "${staging}/leslie-au/Mechana Leslie.component"
	/usr/bin/ditto "${reverb_source}" "${staging}/reverb-au/Mechana Reverb.component"
	/usr/bin/ditto "${fuzz_source}" "${staging}/octave-fuzz-au/Mechana Octave Fuzz.component"
	mkdir -p "${echo_marker:h}"
	{
		print "git-commit=$(git -C "${REPOSITORY}" rev-parse HEAD)"
		print "echo-dsp-sha256=$(shasum -a 256 "${REPOSITORY}/native/echo-core/src/EchoEngine.cpp" | awk '{print $1}')"
		print "component-sha256-before-signing=$(shasum -a 256 "${staging}/echo-au/Mechana Echo.component/Contents/MacOS/Mechana Echo" | awk '{print $1}')"
		print "architecture=${architecture}"
	} >"${echo_marker}"
	grep -q "echo-dsp-sha256=" "${echo_marker}"
	cp "${build}/echo-core/benchmarks/mechana_echo_benchmark" "${staging}/benchmarks/${architecture}/"
	cp "${build}/leslie-core/benchmarks/mechana_leslie_benchmark" "${staging}/benchmarks/${architecture}/"
	cp "${build}/reverb-core/benchmarks/mechana_reverb_benchmark" "${staging}/benchmarks/${architecture}/"
	cp "${build}/octave-fuzz-core/benchmarks/mechana_octave_fuzz_benchmark" "${staging}/benchmarks/${architecture}/"
	cp "${REPOSITORY}/native/benchmarks/README.md" "${staging}/benchmarks/README.md"
	cp "${REPOSITORY}/LICENSE" "${staging}/benchmarks/LICENSE"
	create_benchmark_app "${architecture}" "${staging}/benchmarks"

	sign_path "${staging}/echo-au/Mechana Echo.component"
	sign_path "${staging}/leslie-au/Mechana Leslie.component"
	sign_path "${staging}/reverb-au/Mechana Reverb.component"
	sign_path "${staging}/octave-fuzz-au/Mechana Octave Fuzz.component"
	sign_path "${staging}/benchmarks/${architecture}/mechana_echo_benchmark"
	sign_path "${staging}/benchmarks/${architecture}/mechana_leslie_benchmark"
	sign_path "${staging}/benchmarks/${architecture}/mechana_reverb_benchmark"
	sign_path "${staging}/benchmarks/${architecture}/mechana_octave_fuzz_benchmark"
	sign_path "${staging}/benchmarks/Run Benchmarks.app/Contents/Resources/${architecture}/mechana_echo_benchmark"
	sign_path "${staging}/benchmarks/Run Benchmarks.app/Contents/Resources/${architecture}/mechana_leslie_benchmark"
	sign_path "${staging}/benchmarks/Run Benchmarks.app/Contents/Resources/${architecture}/mechana_reverb_benchmark"
	sign_path "${staging}/benchmarks/Run Benchmarks.app/Contents/Resources/${architecture}/mechana_octave_fuzz_benchmark"
	sign_path "${staging}/benchmarks/Run Benchmarks.app"
	/usr/bin/ditto -c -k --keepParent "${staging}/echo-au/Mechana Echo.component" \
		"${archives}/Mechana-Echo-AU-macOS-${suffix}.zip"
	shasum -a 256 "${archives}/Mechana-Echo-AU-macOS-${suffix}.zip" \
		>"${archives}/Mechana-Echo-AU-macOS-${suffix}.zip.sha256"
	/usr/bin/ditto -c -k --keepParent "${staging}/leslie-au/Mechana Leslie.component" \
		"${archives}/Mechana-Leslie-AU-macOS-${suffix}.zip"
	/usr/bin/ditto -c -k --keepParent "${staging}/reverb-au/Mechana Reverb.component" \
		"${archives}/Mechana-Reverb-AU-macOS-${suffix}.zip"
	/usr/bin/ditto -c -k --keepParent "${staging}/octave-fuzz-au/Mechana Octave Fuzz.component" \
		"${archives}/Mechana-Octave-Fuzz-AU-macOS-${suffix}.zip"
	/usr/bin/ditto -c -k --keepParent "${staging}/benchmarks" \
		"${archives}/Mechana-Effect-Benchmarks-macOS-${suffix}.zip"
}

cmake -E remove_directory "${TARGET}"
mkdir -p "${TARGET}"
case "${1:-all}" in
	all)
		package_architecture arm64 "${ARM64_BUILD}" arm64
		package_architecture x86_64 "${X86_64_BUILD}" x86_64
		;;
	arm64)
		package_architecture arm64 "${ARM64_BUILD}" arm64
		;;
	x86_64)
		package_architecture x86_64 "${X86_64_BUILD}" x86_64
		;;
	*)
		print -u2 "Usage: ${0:t} [all|arm64|x86_64]"
		exit 2
		;;
esac

print "Packaged native effects in ${SCRIPT_DIR}/target"
