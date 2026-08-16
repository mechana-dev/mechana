#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPOSITORY="${SCRIPT_DIR:h:h}"
TARGET="${SCRIPT_DIR}/target/native-effects"

package_architecture() {
	local architecture="$1"
	local build="$2"
	local suffix="$3"
	local staging="${TARGET}/${architecture}"
	local app_source="${build}/apps/mechana-effects/MechanaEffects_artefacts/Release/Mechana Effects.app"
	local echo_source="${build}/adapters/juce-echo-plugin/MechanaEcho_artefacts/Release/AU/Mechana Echo.component"
	local reverb_source="${build}/adapters/juce-plugin/MechanaReverb_artefacts/Release/AU/Mechana Reverb.component"

	mkdir -p "${staging}/app" "${staging}/echo-au" "${staging}/reverb-au" "${staging}/benchmarks/${architecture}"
	/usr/bin/ditto "${app_source}" "${staging}/app/Mechana Effects.app"
	/usr/bin/ditto "${echo_source}" "${staging}/echo-au/Mechana Echo.component"
	/usr/bin/ditto "${reverb_source}" "${staging}/reverb-au/Mechana Reverb.component"
	cp "${build}/echo-core/benchmarks/mechana_echo_benchmark" "${staging}/benchmarks/${architecture}/"
	cp "${build}/reverb-core/benchmarks/mechana_reverb_benchmark" "${staging}/benchmarks/${architecture}/"
	cp "${REPOSITORY}/native/benchmarks/run-benchmarks.sh" "${staging}/benchmarks/Run Benchmarks.command"
	cp "${REPOSITORY}/native/benchmarks/README.md" "${staging}/benchmarks/README.md"
	cp "${REPOSITORY}/LICENSE" "${staging}/benchmarks/LICENSE"

	/usr/bin/codesign --force --deep --sign - "${staging}/app/Mechana Effects.app"
	/usr/bin/codesign --force --deep --sign - "${staging}/echo-au/Mechana Echo.component"
	/usr/bin/codesign --force --deep --sign - "${staging}/reverb-au/Mechana Reverb.component"
	/usr/bin/ditto -c -k --keepParent "${staging}/app/Mechana Effects.app" \
		"${SCRIPT_DIR}/target/Mechana-Effects-macOS-${suffix}.zip"
	/usr/bin/ditto -c -k --keepParent "${staging}/echo-au/Mechana Echo.component" \
		"${SCRIPT_DIR}/target/Mechana-Echo-AU-macOS-${suffix}.zip"
	/usr/bin/ditto -c -k --keepParent "${staging}/reverb-au/Mechana Reverb.component" \
		"${SCRIPT_DIR}/target/Mechana-Reverb-AU-macOS-${suffix}.zip"
	/usr/bin/ditto -c -k --keepParent "${staging}/benchmarks" \
		"${SCRIPT_DIR}/target/Mechana-Effect-Benchmarks-macOS-${suffix}.zip"
}

cmake -E remove_directory "${TARGET}"
mkdir -p "${TARGET}"
package_architecture arm64 "${REPOSITORY}/native/build-echo-suite" arm64
package_architecture x86_64 "${REPOSITORY}/native/build-echo-suite-x86_64" x86_64

print "Packaged native effects in ${SCRIPT_DIR}/target"
