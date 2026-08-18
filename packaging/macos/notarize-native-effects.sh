#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
ARCHITECTURE="${1:-x86_64}"
KEYCHAIN_PROFILE="${MACOS_NOTARY_PROFILE:-mechana-notary}"
TARGET="${SCRIPT_DIR}/target"
STAGING="${TARGET}/native-effects/${ARCHITECTURE}"
ARCHIVES="${TARGET}/${ARCHITECTURE}"

case "${ARCHITECTURE}" in
	arm64 | x86_64) ;;
	*)
		print -u2 "Usage: ${0:t} [arm64|x86_64]"
		exit 2
		;;
esac

archives=(
	"${ARCHIVES}/Mechana-Echo-AU-macOS-${ARCHITECTURE}.zip"
	"${ARCHIVES}/Mechana-Leslie-AU-macOS-${ARCHITECTURE}.zip"
	"${ARCHIVES}/Mechana-Reverb-AU-macOS-${ARCHITECTURE}.zip"
	"${ARCHIVES}/Mechana-Octave-Fuzz-AU-macOS-${ARCHITECTURE}.zip"
	"${ARCHIVES}/Mechana-Effect-Benchmarks-macOS-${ARCHITECTURE}.zip"
)

for archive in "${archives[@]}"; do
	[[ -f "${archive}" ]] || {
		print -u2 "Missing signed archive: ${archive}"
		exit 1
	}
	xcrun notarytool submit "${archive}" --keychain-profile "${KEYCHAIN_PROFILE}" --wait
done

echo_component="${STAGING}/echo-au/Mechana Echo.component"
leslie_component="${STAGING}/leslie-au/Mechana Leslie.component"
reverb_component="${STAGING}/reverb-au/Mechana Reverb.component"
fuzz_component="${STAGING}/octave-fuzz-au/Mechana Octave Fuzz.component"
benchmark_app="${STAGING}/benchmarks/Run Benchmarks.app"

for bundle in "${echo_component}" "${leslie_component}" "${reverb_component}" "${fuzz_component}" "${benchmark_app}"; do
	xcrun stapler staple "${bundle}"
	xcrun stapler validate "${bundle}"
	/usr/bin/codesign --verify --deep --strict --verbose=2 "${bundle}"
done

/usr/bin/ditto -c -k --keepParent "${echo_component}" "${archives[1]}"
/usr/bin/ditto -c -k --keepParent "${leslie_component}" "${archives[2]}"
/usr/bin/ditto -c -k --keepParent "${reverb_component}" "${archives[3]}"
/usr/bin/ditto -c -k --keepParent "${fuzz_component}" "${archives[4]}"
/usr/bin/ditto -c -k --keepParent "${STAGING}/benchmarks" "${archives[5]}"

/usr/sbin/spctl --assess --type execute --verbose=4 "${benchmark_app}"
print "Notarized native effects in ${TARGET}"
