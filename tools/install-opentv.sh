#!/usr/bin/env bash
# Installs OpenTV on the TV: the IPTV player that sits on the home shelf.
#
#   tools/install-opentv.sh <tv-ip[:port]> [apk]
#
# The address is the one the TV shows: Settings > ... > Wireless debugging > IP address &
# port. The port is 5555 for USB debugging over TCP; wireless debugging (Android 11+)
# shows a random port and asks the computer to pair first (adb pair), which this script
# does not do — pair once with `adb pair <ip>:<port> <code>` and then run this.
#
# The APK is looked for in this order:
#   1. the path given on the command line
#   2. apps/OpenTV.apk (a copy placed there by hand)
#   3. the newest release APK in a sibling checkout of the OpenTV project
#      (../opentv/dist), which is where it is built
#
# The TV has to be in developer mode with USB debugging on (Settings > System > About >
# press *Android TV OS build* 7 times, then Developer options > USB debugging). The first
# connection asks the TV to allow it: tick *Always allow*.
#
# Updating OpenTV later is the same command with the new APK, and it must be signed with
# the same key as the installed one (adb refuses to replace an app signed by another key).
set -euo pipefail
cd "$(dirname "$0")/.."

ADDR="${1:-}"
APK="${2:-}"

if [ -z "$ADDR" ]; then
  echo "Usage: tools/install-opentv.sh <tv-ip[:port]> [apk]" >&2
  echo "  The TV's address: Settings > Network & Internet." >&2
  exit 1
fi

case "$ADDR" in
  *:*) HOST="${ADDR%:*}"; PORT="${ADDR##*:}" ;;
  *)   HOST="$ADDR"; PORT=5555 ;;
esac

# adb comes with the Android platform-tools; not everyone keeps it on the PATH.
ADB="${ADB:-$(command -v adb || true)}"
if [ -z "$ADB" ]; then
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /opt/android-sdk /usr/lib/android-sdk; do
    if [ -n "$d" ] && [ -x "$d/platform-tools/adb" ]; then ADB="$d/platform-tools/adb"; break; fi
  done
fi
[ -x "$ADB" ] || { echo "adb not found. Install the Android platform-tools (or set ANDROID_HOME)." >&2; exit 1; }

if [ -z "$APK" ]; then
  for candidate in \
      "$PWD/apps/OpenTV.apk" \
      "$(ls -t "$PWD"/../opentv/dist/*release*.apk 2>/dev/null | head -1 || true)"; do
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then APK="$candidate"; break; fi
  done
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "No OpenTV APK found. Build it in the OpenTV project (./gradlew assembleRelease)" >&2
  echo "and leave it in opentv/dist, or give the path as the second argument." >&2
  exit 1
fi

echo "Installing $APK on $HOST:$PORT..."
"$ADB" connect "$HOST:$PORT" >/dev/null 2>&1 || true
if ! "$ADB" devices | grep -q "^$HOST" ; then
  echo "Could not reach the TV at $HOST:$PORT." >&2
  echo "  - Check the address and that the TV is on the same network." >&2
  echo "  - On Android 11+ the wireless debugging asks to pair first:" >&2
  echo "      adb pair $HOST:$PORT <the 6-digit code the TV shows>" >&2
  echo "  - With a USB cable, enable USB debugging and plug it in." >&2
  exit 1
fi
"$ADB" install -r "$APK"

# OpenTV records TV to disk and keeps a wake lock while it does. Android TV and Fire TV put
# the app to sleep in Doze, which freezes a capture in progress, so the app asks for the
# battery exemption on its own the first time it records. This only makes sure the TV can
# show that prompt right away.
"$ADB" shell "cmd appops set app.opentv REQUEST_IGNORE_BATTERY_OPTIMIZATIONS allow" >/dev/null 2>&1 || true

echo
echo "Done. OpenTV is now on the home shelf (third from the left, next to YouCine)."
echo "Open it on the TV and add your provider: Xtream Codes login, M3U playlist URL or Stalker."
