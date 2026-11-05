#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
# Roidgram setup-gate smoke: fixture credentials only (dummy values, no real login).
set -e
PKG=fork.risin42.nagramx
adb install -r /tmp/roidgram.apk
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 25
adb exec-out screencap -p > /tmp/setup-screen.png
[ -s /tmp/setup-screen.png ] || { echo "no setup screenshot captured"; exit 1; }
python3 "$DIR/contrast.py" /tmp/setup-screen.png

tap_on() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 "$DIR/tap.py" /tmp/ui.xml "$1"
}

tap_on_edit() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 "$DIR/tap.py" /tmp/ui.xml "$1" edit
}

# The 320x640 emulator screen puts later fields and the save button under the
# soft keyboard once typing starts, so coordinate taps land on the keyboard.
# Focus navigation is used instead: one coordinate tap for the first field,
# then KEYCODE_TAB between nodes, verifying the focused node after each move.
FIELD_KB=0

focus_node() {
  local hint="$1"
  local real_attempts=0
  # TAB moves focus downward but does not wrap past the last focusable, so
  # navigation runs downward first, then upward with KEYCODE_NAVIGATE_PREVIOUS.
  local dir_keys="KEYCODE_TAB KEYCODE_TAB KEYCODE_TAB KEYCODE_NAVIGATE_PREVIOUS KEYCODE_NAVIGATE_PREVIOUS KEYCODE_NAVIGATE_PREVIOUS KEYCODE_NAVIGATE_PREVIOUS"
  for key in $dir_keys; do
    if [ "$real_attempts" -eq 0 ] && [ "$FIELD_KB" = "0" ]; then
      # Coordinate tap is only safe before the IME is open; afterwards the
      # keyboard covers later fields and a tap types into the focused field.
      tap_on_edit "$hint" || tap_on "$hint" || true
      real_attempts=1
    else
      adb shell input keyevent $key
    fi
    sleep 1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    # System ANR dialogs (e.g. "Pixel Launcher isn't responding" on this small
    # emulator) cover the app and eat taps: dismiss with "Wait" and retry
    # without spending a move.
    if grep -q "isn't responding" /tmp/ui.xml; then
      XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "wait") && adb shell input tap $XY
      sleep 2
      continue
    fi
    if python3 "$DIR/focuscheck.py" /tmp/ui.xml "$hint"; then return 0; fi
  done
  adb exec-out screencap -p > /tmp/field-fail.png || true
  cp /tmp/ui.xml /tmp/field-fail.xml || true
  echo "could not focus '$hint'"
  exit 1
}

set_field() {
  local hint="$1" value="$2"
  for attempt in 1 2 3; do
    focus_node "$hint"
    adb shell input keyevent KEYCODE_MOVE_END
    for i in $(seq 64); do adb shell input keyevent KEYCODE_DEL; done
    adb shell input text "$value"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if python3 "$DIR/fieldcheck.py" /tmp/ui.xml "$value"; then FIELD_KB=1; return 0; fi
    echo "field '$hint' did not hold the intended value after attempt $attempt"
  done
  adb exec-out screencap -p > /tmp/field-fail.png || true
  cp /tmp/ui.xml /tmp/field-fail.xml || true
  echo "could not set field '$hint' deterministically"
  exit 1
}

press_button() {
  # No coordinate tap here: with the IME open the button is underneath it and a
  # tap lands on a keyboard key, typing into the focused field. TAB-navigate
  # from the current focus and ENTER the focused button instead.
  for attempt in $(seq 1 8); do
    adb shell input keyevent KEYCODE_TAB
    sleep 1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if grep -q "isn't responding" /tmp/ui.xml; then
      XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "wait") && adb shell input tap $XY
      sleep 2
      continue
    fi
    if python3 "$DIR/focuscheck.py" /tmp/ui.xml "$1"; then
      adb shell input keyevent KEYCODE_ENTER
      sleep 1
      adb shell uiautomator dump /sdcard/ui.xml >/dev/null
      adb pull /sdcard/ui.xml /tmp/ui-press.xml >/dev/null 2>&1 || true
      return 0
    fi
  done
  adb exec-out screencap -p > /tmp/field-fail.png || true
  cp /tmp/ui.xml /tmp/field-fail.xml || true
  echo "could not focus button '$1'"
  exit 1
}

