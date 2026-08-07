#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -eu

SCRIPT_DIR="${0:A:h}"
SOURCE_DIR="${SCRIPT_DIR}/desktop"
DESTINATION="${HOME}/Desktop"

for shortcut in "${SOURCE_DIR}"/*.command; do
	install -m 755 "${shortcut}" "${DESTINATION}/${shortcut:t}"
done

echo "Installed Mechana desktop shortcuts in ${DESTINATION}."
