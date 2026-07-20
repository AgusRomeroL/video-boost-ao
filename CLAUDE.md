# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin) that keeps **Video Boost** always on for Pixel Pro cameras **without root**. Pixel Camera deliberately resets the Video Boost toggle every session; there is no flag/intent/preference to pin it without root. The only viable approach is an `AccessibilityService` that re-enables it on every camera launch. This constraint is load-bearing — don't propose ADB/flag/root-based "fixes"; they were investigated and ruled out (Phenotype DB and `device_config` need root since Android 14; SharedPreferences of `com.google.android.GoogleCamera` are a private sandbox).

## Project-specific rules (important)

- **Do NOT add `Co-Authored-By: Claude` or any Claude/AI attribution to commits.** The user removed it from history once; `includeCoAuthoredBy` is off. Keep commit messages clean.
- **All GitHub-facing content (README, release notes, issue templates, commit messages) is in English.** In-app strings ship English + Spanish. Chat with the user is in Spanish.
- **`CameraLabels.kt` is generated — never hand-edit it.** Regenerate with `tools/gen-labels.ps1` (see below).
- **Don't rewrite the service activation logic casually** (`VideoBoostService.kt`). It's verified on-device and full of hard-won edge cases (see Architecture). Change selectors/labels, not the flow, unless a Feature Drop demands it.

## Build

Gradle wrapper is committed. Requires Android SDK (platform 36) + JDK 17 (Android Studio's JBR works). On this machine `--no-daemon` fails with transform lock errors — use the daemon.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug      # dev
./gradlew assembleRelease    # signed; needs keystore.properties (gitignored)
```

Release signing reads `keystore.properties` (repo root, gitignored) pointing to a keystore **outside** the repo (`G:\My Drive\Apps\keys\videoboostao-release.keystore`). Losing the keystore means no more signed updates — it must be preserved. There are no unit tests; verification is on-device (below).

## Release flow

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`, then `gh release create vX.Y <apk> --title ... --notes ...` (tag **must** be `vX.Y` — the in-app update checker parses `tag_name` and strips the leading `v`).
3. The app's `UpdateChecker` hits `releases/latest` on GitHub, so publishing a release is what notifies existing users; Obtainium also polls the repo.

## Architecture

**Two entry points, one shared preference.**

- `VideoBoostService` (AccessibilityService) — the engine. Runs on every UI event.
- `MainActivity` (Compose) — status/onboarding/toggle UI.
- `Prefs` — SharedPreferences (`vb_prefs`/`feature_enabled`). The master switch. The service's first guard is `if (!Prefs.featureEnabled(this)) return`, so pausing the feature never requires disabling accessibility.

**Service flow (`VideoBoostService.onAccessibilityEvent`)** — the non-obvious parts:

- **No `packageNames` filter in the config XML** — filtering is done in code. This is deliberate: the service needs events from *other* apps to detect that Pixel Camera left the foreground and reset its per-session state. A manifest/XML package filter breaks the reset and the whole thing stops re-triggering.
- **Throttle, not debounce**: the camera fires a burst of events while loading; debouncing would postpone the first action indefinitely.
- **Session reset is confirmed with a delay** (`confirmCameraGone` reading `rootInActiveWindow`) — the service's own BACK gesture emits SystemUI window events with the camera still visible, which would otherwise loop reactivation.
- **Idempotent**: it reads the toggle's state before acting and only turns Video Boost *on* if off — it never taps blindly and never turns it off.
- **Panel entry** uses the stable resource-id `options_entry_button` (`Selectors.kt`). A re-click cooldown (≥ panel inflate time) prevents closing the panel mid-animation; capped attempts/entry-clicks stop runaway loops.

**Localization + RTL (`CameraLabels.kt`, generated).** The service reads the system language and looks up localized labels (74 languages), English fallback. Two subtleties, both verified against the live UI:
- The Video Boost on/off control is a **segmented pair of ImageButtons**, not a Switch — state is `isSelected`, not `isChecked`.
- In RTL locales (ar/he/fa/ur) the buttons are **mirrored**. Primary strategy: match the on-button by its localized `contentDescription` (`sapphire_on_desc`, position-independent). Fallback: pick by layout direction (LTR→rightmost, RTL→leftmost) — needed because some locales expose empty `contentDescription`.

**`UpdateChecker`** — no dependencies (HttpURLConnection + org.json), fails silently offline, numeric segment version compare. `INTERNET` permission exists solely for this.

## Regenerating localized labels (after a Pixel Camera update)

`CameraLabels.kt` is extracted from the real Pixel Camera APK, not guessed:

```
adb shell pm path com.google.android.GoogleCamera        # locate base.apk
adb pull <base.apk> tools/gcam-base.apk
"<sdk>\build-tools\<ver>\aapt2" dump resources tools/gcam-base.apk > tools/gcam-resources.txt
powershell tools/gen-labels.ps1
```

Relevant resources: `sapphire_label` (row), `mode_video` (mode chip), `sapphire_on_desc` (on-button desc). "Sapphire" is Video Boost's internal codename. If a Feature Drop also changed the panel entry, re-anchor resource-ids in `Selectors.kt` via `adb exec-out uiautomator dump /dev/tty` with the camera in video mode and the Video Settings panel open.

## On-device testing (ADB)

Test device is a Pixel 10 Pro XL over **wireless debugging** (`adb` at `%LOCALAPPDATA%\Android\Sdk\platform-tools`, not on PATH). Quirks that will otherwise waste time:

- **Wireless debugging drops on reboot** and its port changes; the *pairing* persists (reconnect via `adb connect` / mDNS serial `adb-…_adb-tls-connect._tcp`). Ask the user for the new port after any reboot.
- **The screen must be unlocked** — `am start -a android.media.action.VIDEO_CAMERA` won't bring the camera to the foreground behind the keyguard, and the service sees nothing. Check `dumpsys window | grep isKeyguardShowing`.
- **After `install -r` / a fresh enable, events may not flow until a clean rebind**: `settings put secure accessibility_enabled 0` + empty the services string, then re-set both. Shell has `WRITE_SECURE_SETTINGS`, so enabling the service via `settings put secure enabled_accessibility_services <pkg>/<svc>` + `accessibility_enabled 1` works (the "Restricted setting" wall is only the Settings UI, not the provider).
- **Never `am force-stop` this app** — it unbinds the accessibility service (Android won't auto-rebind cleanly).
- **Per-app locale (`cmd locale set-app-locales`) does NOT reach the running service process**, and ADB can't change the effective system locale without root (`persist.sys.locale` is root-only). Faithful multi-language E2E requires the user to change the system language in Settings. Camera + data verification (labels extracted from the APK, checked against the live tree) is the practical substitute.
- Smoke test: `adb logcat -c` → force-stop camera → `am start -a android.media.action.VIDEO_CAMERA` → `adb logcat -d -s VideoBoostAO`. Expect "Abriendo panel" → "Activando Video Boost" → "Video Boost activado y verificado" in ~0.5 s.

## Distribution

Not on Google Play, on purpose (Play's accessibility policy prohibits automating another app's UI, with account-ban risk). Channels: GitHub Releases → Obtainium (auto-update) + IzzyOnDroid. License is MIT. See README "Distribution notes" and "Donations" (Ko-fi) for the rest.
