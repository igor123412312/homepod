#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 -m unittest discover -s tests -v
python3 prepare.py
cd _centuryplay
chmod +x ./gradlew
./gradlew --no-daemon assembleDebug testDebugUnitTest assembleDebugAndroidTest
cp app/build/outputs/apk/debug/app-debug.apk ../HomePod-Music-v0.6-debug.apk
echo "APK: HomePod-Music-v0.6-debug.apk"
