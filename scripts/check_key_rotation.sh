#!/usr/bin/env bash
# Proves on the emulator that the key rotation works as intended:
# 1. A build signed with the old debug key (what users have installed) accepts the release as an update.
# 2. Afterwards an APK signed only with the public debug key is rejected.
set -uo pipefail
DEBUG_APK=$1
RELEASE_APK=$2

adb uninstall io.github.davydgky.launcher >/dev/null 2>&1
adb install -r "$DEBUG_APK" || { echo "::error::installing the debug build failed"; exit 1; }
if ! adb install -r "$RELEASE_APK"; then
  echo "::error::the release APK does not install as an update over a debug-key build"
  exit 1
fi
if adb install -r "$DEBUG_APK" 2>&1 | tee /dev/stderr | grep -q "Success"; then
  echo "::error::an APK signed only with the public debug key could replace the release build"
  exit 1
fi
echo "[rotation] debug -> release update works; debug key alone is rejected"
