#!/usr/bin/env bash
# Runs inside the emulator job: installs the APK and takes screenshots of the interface with the
# built-in demo song (made-up lines, no player needed). Usage: android-screenshots.sh <apk dir> <out dir>
set -u
APK_DIR=$1
OUT=$2
PKG=tw.folia.controller
mkdir -p "$OUT"
exec > >(tee "$OUT/script.log") 2>&1

adb install -r "$APK_DIR"/*.apk
# Skip Android's one-time "Viewing full screen" hint, which would cover the screenshots.
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

launch() {
  adb shell am force-stop "$PKG"
  sleep 1
  adb shell am start -W -n "$PKG/.MainActivity" "$@"
  for _ in $(seq 1 30); do
    if adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG"; then return; fi
    sleep 1
  done
  echo "app is not in front:"; adb shell dumpsys activity activities | grep -i resumed | head -5
}
shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "saved $1.png"
}
size() {
  adb shell wm size | tail -1 | sed 's/.*: //'
}

# Phone
launch --ez demo true --ez tutorial true
sleep 20
shot phone-tutorial

launch --ez demo true
sleep 25
shot phone-card

WH=$(size); W=${WH%x*}; H=${WH#*x}
CX=$((W / 2)); CY=$((H * 2 / 5))
# Swipe left over the lyrics: "next" feedback bubble.
adb shell input swipe $((W * 3 / 4)) $CY $((W / 4)) $CY 120
sleep 0.2
shot phone-swipe
sleep 2
# A single tap hides the card.
adb shell input tap $CX $CY
sleep 1.5
shot phone-card-hidden

launch --ez demo true --ez tuning true --es mode classic
sleep 32
shot phone-tuning

# Flowrics effects (the card is tapped away so the lyrics show)
for fx in dive vinyl comic boom fireworks; do
  launch --ez demo true --es mode "fx:$fx"
  sleep 14
  adb shell input tap $CX $CY
  sleep 3
  shot "phone-fx-$fx"
done

# Tablet (landscape)
adb shell wm size 2560x1600
adb shell wm density 320
launch --ez demo true
sleep 25
shot tablet-card
launch --ez demo true --ez tutorial true
sleep 15
shot tablet-tutorial
launch --ez demo true --ez tuning true --es mode classic
sleep 34
shot tablet-tuning
launch --ez demo true --es mode fx:neon
sleep 16
shot tablet-fx-neon
adb shell wm size reset
adb shell wm density reset

adb logcat -d -b crash > "$OUT/crash.txt" 2>&1 || true
adb logcat -d | grep -E "FoliaController|fc-tuning|CONSOLE" > "$OUT/app-log.txt" 2>&1 || true
adb logcat -d -s ActivityTaskManager:* ActivityManager:* AndroidRuntime:* WindowManager:W > "$OUT/activity.txt" 2>&1 || true
adb logcat -d -s AndroidRuntime:E chromium:E > "$OUT/errors.txt" 2>&1 || true
echo "crash log lines: $(wc -l < "$OUT/crash.txt")"
