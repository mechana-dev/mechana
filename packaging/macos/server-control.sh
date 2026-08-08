#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -euo pipefail

LABEL="dev.mechana.server"
SERVICE="gui/$(id -u)/${LABEL}"
APP="/Applications/Mechana Server.app"
STATUS_URL="http://127.0.0.1:8787/api/dashboard"

case "${1:-status}" in
	start)
		open "${APP}"
		;;
	stop)
		launchctl bootout "${SERVICE}"
		;;
	restart)
		launchctl bootout "${SERVICE}" 2>/dev/null || true
		open "${APP}"
		;;
	status)
		if curl --silent --fail --max-time 2 "${STATUS_URL}" >/dev/null; then
			print "Mechana Server is running at http://127.0.0.1:8787/dashboard"
		else
			print "Mechana Server is not responding."
			exit 1
		fi
		;;
	*)
		print -u2 "Usage: $0 {start|stop|restart|status}"
		exit 2
		;;
esac
