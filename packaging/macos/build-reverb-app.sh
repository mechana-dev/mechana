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

if [[ "$(uname -s)" != "Darwin" ]]; then
	print -u2 "The macOS Reverb app must be built on macOS."
	exit 1
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JPACKAGE}" ]]; then
	print -u2 "Set JAVA_HOME to the Java 25 JDK whose architecture should be packaged."
	exit 1
fi
for tool in mvn /usr/bin/ditto; do
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

cp standalone-reverb-app/target/mechana-standalone-reverb.jar "${STAGING}/"
cp standalone-reverb-app/src/main/distribution/ir-profiles/* "${STAGING}/ir-profiles/"
cp standalone-reverb-app/src/main/distribution/capture/* "${STAGING}/capture/"
cp LICENSE NOTICE "${STAGING}/"

"${JPACKAGE}" --type app-image --dest "${APPS}" --input "${STAGING}" \
	--name "Mechana Reverb" --main-jar mechana-standalone-reverb.jar \
	--main-class dev.mechana.localreverb.StandaloneReverbMain --icon "${ICON}" \
	--mac-package-identifier dev.mechana.reverb --app-version 1.0.0

/usr/bin/ditto -c -k --sequesterRsrc --keepParent "${APPS}/Mechana Reverb.app" \
	"${SCRIPT_DIR}/target/Mechana-Reverb-macOS-${PACKAGE_ARCH}.zip"

print "Built ${PACKAGE_ARCH} Reverb app:"
print "${APPS}/Mechana Reverb.app"
print "${SCRIPT_DIR}/target/Mechana-Reverb-macOS-${PACKAGE_ARCH}.zip"
