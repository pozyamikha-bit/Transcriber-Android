#!/bin/bash
set -x
mkdir -p ci
adb shell getprop ro.product.cpu.abilist > ci/abilist.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk > ci/install.txt 2>&1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> ci/install.txt 2>&1
adb logcat -c
adb shell am instrument -w -r com.transcriber.app.test/androidx.test.runner.AndroidJUnitRunner > ci/instrument.txt 2>&1
mkdir -p ci/files
adb exec-out run-as com.transcriber.app tar c -C files ci > ci/files.tar
tar xf ci/files.tar -C ci/files --strip-components=1
# скриншоты экранов
adb shell am start -n com.transcriber.app/.MainActivity; sleep 6
adb exec-out screencap -p > ci/screen_jobs.png
AID=$(cat ci/files/audio_job.txt 2>/dev/null)
VID=$(cat ci/files/video_job.txt 2>/dev/null)
adb shell am start -n com.transcriber.app/.MainActivity --es job "$VID"; sleep 4
adb exec-out screencap -p > ci/screen_video_job.png
adb shell am start -n com.transcriber.app/.MainActivity --es job "$AID"; sleep 4
adb exec-out screencap -p > ci/screen_audio_job.png
adb shell am start -n com.transcriber.app/.MainActivity --es doc "$VID"; sleep 5
adb exec-out screencap -p > ci/screen_doc.png
adb shell am start -n com.transcriber.app/.MainActivity --es settings 1; sleep 4
adb exec-out screencap -p > ci/screen_settings.png
adb logcat -d -t 3000 > ci/logcat.txt
grep -E "FAILURES|OK \(" ci/instrument.txt || true
exit 0
