#!/usr/bin/env bash
# Builds FreeMyTV.apk inside Docker, for a machine without a JDK (or with a different one).
# Same result as ./build.sh: it runs that script; the container only supplies the JDK and zip.
# Needs Docker and the Android SDK (build-tools 34+, platforms;android-34) on the host.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found at $SDK: set ANDROID_HOME."; exit 1; }

IMAGE=freemytv-build
docker build -q -t "$IMAGE" -f tools/Dockerfile.build tools >/dev/null

# Same uid/gid as the host so FreeMyTV.apk and out/ are not left owned by root.
exec docker run --rm \
  --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -e ANDROID_HOME=/sdk -e JAVA_HOME=/opt/java/openjdk \
  -v "$PWD:/work" -v "$SDK:/sdk:ro" -w /work \
  "$IMAGE" ./build.sh "$@"
