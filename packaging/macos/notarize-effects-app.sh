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
rm -f "${archive}"
(cd "${app:h}" && COPYFILE_DISABLE=1 /usr/bin/zip -qry --symlinks "${archive}" "${app:t}")

verification_directory="$(mktemp -d "${TMPDIR:-/tmp}/mechana-effects-verify.XXXXXX")"
trap 'rm -rf "${verification_directory}"' EXIT
/usr/bin/ditto -x -k "${archive}" "${verification_directory}"
/usr/bin/codesign --verify --deep --strict --verbose=2 "${verification_directory}/${app:t}"
/usr/sbin/spctl --assess --type execute --verbose=4 "${verification_directory}/${app:t}"

print "Notarized Effects app: ${archive}"
