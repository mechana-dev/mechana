#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -u

MECHANA_REPO="${MECHANA_REPO:-${HOME}/Projects/mechana}"
APP_JAR="${MECHANA_REPO}/worker-control-app/target/mechana-worker-control.jar"
APP_LOG="${HOME}/.mechana/worker-control.log"

if pgrep -f "java -jar ${APP_JAR}" >/dev/null 2>&1; then
	echo "Mechana Worker Control is already running."
	exit 0
fi
if [[ ! -f "${APP_JAR}" ]]; then
	cd "${MECHANA_REPO}" || exit 1
	mvn -pl worker-control-app -am package -DskipTests || exit 1
fi
mkdir -p "${HOME}/.mechana"
cd "${MECHANA_REPO}" || exit 1
nohup java -jar "${APP_JAR}" >>"${APP_LOG}" 2>&1 &
echo "Mechana Worker Control started."
