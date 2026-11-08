#!/usr/bin/env bash
# Builds the A->B updater proof fixtures for Roidgram:
#   b.apk  - minimal valid APK, package app.roidgram, versionCode = A+1, signed with
#            the SAME stable key (the legitimate update)
#   c.apk  - same, but signed with a throwaway key (wrong-signer rejection case)
#   roidgram-update-{good,badsha,wrongsig,old}.json - release manifests
#   release-{good,badsha,wrongsig,old}.json         - GitHub-release-shaped wrappers
# Env in: APP_APK KS KS_PASS KS_ALIAS OUT ANDROID_HOME
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
[ -n "$A_VC" ] || { echo "could not read versionCode from $APP_APK"; exit 1; }
B_VC=$((A_VC + 1))
echo "installed A versionCode=$A_VC -> fixture B versionCode=$B_VC"
echo "$B_VC" > "$OUT/b-versioncode.txt"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

make_manifest() { # $1 = target xml path, $2 = versionCode
  cat > "$1" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="app.roidgram"
    android:versionCode="$2"
    android:versionName="updater-proof-fixture">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
    <application android:label="RoidgramFixture"/>
</manifest>
EOF
}

build_apk() { # $1 = out apk, $2 = versionCode, $3 = keystore, $4 = store pass, $5 = alias
  make_manifest "$WORK/AndroidManifest.xml" "$2"
  "$AAPT2" link -o "$WORK/unsigned.apk" \
    -I "$ANDROID_HOME/platforms/android-36/android.jar" \
    --manifest "$WORK/AndroidManifest.xml"
  "$ZIPALIGN" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
  "$APKSIGNER" sign --ks "$3" --ks-pass "pass:$4" --ks-key-alias "$5" \
    --out "$1" "$WORK/aligned.apk"
}

build_apk "$OUT/b.apk" "$B_VC" "$KS" "$KS_PASS" "$KS_ALIAS"

# Throwaway key for the wrong-signer case; never leaves the runner.
umask 077
keytool -genkeypair -keystore "$WORK/throwaway.jks" -storepass throwaway-proof \
  -keypass throwaway-proof -alias throwaway -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=updater-proof-throwaway" >/dev/null 2>&1
build_apk "$OUT/c.apk" "$B_VC" "$WORK/throwaway.jks" "throwaway-proof" "throwaway"

B_SHA=$(sha256sum "$OUT/b.apk" | cut -d' ' -f1)
C_SHA=$(sha256sum "$OUT/c.apk" | cut -d' ' -f1)
echo "b.apk sha256=$B_SHA"
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
