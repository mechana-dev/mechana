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

echo_component="${STAGING}/echo-au/Mechana Echo.component"
leslie_component="${STAGING}/leslie-au/Mechana Leslie.component"
reverb_component="${STAGING}/reverb-au/Mechana Reverb.component"
fuzz_component="${STAGING}/octave-fuzz-au/Mechana Octave Fuzz.component"
benchmark_app="${STAGING}/benchmarks/Run Benchmarks.app"

benchmark_details="$(/usr/bin/codesign -dvvv "${benchmark_app}" 2>&1)"
print -r -- "${benchmark_details}" | grep -q '^Authority=Developer ID Application:'
print -r -- "${benchmark_details}" | grep -q 'flags=.*runtime'
benchmark_team="$(print -r -- "${benchmark_details}" | sed -n 's/^TeamIdentifier=//p')"
[[ -n "${benchmark_team}" && "${benchmark_team}" != "not set" ]] || {
	print -u2 "Benchmark app is not signed with a distribution team"
	exit 1
}
for executable in "${benchmark_app}/Contents/Resources/${ARCHITECTURE}"/mechana_*_benchmark(N); do
	executable_team="$(/usr/bin/codesign -dvvv "${executable}" 2>&1 | sed -n 's/^TeamIdentifier=//p')"
	[[ "${executable_team}" == "${benchmark_team}" ]] || {
		print -u2 "Benchmark executable signature does not match the app team: ${executable}"
		exit 1
	}
done

for archive in "${archives[@]}"; do
	[[ -f "${archive}" ]] || {
		print -u2 "Missing signed archive: ${archive}"
		exit 1
	}
	xcrun notarytool submit "${archive}" --keychain-profile "${KEYCHAIN_PROFILE}" --wait
done

for bundle in "${echo_component}" "${leslie_component}" "${reverb_component}" "${fuzz_component}" "${benchmark_app}"; do
	xcrun stapler staple "${bundle}"
	xcrun stapler validate "${bundle}"
	/usr/bin/codesign --verify --deep --strict --verbose=2 "${bundle}"
done

rm -f "${archives[@]}"
(cd "${echo_component:h}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archives[1]}" "${echo_component:t}")
(cd "${leslie_component:h}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archives[2]}" "${leslie_component:t}")
(cd "${reverb_component:h}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archives[3]}" "${reverb_component:t}")
(cd "${fuzz_component:h}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archives[4]}" "${fuzz_component:t}")
(cd "${STAGING}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archives[5]}" benchmarks)

/usr/sbin/spctl --assess --type execute --verbose=4 "${benchmark_app}"
print "Notarized native effects in ${TARGET}"
