#!/usr/bin/env bash
# Adds a Tapo camera to the Phase 0 app over adb, so nobody types a password with the remote.
# The password is read without echo and isn't saved in shell history.
# Works with the debug and perf builds; start the app on the TV first (cameras live in memory).
#
# Usage: tools/scripts/add-camera.sh "Front door" 192.168.1.20 tapoview [telephoto]
#        ANDROID_SERIAL=192.168.1.50:5555 tools/scripts/add-camera.sh ...   (pick a device)
# The address may end in :port when RTSP isn't on 554 (192.168.1.20:8554).
set -euo pipefail
if [ $# -lt 3 ]; then
  echo "Usage: $0 <name> <camera-ip[:port]> <camera-account-user> [telephoto]" >&2
  exit 2
fi
name=$1; host=$2; user=$3; lens=${4:-standard}
read -r -s -p "Camera Account password for $user: " pass; echo

# adb joins its arguments into one command for the TV's shell, so quote each value for that shell.
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

args=(shell am broadcast -a io.github.rhlshlt.tvrtsp.ADD_TAPO -n io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver
  --es name "$(q "$name")" --es host "$(q "$host")" --es user "$(q "$user")" --es pass "$(q "$pass")")
if [ "$lens" = "telephoto" ]; then args+=(--es lens telephoto); fi
adb "${args[@]}"
unset pass