echo "== negative validation: bad hash rejected =="
set_field "api_id" "123456"
set_field "api_hash" "xyz"
press_button "save and continue"
sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/ui-invalid.xml >/dev/null 2>&1
grep -q "32 hexadecimal" /tmp/ui-invalid.xml || { echo "validation error text not shown"; exit 1; }
python3 "$DIR/fieldcheck.py" /tmp/ui-invalid.xml "123456" || { adb exec-out screencap -p > /tmp/field-fail.png || true; cp /tmp/ui-invalid.xml /tmp/field-fail.xml; echo "api_id field lost its value during negative test"; exit 1; }
python3 "$DIR/fieldcheck.py" /tmp/ui-invalid.xml "xyz" || { echo "bad hash never reached the hash field - negative test vacuous"; exit 1; }
RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
echo "$RESUMED"
echo "$RESUMED" | grep -q "ApiSetupActivity" || { echo "setup not still in foreground after rejected input"; exit 1; }
adb exec-out screencap -p > /tmp/invalid.png

echo "== save valid-format dummy fixture -> restart -> gate must pass =="
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 15
set_field "api_id" "123456"

echo "== touch-access probe: hash field reachable with keyboard open (tap, then scroll+tap) =="
# Capture the actual IME-open state FIRST: screenshot + IME window bounds, so the
# covered/uncovered claim rests on pixels and insets taken at the moment, not on
# an end-of-probe screenshot after the keyboard may have been dismissed.
adb exec-out screencap -p > /tmp/ime-open.png || true
{
  echo "== dumpsys window: IME / input method windows =="
  adb shell dumpsys window | grep -iE 'InputMethod|ime' | head -20
  echo "== dumpsys input_method: bounds =="
  adb shell dumpsys input_method | grep -iE 'mCurRootView|bound|frame|visible' | head -20
  echo "== focused node + field bounds =="
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - <<'PYEOF'
import xml.etree.ElementTree as ET
for n in ET.parse('/tmp/ui.xml').iter('node'):
    t = (n.get('text') or '').strip()
    cls = (n.get('class') or '').split('.')[-1]
    if t or 'EditText' in cls or 'Button' in cls or n.get('focused') == 'true':
        print(f"{cls:12} focused={n.get('focused')} bounds={n.get('bounds')} {t[:40]!r}")
PYEOF
} > /tmp/ime-state.txt 2>&1 || true
# With the IME open after typing api_id, check whether ordinary touch can reach
# the api_hash field at 320x640: direct coordinate tap first, then a scroll
# swipe in the content area above the keyboard and a second tap. Result is
# reported either way; failure captures pixels and the dump for layout fixes.
touch_access="inaccessible"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "api_hash"); then
  adb shell input tap $XY
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  if python3 "$DIR/focuscheck.py" /tmp/ui.xml "api_hash"; then
    touch_access="direct-tap"
  else
    adb shell input swipe 160 350 160 250 300
    sleep 1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "api_hash"); then
      adb shell input tap $XY
      sleep 1
      adb shell uiautomator dump /sdcard/ui.xml >/dev/null
      adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
      if python3 "$DIR/focuscheck.py" /tmp/ui.xml "api_hash"; then
        touch_access="scroll-then-tap"
      fi
    fi
  fi
fi
echo "touch access to api_hash with IME open: $touch_access"
if [ "$touch_access" = "inaccessible" ]; then
  adb exec-out screencap -p > /tmp/touch-access-fail.png || true
  cp /tmp/ui.xml /tmp/touch-access-fail.xml || true
fi

# The probe's coordinate taps may have typed into the focused field via the IME;
# re-enter both fields deterministically (TAB-only now that the IME is open).
set_field "api_id" "123456"
set_field "api_hash" "0123456789abcdef0123456789abcdef"
press_button "save and continue"
sleep 18
RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
echo "$RESUMED"
if echo "$RESUMED" | grep -q "ApiSetupActivity"; then
  # Evidence on failure: the re-shown setup screen discriminates the cause.
  # Prefilled api_id => custom_app_id survived (keystore/decrypt side failed).
  # Empty fields + no error => persistence lost (apply()+exit race) or save tap missed.
  # Error text present => validation rejected the fixture input.
  adb exec-out screencap -p > /tmp/after-save-fail.png || true
  adb shell uiautomator dump /sdcard/ui-fail.xml >/dev/null
  adb pull /sdcard/ui-fail.xml /tmp/after-save-fail.xml >/dev/null 2>&1 || true
  adb logcat -d > /tmp/logcat.txt || true
  echo "setup shown again after credentials saved - gate failed"
  exit 1
