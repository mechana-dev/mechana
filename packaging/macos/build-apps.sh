#!/bin/zsh
# Copyright (c) 2026 Mark Vita
# Licensed under the Apache License, Version 2.0.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPOSITORY="${SCRIPT_DIR:h:h}"
TARGET="${SCRIPT_DIR}/target"
STAGING="${TARGET}/staging"
APPS="${TARGET}/apps"
ICONSET="${TARGET}/Mechana.iconset"
ICON="${TARGET}/Mechana.icns"
SOURCE_ICON="${REPOSITORY}/assets/brand/mechana-app-icon.png"

if [[ "$(uname -s)" != "Darwin" ]]; then
	print -u2 "macOS app images must be built on macOS."
	exit 1
fi
for tool in mvn jpackage sips iconutil; do
	if ! command -v "${tool}" >/dev/null 2>&1; then
		print -u2 "Required tool is unavailable: ${tool}"
		exit 1
	fi
done

cd "${REPOSITORY}"
mvn -pl macos-app-launcher,mechana-server,worker-control-app,client-job-launcher,plugins/sleep-plugin -am package

rm -rf "${TARGET}"
mkdir -p "${STAGING}/server" "${STAGING}/worker-control" "${STAGING}/job-launcher" "${APPS}" "${ICONSET}"

for size in 16 32 128 256 512; do
	sips -z "${size}" "${size}" "${SOURCE_ICON}" --out "${ICONSET}/icon_${size}x${size}.png" >/dev/null
	double=$((size * 2))
	sips -z "${double}" "${double}" "${SOURCE_ICON}" --out "${ICONSET}/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "${ICONSET}" -o "${ICON}"

cp macos-app-launcher/target/mechana-macos-app-launcher.jar "${STAGING}/server/"
cp mechana-server/target/mechana-server.jar "${STAGING}/server/"
cp plugins/sleep-plugin/target/mechana-plugin-sleep-0.1.0-SNAPSHOT.jar "${STAGING}/server/mechana-plugin-sleep.jar"
cp plugins/video-ffmpeg-plugin/target/mechana-plugin-video-0.1.0-SNAPSHOT.jar "${STAGING}/server/mechana-plugin-video.jar"
cp plugins/fractal-render-plugin/target/mechana-plugin-fractal-render-0.1.0-SNAPSHOT.jar "${STAGING}/server/mechana-plugin-fractal-render.jar"
cp plugins/ocr-tesseract-plugin/target/mechana-plugin-ocr-tesseract-0.1.0-SNAPSHOT.jar "${STAGING}/server/mechana-plugin-ocr-tesseract.jar"
cp plugins/blender-render-plugin/target/mechana-plugin-blender-render-0.1.0-SNAPSHOT.jar "${STAGING}/server/mechana-plugin-blender-render.jar"
cp worker-control-app/target/mechana-worker-control.jar "${STAGING}/worker-control/"
cp client-job-launcher/target/mechana-client-job-launcher.jar "${STAGING}/job-launcher/"

jpackage --type app-image --dest "${APPS}" --input "${STAGING}/server" \
	--name "Mechana Server" --main-jar mechana-macos-app-launcher.jar \
	--main-class dev.mechana.macos.ServerAppMain --icon "${ICON}" \
	--mac-package-identifier dev.mechana.server --app-version 1.0.0 \
	--add-launcher "Mechana Server Daemon"="${SCRIPT_DIR}/server-daemon.properties"
jpackage --type app-image --dest "${APPS}" --input "${STAGING}/worker-control" \
	--name "Mechana Worker Control" --main-jar mechana-worker-control.jar \
	--main-class dev.mechana.workercontrol.WorkerControlMain --icon "${ICON}" \
	--mac-package-identifier dev.mechana.worker-control --app-version 1.0.0
jpackage --type app-image --dest "${APPS}" --input "${STAGING}/job-launcher" \
	--name "Mechana Job Launcher" --main-jar mechana-client-job-launcher.jar \
	--main-class dev.mechana.launcher.ClientJobLauncherMain --icon "${ICON}" \
	--mac-package-identifier dev.mechana.job-launcher --app-version 1.0.0

print "Built macOS apps in ${APPS}:"
find "${APPS}" -maxdepth 1 -name '*.app' -print

if [[ "${1:-}" == "--install" ]]; then
	"${SCRIPT_DIR}/install-apps.sh"
fi
