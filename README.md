# Video Boost AO (Always-On)

[![Latest release](https://img.shields.io/github/v/release/AgusRomeroL/video-boost-ao)](https://github.com/AgusRomeroL/video-boost-ao/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/agusromero)

**Keep Video Boost always on for your Pixel Pro — no root.**

Pixel Camera deliberately turns Video Boost off every time you close the app
(Google briefly made it persistent in Feb 2025 and then reverted it). There is
no flag, intent or preference that can pin it without root — so this app
re-enables it for you, automatically, every time you open the camera in video
mode.

An `AccessibilityService` detects Pixel Camera in the foreground, opens the
**Video Settings** panel, reads the actual state of the **Video Boost** toggle
and turns it on only if it is off (it never taps blindly — if it is already on
it does nothing). Then it closes the panel to leave the viewfinder exactly as
it found it. The whole sequence takes about half a second.

## Install

### Recommended: Obtainium (automatic updates)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases).
2. Add this app: **[one-tap link](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/AgusRomeroL/video-boost-ao)**
   — or in Obtainium: *Add App* → paste `https://github.com/AgusRomeroL/video-boost-ao`.
3. Obtainium installs the latest release and updates it automatically from now on.

### Manual

1. Download the APK from the [latest release](https://github.com/AgusRomeroL/video-boost-ao/releases/latest)
   and install it (allow "install unknown apps").

### Then, one-time setup (both methods)

2. Open *Video Boost AO* → the app guides you: tap **Open Accessibility
   settings** (your service entry comes up highlighted) and enable
   *Video Boost Always-On*.
3. If Android shows **"Restricted setting"** (normal for sideloaded apps on
   Android 13+): tap **Open App info** in the app → ⋮ menu (top right) →
   **"Allow restricted settings"** (confirm with fingerprint/PIN) → retry
   step 2.
4. Open Pixel Camera in video mode: within ~0.5 s Video Boost turns on by
   itself (sparkle icon at the top left of the viewfinder). The app also has a
   **Try it now** button.

The app includes a **master switch** to pause/resume the automation without
touching accessibility settings, and checks GitHub for **updates** on launch
(a card appears when a new version is available).

## Features

- Re-enables Video Boost on every camera session — the thing Pixel Camera
  refuses to remember.
- **74 languages**: the UI labels it looks for are not guessed — they are
  extracted directly from the real Pixel Camera APK
  ([`CameraLabels.kt`](app/src/main/java/com/agustin/videoboostao/CameraLabels.kt)).
- **RTL-aware**: in Arabic/Hebrew/Farsi/Urdu the on/off segmented buttons are
  mirrored; the service picks the correct one (verified against the live UI).
- Idempotent and safe: reads the toggle state before acting; never turns
  Video Boost off; gives up quietly after a few attempts if the UI changed.
- Material 3 UI (Compose) with dynamic color, guided onboarding, master
  switch, and an update checker.
- No root, no Shizuku, no analytics. `INTERNET` permission is used solely for
  the GitHub release check.

## Requirements

- A Pixel Pro model with Video Boost (Pixel 8 Pro and newer Pro models).
- Recent Pixel Camera (10.x line, Android 16).

## Heads-up: data & storage

Video Boost processes video in the cloud through Google Photos: each boosted
video is uploaded and stored twice, using data and storage. Always-on means
more of both.

## If it stops working (maintenance)

Pixel Camera Feature Drops can change texts, ids or view hierarchy.

- The panel-entry `resource-id` lives in
  [`Selectors.kt`](app/src/main/java/com/agustin/videoboostao/Selectors.kt);
  localized labels live in `CameraLabels.kt`.
- Re-anchor resource-ids with a live dump (camera in video mode, panel open):

  ```
  adb exec-out uiautomator dump /dev/tty
  ```

- Regenerate localized labels from the real camera APK with
  [`tools/gen-labels.ps1`](tools/gen-labels.ps1):

  ```
  adb shell pm path com.google.android.GoogleCamera   # locate base.apk
  adb pull <base.apk> tools/gcam-base.apk
  aapt2 dump resources tools/gcam-base.apk > tools/gcam-resources.txt
  powershell tools/gen-labels.ps1
  ```

  Relevant resources: `string/sapphire_label` (row label), `string/mode_video`
  (mode chip), `string/sapphire_on_desc` (on-button content description) —
  "Sapphire" is Video Boost's internal codename.

Debug logs: `adb logcat -s VideoBoostAO`

## Build

Android SDK (platform 36) + JDK 17 (Android Studio's JBR works):

```
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # signed release (needs keystore.properties)
```

Release signing reads `keystore.properties` (repo root, gitignored) pointing
to a keystore **outside** the repo:

```properties
storeFile=PATH\\TO\\your-release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

## Distribution notes

**Not on Google Play, on purpose.** This app uses an `AccessibilityService`
for a non-accessibility purpose (automating another app's UI), which Play's
policy requires declaring and effectively prohibits — with account-level
enforcement risk. Distribution is via GitHub Releases (this repo), Obtainium,
and F-Droid-compatible repos that ship the developer-signed APK.

## Support

If this app saves you a daily tap, you can support development:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/agusromero)

## License

[MIT](LICENSE) © 2026 Agustín Romero López
