#!/usr/bin/env bash
# Drive a NakashTV streamer over adb: find it, install a build, screenshot, logs.
#
#   scripts/tv.sh devices            list streamers on the LAN and connect to them
#   scripts/tv.sh shot [name]        screenshot -> .screens/<name>.png (1280px wide)
#   scripts/tv.sh logs [lines]       recent NakashTV logcat lines + crashes
#   scripts/tv.sh launch             start NakashTV
#   scripts/tv.sh install <apk>      install in place (never uninstalls: keeps user data)
#   scripts/tv.sh run [apk]          install (if given) + launch + wait + shot + logs
#
# Target device: $TV (ip:port), default 10.0.0.6:5555 (bedroom Mi Box).
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
TV="${TV:-10.0.0.6:5555}"
PKG=tv.nakash
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHOTS="$ROOT/.screens"

adb_tv() { "$ADB" -s "$TV" "$@"; }

ensure_connected() {
  if ! "$ADB" devices | grep -q "^$TV[[:space:]]*device"; then
    "$ADB" connect "$TV" >/dev/null 2>&1 || true
    sleep 1
  fi
  "$ADB" devices | grep -q "^$TV[[:space:]]*device" || {
    echo "Streamer $TV is not reachable (off, asleep, or new IP). Try: scripts/tv.sh devices" >&2
    exit 1
  }
}

cmd_devices() {
  "$ADB" mdns services | awk '/_adb._tcp/ {print $3}' | while read -r addr; do
    "$ADB" connect "$addr" >/dev/null 2>&1 || true
  done
  "$ADB" devices -l
}

cmd_shot() {
  ensure_connected
  local name="${1:-tv-$(date +%H%M%S)}"
  mkdir -p "$SHOTS"
  adb_tv exec-out screencap -p > "$SHOTS/$name.png"
  sips -Z 1280 "$SHOTS/$name.png" >/dev/null
  echo "$SHOTS/$name.png"
}

cmd_logs() {
  ensure_connected
  local lines="${1:-200}"
  local pid
  pid="$(adb_tv shell pidof "$PKG" | tr -d '\r' || true)"
  echo "== crashes"
  adb_tv logcat -d -b crash -t 50 2>/dev/null | grep -A20 "$PKG" || echo "(none)"
  echo "== app log (pid ${pid:-not running})"
  if [ -n "$pid" ]; then
    adb_tv logcat -d -t "$lines" --pid="$pid"
  fi
}

cmd_launch() {
  ensure_connected
  adb_tv shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
}

cmd_install() {
  ensure_connected
  local apk="$1"
  # -r keeps data. A signature mismatch fails here on purpose: never uninstall to get around it.
  adb_tv install -r "$apk"
}

cmd_run() {
  ensure_connected
  [ -n "${1:-}" ] && cmd_install "$1"
  adb_tv logcat -c
  cmd_launch
  sleep "${WAIT:-6}"
  cmd_shot "run-$(date +%H%M%S)"
  cmd_logs 80 | tail -40
}

case "${1:-}" in
  devices) cmd_devices ;;
  shot)    shift; cmd_shot "$@" ;;
  logs)    shift; cmd_logs "$@" ;;
  launch)  cmd_launch ;;
  install) shift; cmd_install "$@" ;;
  run)     shift; cmd_run "$@" ;;
  *) sed -n '2,11p' "$0"; exit 1 ;;
esac
