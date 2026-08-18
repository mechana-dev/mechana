#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0. See the repository LICENSE.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
ARCHITECTURE="${1:-}"
KEYCHAIN_PROFILE="${MACOS_NOTARY_PROFILE:-mechana-notary}"

case "${ARCHITECTURE}" in
	arm64 | x86_64) ;;
	*)
		print -u2 "Usage: ${0:t} <arm64|x86_64>"
		exit 2
		;;
esac

archive="${SCRIPT_DIR}/target/${ARCHITECTURE}/Mechana-Effects-macOS-${ARCHITECTURE}.zip"
app="${SCRIPT_DIR}/target/effects-app/${ARCHITECTURE}/apps/Mechana Effects.app"

[[ -f "${archive}" ]] || { print -u2 "Missing signed archive: ${archive}"; exit 1; }
[[ -d "${app}" ]] || { print -u2 "Missing staged app: ${app}"; exit 1; }

xcrun notarytool submit "${archive}" --keychain-profile "${KEYCHAIN_PROFILE}" --wait
xcrun stapler staple "${app}"
xcrun stapler validate "${app}"
/usr/bin/codesign --verify --deep --strict --verbose=2 "${app}"
/usr/sbin/spctl --assess --type execute --verbose=4 "${app}"
/usr/bin/ditto -c -k --keepParent "${app}" "${archive}"

print "Notarized Effects app: ${archive}"
