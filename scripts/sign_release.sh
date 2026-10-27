#!/usr/bin/env bash
# Signs the unsigned release APK for distribution, as in Velo.
#
# With the secret release key (LAUNCHER_KEYSTORE_BASE64 / LAUNCHER_KEYSTORE_PASSWORD, key alias
# "launcher") the APK is signed with APK Signature Scheme v3 key rotation: the lineage proves that the
# old, public debug key handed over to the release key. Installed builds signed with the debug key
# accept it as an update, while the debug key alone can no longer update the app afterwards.
#
# Without the secret it falls back to the debug key, so the APK still installs and updates itself.
set -euo pipefail

BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
VERSION_CODE=${GITHUB_RUN_NUMBER:-1}
IN=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=dist/launcher-$VERSION_CODE.apk
TMP=${RUNNER_TEMP:-$(mktemp -d)}
DEBUG_KEY=(--ks keystore/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey)

mkdir -p dist
"$BT/zipalign" -p -f 4 "$IN" "$TMP/aligned.apk"

if [ -n "${LAUNCHER_KEYSTORE_BASE64:-}" ] && [ -n "${LAUNCHER_KEYSTORE_PASSWORD:-}" ]; then
  printf '%s' "$LAUNCHER_KEYSTORE_BASE64" | base64 -d > "$TMP/release.p12"
  RELEASE_KEY=(--ks "$TMP/release.p12" --ks-pass env:LAUNCHER_KEYSTORE_PASSWORD --ks-key-alias launcher)
  "$BT/apksigner" rotate --out "$TMP/lineage" \
    --old-signer "${DEBUG_KEY[@]}" \
    --new-signer "${RELEASE_KEY[@]}"
  "$BT/apksigner" sign "${DEBUG_KEY[@]}" \
    --next-signer "${RELEASE_KEY[@]}" \
    --lineage "$TMP/lineage" \
    --out "$OUT" "$TMP/aligned.apk"
  rm -f "$TMP/release.p12"
  SIGNED_WITH=release
else
  echo "::warning::LAUNCHER_KEYSTORE_BASE64 is not set; the release APK is signed with the public debug key."
  "$BT/apksigner" sign "${DEBUG_KEY[@]}" --out "$OUT" "$TMP/aligned.apk"
  SIGNED_WITH=debug
fi

"$BT/apksigner" verify -v --print-certs "$OUT" | grep -E "Verified using|Signer|SHA-256|lineage" || true
echo "apk=$OUT" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "signed_with=$SIGNED_WITH" >> "${GITHUB_OUTPUT:-/dev/null}"
