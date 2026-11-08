#!/usr/bin/env bash
# Emulator-side driver for the Roidgram A->B updater proof. Invoked as ONE line by
# reactivecircus/android-emulator-runner (each script line is a separate sh -c there,
# so background jobs, traps and variables only survive inside a single script file).
# Env in: APP_APK APP_TEST_APK CONTROLLER_APP_APK CONTROLLER_TEST_APK FIXTURE_DIR EVIDENCE_DIR EXPECTED_CERT_SHA256
set -uo pipefail

EV="$EVIDENCE_DIR"
mkdir -p "$EV"
B_VC=$(cat "$FIXTURE_DIR/b-versioncode.txt")
echo "proof fixtures: B versionCode=$B_VC, expected cert=$EXPECTED_CERT_SHA256"

cleanup() {
  # Pull device evidence while the emulator is still alive (post-kill pulls get
  # "device offline"); sanitize every name (one ":" broke an atomic upload before).
  for f in $(timeout 20 adb -s emulator-5554 shell ls /data/local/tmp/ 2>/dev/null | tr -d '\r'); do
    case "$f" in *:*|*/*|"") continue;; esac
    case "$f" in roidgram-*.png)
      timeout 30 adb -s emulator-5554 exec-out cat "/data/local/tmp/$f" > "$EV/$f" 2>/dev/null || true
      ;;
    esac
  done
  timeout 60 adb -s emulator-5554 logcat -d > "$EV/logcat.txt" 2>/dev/null || true
  ls -la "$EV" || true
  # Console redundancy: the artifact upload validates filenames and fails atomically;
  # the run log always survives.
  echo '== post-run dumpsys =='
  timeout 30 adb -s emulator-5554 shell dumpsys package app.roidgram 2>/dev/null | grep -m3 'versionCode\|signatures' || true
  kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Fixture server on the host, bridged into the device by adb reverse.
timeout 30 adb -s emulator-5554 reverse tcp:8799 tcp:8799
python3 -m http.server 8799 --directory "$FIXTURE_DIR" > "$EV/fixture-server.log" 2>&1 &
SERVER_PID=$!
sleep 1
curl -sf http://127.0.0.1:8799/health.txt || { echo "fixture server failed to start"; exit 1; }

# Install: Roidgram A, its instrumentation test APK, and the controller test APK.
timeout 600 adb -s emulator-5554 install -r "$APP_APK" || { echo "app install failed"; exit 1; }
timeout 300 adb -s emulator-5554 install -r "$APP_TEST_APK" || { echo "app test install failed"; exit 1; }
timeout 300 adb -s emulator-5554 install -r "$CONTROLLER_APP_APK" || { echo "controller app install failed"; exit 1; }
timeout 300 adb -s emulator-5554 install -r "$CONTROLLER_TEST_APK" || { echo "controller test install failed"; exit 1; }
timeout 30 adb -s emulator-5554 shell dumpsys package app.roidgram | grep -m1 versionCode

# 1) In-app suite: fetch/parse, rejection cases, download, verify, grant flow, sheet.
echo '== app instrumentation suite =='
timeout 900 adb -s emulator-5554 shell am instrument -w -r \
  -e class tw.nekomimi.nekogram.update.GithubAppUpdateFlowTest \
  app.roidgram.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$EV/app-tests.log"
APP_RESULT=${PIPESTATUS[0]}
echo "app instrumentation exit=$APP_RESULT"

# 2) Controller: click the sheet, assert replacement + stable cert.
echo '== controller suite =='
timeout 600 adb -s emulator-5554 shell am instrument -w -r \
  -e class app.roidgram.updatercontroller.InstallControllerTest \
  -e expectedVersionCode "$B_VC" \
  -e expectedCertSha256 "$EXPECTED_CERT_SHA256" \
  app.roidgram.updatercontroller.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$EV/controller-tests.log"
CTRL_RESULT=${PIPESTATUS[0]}
echo "controller instrumentation exit=$CTRL_RESULT"

# 3) Host-side truth: dumpsys must show the real B build after replacement.
echo '== host dumpsys assertion =='
timeout 30 adb -s emulator-5554 shell dumpsys package app.roidgram | grep -m1 versionCode | tee "$EV/host-dumpsys.txt"
grep -q "versionCode=$B_VC " "$EV/host-dumpsys.txt" || { echo "HOST ASSERT FAILED: versionCode != $B_VC"; exit 1; }

[ "$APP_RESULT" -eq 0 ] || { echo "app suite failed"; exit 1; }
[ "$CTRL_RESULT" -eq 0 ] || { echo "controller suite failed"; exit 1; }

# 4) Post-replacement: data marker must survive and the real B build must launch.
# On API 35 the package manager can stay briefly wedged after the session install
# (run 42508708791: pm queries NameNotFound/hang right after dumpsys showed B).
# Wait for the package to resolve before instrumenting, and retry the suite once
# if the first attempt still times out.
echo '== post-replacement suite =='
for i in $(seq 1 30); do
  if timeout 10 adb -s emulator-5554 shell pm path app.roidgram 2>/dev/null | grep -q 'package:'; then
    echo "pm path ok after ${i} checks"; break
  fi
  sleep 2
done
timeout 10 adb -s emulator-5554 shell pm path app.roidgram | tee "$EV/pm-path-post.txt"
grep -q 'package:' "$EV/pm-path-post.txt" || { echo 'package never resolvable after replacement'; exit 1; }

timeout 300 adb -s emulator-5554 shell am instrument -w -r \
  -e class tw.nekomimi.nekogram.update.PostReplacementTest \
  -e expectedVersionCode "$B_VC" \
  app.roidgram.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$EV/post-tests.log"
POST_RESULT=${PIPESTATUS[0]}
if [ "$POST_RESULT" -eq 124 ]; then
  echo 'post-replacement instrumentation timed out; retrying once after pm settle'
  for i in $(seq 1 30); do
    if timeout 10 adb -s emulator-5554 shell pm path app.roidgram 2>/dev/null | grep -q 'package:'; then break; fi
    sleep 2
  done
  timeout 300 adb -s emulator-5554 shell am instrument -w -r \
    -e class tw.nekomimi.nekogram.update.PostReplacementTest \
    -e expectedVersionCode "$B_VC" \
    app.roidgram.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$EV/post-tests-retry.log"
  POST_RESULT=${PIPESTATUS[0]}
fi
echo "post-replacement instrumentation exit=$POST_RESULT"

# Real B launches: no crash on startup after the update (functionality smoke).
timeout 30 adb -s emulator-5554 shell monkey -p app.roidgram -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 25
timeout 30 adb -s emulator-5554 exec-out screencap -p > "$EV/roidgram-post-update-launch.png" 2>/dev/null || true
timeout 30 adb -s emulator-5554 logcat -d > "$EV/logcat-post-update.txt" 2>/dev/null || true
if grep -q 'FATAL EXCEPTION' "$EV/logcat-post-update.txt"; then echo 'real B crashed on launch after update'; exit 1; fi

[ "$POST_RESULT" -eq 0 ] || { echo "post-replacement suite failed"; exit 1; }
echo 'UPDATER PROOF PASSED'
