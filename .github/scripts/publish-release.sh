#!/bin/bash
# Publishes a GitHub release for a gated main-head APK. Safe by construction:
# - release is created as a DRAFT, all assets uploaded, then published atomically,
#   so /releases/latest never points at a release whose roidgram-update.json is missing
#   (which would mask the previous valid update);
# - existing published tag = clean skip; existing draft = abort (no deletion/overwrite
#   ever; cleanup of a stale draft is a deliberate human decision);
# - every check run on the head SHA must be completed+successful, and this workflow's
#   own "build" job MUST be present and successful (no vacuous pass);
# - versionCode (read from the APK) must be strictly newer than the previous manifest;
# - the signing certificate must equal the stable CI key.
#
# Fixture mode for tests: ROIDGRAM_FIXTURE_DIR set => no network; external responses are read
# from files in that directory and mutations appended to mutations.log there.
set -euo pipefail
APK="${1:?usage: publish-release.sh PATH-TO-APK}"
test -s "$APK"
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
AAPT="${BT}aapt"
APKSIGNER="${BT}apksigner"
FIX="${ROIDGRAM_FIXTURE_DIR:-}"

api() { # api <name> <real-command...>
  local name="$1"; shift
  if [ -n "$FIX" ]; then cat "$FIX/$name" 2>/dev/null || return 1; else "$@"; fi
}
mutate() { # mutate <action> <args...>
  if [ -n "$FIX" ]; then echo "$*" >> "$FIX/mutations.log"; else "$@"; fi
}

VC=$("$AAPT" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
VN=$("$AAPT" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
test -n "$VC" && test -n "$VN"
TAG="v$VN"
ASSET="Roidgram-$VN.apk"
echo "head=${GITHUB_SHA:-?} tag=$TAG versionCode=$VC"

# Gate 1: signature must be the stable CI key.
CERT=$("$APKSIGNER" verify --print-certs "$APK" | sed -n "s/Signer #1 certificate SHA-256 digest: //p")
echo "cert=$CERT"
[ "$CERT" = "$EXPECTED_CERT_SHA256" ] || { echo "PUBLISH ABORTED: unexpected signing certificate"; exit 1; }

# Gate 2: complete check inventory on this exact head. The build job's check run MUST
# exist and be green (no vacuous pass); every check run except this publish job must be
# completed with an acceptable conclusion.
BUILD_CHECK_NAME="${BUILD_CHECK_NAME:-build}"
CHECKS=$(api check-runs.json gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/check-runs")
echo "$CHECKS" | jq -e --arg bn "$BUILD_CHECK_NAME" '[.check_runs[] | select(.name == $bn and .status == "completed" and .conclusion == "success")] | length >= 1' >/dev/null \
  || { echo "PUBLISH ABORTED: successful build check run not found on $GITHUB_SHA"; exit 1; }
BAD=$(echo "$CHECKS" | jq '[.check_runs[] | select(.name != "Publish release")
  | select(.status != "completed" or ((.conclusion == "success" or .conclusion == "neutral" or .conclusion == "skipped") | not))] | length')
[ "$BAD" = "0" ] || { echo "PUBLISH ABORTED: $BAD incomplete/unsuccessful check runs on $GITHUB_SHA"; exit 1; }

# Gate 3: tag state. Published tag => skip (nothing to publish). Draft => abort, never delete.
REL=$(api release-view.json gh release view "$TAG" --repo "$GITHUB_REPOSITORY" --json isDraft 2>/dev/null || echo '')
if [ -n "$REL" ]; then
  IS_DRAFT=$(echo "$REL" | jq -r '.isDraft // false')
  if [ "$IS_DRAFT" = "true" ]; then
    echo "PUBLISH ABORTED: stale draft release $TAG exists - refusing to delete or overwrite; clean up deliberately."
    exit 1
  fi
  echo "::notice::Release $TAG already published; versionName unchanged - nothing to publish."
  exit 0
fi

# Gate 4: monotonic versionCode vs the previous release manifest.
PREV=0
PREV_URL=$(api latest-release.json gh api "repos/$GITHUB_REPOSITORY/releases/latest" \
  --jq '.assets[] | select(.name == "roidgram-update.json") | .browser_download_url' 2>/dev/null || true)
if [ -n "$PREV_URL" ]; then
  PREV=$(api prev-manifest.json curl -fsSL "$PREV_URL" | jq -r '.versionCode')
fi
echo "previous manifest versionCode=$PREV new=$VC"
[ "$VC" -gt "$PREV" ] || { echo "PUBLISH ABORTED: versionCode $VC not newer than published $PREV"; exit 1; }

# Publish: draft, upload both assets, publish atomically.
cp "$APK" "$ASSET"
SHA=$(sha256sum "$ASSET" | awk '{print $1}')
mutate gh release create "$TAG" --draft --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" \
  --title "$TAG" --notes "Automatic release $TAG (versionCode $VC) after the full gate on $GITHUB_SHA."
mutate gh release upload "$TAG" "$ASSET" --repo "$GITHUB_REPOSITORY"
APK_URL="https://github.com/$GITHUB_REPOSITORY/releases/download/$TAG/$ASSET"
jq -n --argjson vc "$VC" --arg vn "$VN" --arg url "$APK_URL" --arg sha "$SHA" \
  '{versionCode: $vc, versionName: $vn, apkUrl: $url, sha256: $sha}' > roidgram-update.json
mutate gh release upload "$TAG" roidgram-update.json --repo "$GITHUB_REPOSITORY"
mutate gh release edit "$TAG" --draft=false --repo "$GITHUB_REPOSITORY"
echo "PUBLISHED $TAG (versionCode $VC) with $ASSET + roidgram-update.json"
