#!/usr/bin/env bash
# Builds the A->B updater proof fixtures for Roidgram.
#   The "good" update is the REAL Roidgram B build (B_APK, stable-signed, vc = A+1):
#   only a real Roidgram-to-Roidgram replacement supports claims about the complete
#   update path; the minimal crafted APK (c.apk, throwaway key) is used ONLY for the
#   wrong-signer rejection case, where the plumbing itself is what is tested.
#   roidgram-update-{good,badsha,wrongsig,old}.json - release manifests
#   release-{good,badsha,wrongsig,old}.json         - GitHub-release-shaped wrappers
# Env in: APP_APK B_APK OUT ANDROID_HOME
# Prints only non-secret facts. Never echoes key material.
set -euo pipefail

BT="$ANDROID_HOME/build-tools/36.0.0"
AAPT="$BT/aapt"
AAPT2="$BT/aapt2"
APKSIGNER="$BT/apksigner"
ZIPALIGN="$BT/zipalign"
for t in "$AAPT" "$AAPT2" "$APKSIGNER" "$ZIPALIGN"; do
  [ -x "$t" ] || { echo "missing build-tool: $t"; exit 1; }
done

A_VC=$("$AAPT" dump badging "$APP_APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
B_VC=$("$AAPT" dump badging "$B_APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
[ -n "$A_VC" ] && [ -n "$B_VC" ] || { echo "could not read versionCodes"; exit 1; }
[ "$B_VC" -gt "$A_VC" ] || { echo "fixture B ($B_VC) must be newer than A ($A_VC)"; exit 1; }
echo "installed A versionCode=$A_VC -> real B versionCode=$B_VC"
echo "$B_VC" > "$OUT/b-versioncode.txt"
cp "$B_APK" "$OUT/b.apk"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Wrong-signer case: minimal APK, same package and versionCode as B, throwaway key
# (never leaves the runner). Only tests the signer check, so a minimal APK suffices.
cat > "$WORK/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="app.roidgram"
    android:versionCode="$B_VC"
    android:versionName="updater-proof-fixture">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
    <application android:label="RoidgramFixture"/>
</manifest>
EOF
"$AAPT2" link -o "$WORK/unsigned.apk" \
  -I "$ANDROID_HOME/platforms/android-36/android.jar" \
  --manifest "$WORK/AndroidManifest.xml"
"$ZIPALIGN" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
umask 077
keytool -genkeypair -keystore "$WORK/throwaway.jks" -storepass throwaway-proof \
  -keypass throwaway-proof -alias throwaway -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=updater-proof-throwaway" >/dev/null 2>&1
"$APKSIGNER" sign --ks "$WORK/throwaway.jks" --ks-pass pass:throwaway-proof \
  --ks-key-alias throwaway --out "$OUT/c.apk" "$WORK/aligned.apk"

B_SHA=$(sha256sum "$OUT/b.apk" | cut -d' ' -f1)
C_SHA=$(sha256sum "$OUT/c.apk" | cut -d' ' -f1)
echo "real b.apk sha256=$B_SHA"
echo "c.apk sha256=$C_SHA"

write_update_manifest() { # $1 = name, $2 = vc, $3 = apk file, $4 = sha
  cat > "$OUT/roidgram-update-$1.json" <<EOF
{"versionCode": $2, "versionName": "updater-proof-fixture", "apkUrl": "http://127.0.0.1:8799/$3", "sha256": "$4", "notes": "A-to-B updater proof fixture"}
EOF
  cat > "$OUT/release-$1.json" <<EOF
{"tag_name": "updater-proof", "assets": [{"name": "roidgram-update.json", "browser_download_url": "http://127.0.0.1:8799/roidgram-update-$1.json"}]}
EOF
}

write_update_manifest good    "$B_VC" b.apk "$B_SHA"
write_update_manifest badsha  "$B_VC" b.apk "0000000000000000000000000000000000000000000000000000000000000000"
write_update_manifest wrongsig "$B_VC" c.apk "$C_SHA"
write_update_manifest old     "$A_VC" b.apk "$B_SHA"

echo "fixture server ok" > "$OUT/health.txt"
ls -la "$OUT"
