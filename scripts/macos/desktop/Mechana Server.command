#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -u

MECHANA_REPO="${MECHANA_REPO:-${HOME}/Projects/mechana}"
MECHANA_DATA="${MECHANA_REPO}/.mechana/server"
MECHANA_LOG="${MECHANA_DATA}/server.log"
MECHANA_PID="${MECHANA_DATA}/server.pid"
MECHANA_DASHBOARD="http://127.0.0.1:8787/dashboard"
MECHANA_PUBLIC_URL="http://marks-macbook-air-m4:8787"
MECHANA_JAR="${MECHANA_REPO}/mechana-server/target/mechana-server.jar"

show_dashboard() {
	open -na "Safari" "${MECHANA_DASHBOARD}"
}

if curl --silent --fail --max-time 2 "${MECHANA_DASHBOARD}" >/dev/null 2>&1; then
	show_dashboard
	exit 0
fi

mkdir -p "${MECHANA_DATA}"
if lsof -nP -iTCP:8787 -sTCP:LISTEN >/dev/null 2>&1; then
	echo "Port 8787 is already in use, but it is not serving the Mechana dashboard."
	read -k 1 "?Press any key to close."
	exit 1
fi

if [[ ! -f "${MECHANA_JAR}" ]]; then
	cd "${MECHANA_REPO}" || exit 1
	mvn -pl mechana-server,plugins/sleep-plugin -am package -DskipTests || exit 1
fi

cd "${MECHANA_REPO}" || exit 1
nohup java -jar "${MECHANA_JAR}" 8787 "${MECHANA_PUBLIC_URL}" "${MECHANA_DATA}" >>"${MECHANA_LOG}" 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" >"${MECHANA_PID}"
for attempt in {1..60}; do
	if curl --silent --fail --max-time 1 "${MECHANA_DASHBOARD}" >/dev/null 2>&1; then
		show_dashboard
		exit 0
	fi
	if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
		echo "The Mechana server stopped. See ${MECHANA_LOG}."
		read -k 1 "?Press any key to close."
		exit 1
	fi
	sleep 0.5
done

echo "The Mechana server did not become ready. See ${MECHANA_LOG}."
read -k 1 "?Press any key to close."
exit 1