fi
# The gate passed. In this fork the whole login flow is fragments INSIDE
# LaunchActivity (LaunchActivity.java:1207-1210: new LoginActivity()/IntroActivity()
# via presentFragment), so ResumedActivity stays LaunchActivity and an activity-name
# assertion can never pass. Assert screen CONTENT instead: the phone-entry slide
# header "Your Phone" (LoginActivity.java:3584, R.string.YourPhone). Walk the real
# path: intro "Start Messaging" -> permission prompt "Continue" -> system
# permission dialog, denied ("Don't allow" - manual entry still works).
# Terminal assertion = header identity AND a visible, enabled, editable phone
# field (phoneField carries content-desc "Phone number", LoginActivity.java:2394)
# - matching the header text alone could pass on a hidden or partial render.
phone_slide_ready() {
  python3 - /tmp/ui.xml <<'PYCHECK'
import re, sys, xml.etree.ElementTree as ET
header = False
field = False
for n in ET.parse(sys.argv[1]).iter('node'):
    if (n.get('text') or '').strip() == 'Your Phone':
        header = True
    cls = n.get('class') or ''
    if (n.get('content-desc') or '').strip() == 'Phone number' and 'EditText' in cls and n.get('enabled') == 'true':
        nums = list(map(int, re.findall(r'\d+', n.get('bounds') or '')))
        if len(nums) == 4 and nums[2] - nums[0] > 0 and nums[3] - nums[1] > 0:
            field = True
sys.exit(0 if (header and field) else 1)
PYCHECK
}

LOGIN_OK=0
for i in $(seq 1 15); do
  RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
  echo "$RESUMED"
  if echo "$RESUMED" | grep -q "ApiSetupActivity"; then
    adb exec-out screencap -p > /tmp/after-save-fail.png || true
    adb shell uiautomator dump /sdcard/ui-fail.xml >/dev/null
    adb pull /sdcard/ui-fail.xml /tmp/after-save-fail.xml >/dev/null 2>&1 || true
    adb logcat -d > /tmp/logcat.txt || true
    echo "setup shown again after credentials saved - gate failed"
    exit 1
  fi
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  if phone_slide_ready; then LOGIN_OK=1; break; fi
  if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "Start Messaging"); then
    adb shell input tap $XY
    sleep 2
    continue
  fi
  if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "Continue"); then
    adb shell input tap $XY
    sleep 2
    continue
  fi
  # System permission dialog: deny by stable resource-id (locale/punctuation
  # proof), text match as fallback. Denial is fixture-only; manual phone entry
  # still works (LoginActivity.onRequestPermissionsResultFragment).
  if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "permissioncontroller:id/permission_deny_button" || python3 "$DIR/boundsget.py" /tmp/ui.xml "don't allow"); then
    adb shell input tap $XY
    sleep 2
    continue
  fi
  sleep 2
done
if [ "$LOGIN_OK" != "1" ]; then
  # Same evidence standard as the setup-reshown branch: only pixels + dump +
  # logcat discriminate a stuck onboarding from a real gate regression.
  adb exec-out screencap -p > /tmp/after-save-fail.png || true
  adb shell uiautomator dump /sdcard/ui-fail.xml >/dev/null
  adb pull /sdcard/ui-fail.xml /tmp/after-save-fail.xml >/dev/null 2>&1 || true
  adb logcat -d > /tmp/logcat.txt || true
  echo "expected LoginActivity after gate passed"
  exit 1
fi
adb exec-out screencap -p > /tmp/after-save.png
[ -s /tmp/after-save.png ] || { echo "no after-save screenshot"; exit 1; }

adb logcat -d > /tmp/logcat.txt || true
if grep -q "FATAL EXCEPTION" /tmp/logcat.txt; then echo "App crashed"; exit 1; fi
echo "OK: setup validation, save/restart gate, and no crash (fixture credentials, no login request made)"
