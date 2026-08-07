#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -u

MECHANA_REPO="${MECHANA_REPO:-${HOME}/Projects/mechana}"
APP_JAR="${MECHANA_REPO}/client-job-launcher/target/mechana-client-job-launcher.jar"
APP_LOG="${HOME}/.mechana/client-job-launcher.log"

if pgrep -f "java -jar ${APP_JAR}" >/dev/null 2>&1; then
	echo "Mechana Client Job Launcher is already running."
	exit 0
fi
if [[ ! -f "${APP_JAR}" ]]; then
	cd "${MECHANA_REPO}" || exit 1
	mvn -pl client-job-launcher -am package -DskipTests || exit 1
fi
mkdir -p "${HOME}/.mechana"
cd "${MECHANA_REPO}" || exit 1
nohup java -jar "${APP_JAR}" >>"${APP_LOG}" 2>&1 &
echo "Mechana Client Job Launcher started."
