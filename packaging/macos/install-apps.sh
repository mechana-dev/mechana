#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
SOURCE="${SCRIPT_DIR}/target/apps"
DESTINATION="${HOME}/Applications"

if [[ ! -d "${SOURCE}" ]]; then
	print -u2 "Build the apps first with packaging/macos/build-apps.sh."
	exit 1
fi

mkdir -p "${DESTINATION}"
for app in "Mechana Server.app" "Mechana Worker Control.app" "Mechana Job Launcher.app"; do
	if [[ ! -d "${SOURCE}/${app}" ]]; then
		print -u2 "Missing built app: ${SOURCE}/${app}"
		exit 1
	fi
	/usr/bin/ditto "${SOURCE}/${app}" "${DESTINATION}/${app}"
done

print "Installed Mechana apps in ${DESTINATION}. Drag them to the Dock if desired."
