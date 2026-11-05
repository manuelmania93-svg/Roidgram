#!/bin/bash
# Coexistence check: the old-package NagramXF build and Roidgram must install
# side by side (distinct applicationIds, same debug key). Fails the job if
# either package is missing after both installs.
set -e
OLD_PKG=fork.risin42.nagramx
NEW_PKG=app.roidgram
adb install -r /tmp/old-package.apk
adb install -r /tmp/roidgram.apk
adb shell pm list packages | grep -E 'nagram|roidgram' | tee /tmp/coexist-packages.txt
adb shell pm list packages | grep -q "package:$OLD_PKG" || { echo "old package $OLD_PKG missing after installs"; exit 1; }
adb shell pm list packages | grep -q "package:$NEW_PKG" || { echo "new package $NEW_PKG missing after installs"; exit 1; }
echo "coexist OK: $OLD_PKG and $NEW_PKG both installed"
