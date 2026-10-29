#!/bin/zsh
# Build Glass from source and install it on a TV over ADB (Wi-Fi debugging on).
# Usage: ./install.sh 192.168.1.50        (port 5555 assumed; or ip:port)
#        ./install.sh 192.168.1.50 --apk  (install the release APK in this folder instead of building)
# Also turns on what a fresh install leaves off: the remote-button service and the sleep-timer admin.
# Both can be turned on from Glass Settings instead; this just saves the clicks.
set -e
TV=${1:?usage: ./install.sh <tv-ip>[:port] [--apk]}
[[ $TV == *:* ]] || TV=$TV:5555
ADB=${ADB:-$(command -v adb || echo $HOME/Library/Android/sdk/platform-tools/adb)}
PKG=io.github.mattdillioncodes.glass
SERVICE=$PKG/$PKG.KeyRemapService

cd "${0:A:h}"
if [[ $2 == --apk ]]; then
  APK=$(ls -t glass-*.apk 2>/dev/null | head -1)
  [[ -n $APK ]] || { echo "no glass-*.apk next to this script"; exit 1; }
else
  [[ -d "/Applications/Android Studio.app" && -z $JAVA_HOME ]] &&
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ./gradlew assembleRelease --console=plain -q
  APK=app/build/outputs/apk/release/app-release.apk
fi

$ADB connect "$TV" >/dev/null
export ANDROID_SERIAL=$TV
$ADB install -r "$APK"
$ADB shell dpm set-active-admin --user 0 $PKG/.SleepAdmin >/dev/null 2>&1 || true

# Add Glass to the enabled accessibility services, keeping any others the TV already has.
current=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
[[ $current == null ]] && current=""
others=$(echo "$current" | tr ':' '\n' | grep -v "^$PKG/" | grep -v '^$' | paste -sd: - || true)
wanted=${others:+$others:}$SERVICE

# Binding right after an install fails silently on some TVs: start Glass first, then toggle
# accessibility off and on, retrying with longer waits until the service shows as bound.
$ADB shell am start -n $PKG/.HomeActivity >/dev/null
bound=no
for wait in 3 8 15; do
  sleep $wait
  $ADB shell settings put secure accessibility_enabled 0
  $ADB shell settings put secure enabled_accessibility_services "'$others'"
  sleep 2
  $ADB shell settings put secure enabled_accessibility_services "'$wanted'"
  $ADB shell settings put secure accessibility_enabled 1
  sleep 3
  if $ADB shell dumpsys accessibility | grep -q 'label=Glass remote buttons'; then bound=yes; break; fi
done
echo "remote buttons: $([[ $bound == yes ]] && echo on || echo 'off (turn on in Glass Settings)')"
echo "To make Glass the home screen: press Home on the remote and pick Glass,"
echo "or see the README for TVs that don't offer a choice."
