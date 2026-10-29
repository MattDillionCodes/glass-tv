# Glass (source, private)

Source for Glass, the glass-style Android TV / Google TV launcher. The public page with the APK,
install guide and screenshots is a separate repo (`glass-launcher`); this one stays private.
User-facing docs live there, not here. Not open source: see [LICENSE](LICENSE).

## Build

Needs Android Studio (its bundled JDK) and the Android SDK. No dependencies beyond the SDK.

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./install.sh 192.168.1.50     # build, install on a TV over ADB, turn on remote buttons + sleep admin
```

Plain Kotlin and framework views, no AndroidX. minSdk 28 (Android 9), R8-shrunk.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`, then check it's signed with the release key:
   `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
   (SHA-256 `bead05ac…eedeadb873f7`).
3. Copy it to the public repo as `glass-<version>.apk` and attach it to a GitHub release there.

**The release key is not in this repo.** It lives in `~/.android-keys/` (`glass-release.jks` plus
`glass-release.properties` with its password); Gradle picks it up from there and falls back to the
debug key when it's missing. Back both files up somewhere safe: an APK signed with a different key
can't update an installed Glass, so losing the key means everyone has to uninstall and reinstall.

## Testing

On the `Glass_TV` emulator (Android TV, API 34, 1080p, port 5556), not a real TV. The stock
launcher must be disabled there for Glass to get Home. `adb shell input keyevent` presses skip
accessibility key filters, so remote-button learning needs real hardware presses: register a
virtual USB keyboard with `adb shell hid <json>` (HID usage 58 = F1).

## Layout

| File | What |
|---|---|
| `HomeActivity.kt` | Home: Top Shelf, dock, app grid, ambient clock, wallpaper rotation, weather |
| `Glass.kt` | Glass rendering: blurred backdrop, refraction, glass panels, tiles, Top Shelf blur |
| `ControlCenter.kt` | Volume, inputs, sleep timer, settings shortcuts |
| `SettingsActivity.kt` | Glass Settings: weather city, timers, wallpaper sets, button learning, permissions |
| `KeyRemapService.kt` | Accessibility key filter that runs the button map |
| `AmbientDream.kt` | The ambient clock as a system screensaver |
| `Prefs.kt` | Every user setting in one place |
| `Wallpapers.kt` | Built-in sets (`assets/wallpapers/`) and the user's own photos |
| `LiveData.kt` | Open-Meteo weather and city search |
| `Sleep.kt` | Sleep timer (device admin `lockNow`) |
