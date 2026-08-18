#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPOSITORY="${SCRIPT_DIR:h:h}"
TARGET="${SCRIPT_DIR}/target/reverb"
STAGING="${TARGET}/staging"
APPS="${TARGET}/apps"
ICON="${TARGET}/MechanaReverb.icns"
JPACKAGE="${JAVA_HOME:-}/bin/jpackage"
SIGNING_IDENTITY="${MACOS_SIGNING_IDENTITY:-}"

if [[ "$(uname -s)" != "Darwin" ]]; then
	print -u2 "The macOS Reverb app must be built on macOS."
	exit 1
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JPACKAGE}" ]]; then
	print -u2 "Set JAVA_HOME to the Java 25 JDK whose architecture should be packaged."
	exit 1
fi
for tool in mvn /usr/bin/ditto xcrun; do
	if ! command -v "${tool}" >/dev/null 2>&1; then
		print -u2 "Required tool is unavailable: ${tool}"
		exit 1
	fi
done

JPACKAGE_KIND="$(file "${JPACKAGE}")"
if [[ "${JPACKAGE_KIND}" == *"x86_64"* ]]; then
	PACKAGE_ARCH="x86_64"
elif [[ "${JPACKAGE_KIND}" == *"arm64"* ]]; then
	PACKAGE_ARCH="arm64"
else
	print -u2 "Cannot determine jpackage architecture: ${JPACKAGE_KIND}"
	exit 1
fi

cd "${REPOSITORY}"
mvn -pl standalone-reverb-app -am package

rm -rf "${TARGET}"
mkdir -p "${STAGING}/ir-profiles" "${STAGING}/capture" "${APPS}"
cp "${SCRIPT_DIR}/icons/mechana-reverb.icns" "${ICON}"

xcrun clang -O2 -arch "${PACKAGE_ARCH}" -mmacosx-version-min=12.0 \
	-framework AudioToolbox -framework CoreAudio -framework CoreFoundation \
	"${SCRIPT_DIR}/MechanaPreviewAudio.c" -o "${STAGING}/mechana-preview-audio"

cp standalone-reverb-app/target/mechana-standalone-reverb.jar "${STAGING}/"
cp standalone-reverb-app/src/main/distribution/ir-profiles/* "${STAGING}/ir-profiles/"
cp standalone-reverb-app/src/main/distribution/capture/* "${STAGING}/capture/"
cp LICENSE NOTICE "${STAGING}/"

signing_options=()
if [[ -n "${SIGNING_IDENTITY}" ]]; then
	signing_options=(--mac-sign --mac-signing-key-user-name "${SIGNING_IDENTITY}")
fi

"${JPACKAGE}" --type app-image --dest "${APPS}" --input "${STAGING}" \
	--name "Mechana Effects" --main-jar mechana-standalone-reverb.jar \
	--main-class dev.mechana.localreverb.StandaloneReverbMain --icon "${ICON}" \
	--mac-package-identifier dev.mechana.effects --app-version 1.0.0 "${signing_options[@]}"

if [[ -n "${SIGNING_IDENTITY}" ]]; then
	app_bundle="${APPS}/Mechana Effects.app"
	chmod -R u+w "${app_bundle}"
	xattr -cr "${app_bundle}"
	while IFS= read -r -d '' binary; do
		if file "${binary}" | grep -q 'Mach-O'; then
			/usr/bin/codesign --force --options runtime --timestamp --sign "${SIGNING_IDENTITY}" \
				--preserve-metadata=identifier,entitlements,requirements,flags "${binary}"
		fi
	done < <(find "${app_bundle}" -type f -print0)
	/usr/bin/codesign --force --options runtime --timestamp --sign "${SIGNING_IDENTITY}" \
		"${app_bundle}/Contents/runtime"
	/usr/bin/codesign --force --options runtime --timestamp --sign "${SIGNING_IDENTITY}" "${app_bundle}"
	/usr/bin/codesign --verify --deep --strict --verbose=2 "${APPS}/Mechana Effects.app"
fi

archive="${SCRIPT_DIR}/target/Mechana-Effects-macOS-${PACKAGE_ARCH}.zip"
rm -f "${archive}"
(cd "${APPS}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archive}" "Mechana Effects.app")

print "Built ${PACKAGE_ARCH} Effects app:"
print "${APPS}/Mechana Effects.app"
print "${SCRIPT_DIR}/target/Mechana-Effects-macOS-${PACKAGE_ARCH}.zip"
