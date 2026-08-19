#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPOSITORY="${SCRIPT_DIR:h:h}"
SOURCE_COMPONENT="${1:-${SCRIPT_DIR}/target/native-effects/arm64/echo-au/Mechana Echo.component}"
COMPONENTS_DIRECTORY="${HOME}/Library/Audio/Plug-Ins/Components"
INSTALLED_COMPONENT="${COMPONENTS_DIRECTORY}/Mechana Echo.component"
STAGING_COMPONENT="${COMPONENTS_DIRECTORY}/.Mechana Echo.installing.component"
BACKUP_COMPONENT="${COMPONENTS_DIRECTORY}/.Mechana Echo.previous.component"

if [[ ! -d "${SOURCE_COMPONENT}" ]]; then
	print -u2 "Echo AU not found: ${SOURCE_COMPONENT}"
	print -u2 "Build/package it first with packaging/macos/package-native-effects.sh arm64"
	exit 2
fi

source_marker="${SOURCE_COMPONENT}/Contents/Resources/MechanaBuild.txt"
if [[ ! -f "${source_marker}" ]]; then
	print -u2 "Refusing to install an Echo AU without MechanaBuild.txt: ${SOURCE_COMPONENT}"
	exit 2
fi

expected_commit="$(sed -n 's/^git-commit=//p' "${source_marker}")"
expected_dsp_sha="$(sed -n 's/^echo-dsp-sha256=//p' "${source_marker}")"
current_commit="$(git -C "${REPOSITORY}" rev-parse HEAD)"
current_dsp_sha="$(shasum -a 256 "${REPOSITORY}/native/echo-core/src/EchoEngine.cpp" | awk '{print $1}')"
if [[ "${expected_commit}" != "${current_commit}" || "${expected_dsp_sha}" != "${current_dsp_sha}" ]]; then
	print -u2 "Refusing to install a stale Echo AU."
	print -u2 "Packaged commit/DSP: ${expected_commit} / ${expected_dsp_sha}"
	print -u2 "Checkout commit/DSP: ${current_commit} / ${current_dsp_sha}"
	exit 3
fi

signing_identity="${MACOS_DEVELOPMENT_SIGNING_IDENTITY:-}"
if [[ -z "${signing_identity}" ]]; then
	signing_identity="$(security find-identity -v -p codesigning \
		| sed -n 's/.*"\(Apple Development:[^"]*\)".*/\1/p' | head -n 1)"
fi
if [[ -z "${signing_identity}" ]]; then
	print -u2 "No Apple Development code-signing identity is available."
	print -u2 "Set MACOS_DEVELOPMENT_SIGNING_IDENTITY explicitly, or use '-' for ad-hoc signing."
	exit 4
fi

mkdir -p "${COMPONENTS_DIRECTORY}"
cmake -E remove_directory "${STAGING_COMPONENT}"
cmake -E remove_directory "${BACKUP_COMPONENT}"
/usr/bin/ditto "${SOURCE_COMPONENT}" "${STAGING_COMPONENT}"
/usr/bin/xattr -cr "${STAGING_COMPONENT}"
/usr/bin/codesign --force --deep --sign "${signing_identity}" "${STAGING_COMPONENT}"
/usr/bin/codesign --verify --deep --strict --verbose=2 "${STAGING_COMPONENT}"

if [[ -d "${INSTALLED_COMPONENT}" ]]; then
	/bin/mv "${INSTALLED_COMPONENT}" "${BACKUP_COMPONENT}"
fi
if ! /bin/mv "${STAGING_COMPONENT}" "${INSTALLED_COMPONENT}"; then
	if [[ -d "${BACKUP_COMPONENT}" ]]; then
		/bin/mv "${BACKUP_COMPONENT}" "${INSTALLED_COMPONENT}"
	fi
	exit 5
fi
/usr/bin/touch "${INSTALLED_COMPONENT}"

installed_marker="${INSTALLED_COMPONENT}/Contents/Resources/MechanaBuild.txt"
cmp -s "${source_marker}" "${installed_marker}" || {
	print -u2 "Installed Echo build marker does not match the packaged component."
	exit 6
}

/usr/bin/killall -9 AudioComponentRegistrar 2>/dev/null || true
discovered=false
for attempt in {1..10}; do
	if /usr/bin/auval -a | grep -q '^aufx Echo Mchn'; then
		discovered=true
		break
	fi
	sleep 1
done
if [[ "${discovered}" != true ]]; then
	print -u2 "Audio Component Registrar did not discover the newly installed Echo AU."
	exit 7
fi

/usr/bin/auval -v aufx Echo Mchn
cmake -E remove_directory "${BACKUP_COMPONENT}"
print "Installed and validated Mechana Echo ${expected_commit[1,12]} using: ${signing_identity}"
