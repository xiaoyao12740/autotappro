# 🖱️ AutoTapPro

**A local Android auto-clicker that records tap / long-press / swipe sequences and replays them on schedule.**

[![License: Non-Commercial](https://img.shields.io/badge/License-Non--Commercial-red)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/Android-9%20%2B-3DDC84?logo=android&logoColor=white)]()
[![Language](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)]()
[![Network: none](https://img.shields.io/badge/Network-offline-lightgrey)]()

> ⚠️ **For personal & non-commercial use only.** See [`LICENSE`](./LICENSE). No network permission, no ads, fully local.

---

## ⬇️ Quick Download

> Don't want to read source code? Grab an APK from the [Releases page](https://github.com/xiaoyao12740/autotappro/releases/latest); for building from source, see the developer section at the bottom.

| Your device | Download | Notes |
|---|---|---|
| 📱 Android 9+ (recommended) | [AutoTapPro-Android-v1.0.1-release.apk](https://github.com/xiaoyao12740/autotappro/releases/download/v1.0.1/AutoTapPro-Android-v1.0.1-release.apk) | Release-signed build for daily use |
| 📱 Android 9+ (debug) | [AutoTapPro-Android-v1.0.1-debug.apk](https://github.com/xiaoyao12740/autotappro/releases/download/v1.0.1/AutoTapPro-Android-v1.0.1-debug.apk) | Debug-key signed, for troubleshooting |

- All assets, release notes and SHA-256 checksums are in the [v1.0.1 release](https://github.com/xiaoyao12740/autotappro/releases/tag/v1.0.1).
- Allow "install unknown apps" in system settings before installing; grant overlay, accessibility and battery whitelist on first launch.

---

## ✨ Features

### Capture & recording
- A full-screen transparent capture layer records one touch as **tap / long-press / swipe / double-tap**, with start/end coordinates and touch duration.
- Multi-point sequences, up to 100 steps per profile; per-point delay, touch duration and repeat count are configurable.
- Replayed synthetic gestures are excluded from recording, so a replay action is not recorded again.
- Multi-touch is paired per finger by pointer ID: two fingers pressed at the same time produce two separate points instead of a cross-screen swipe.

### Replay
- **Two channels**, persisted with the profile:
  - Accessibility: `AccessibilityService.dispatchGesture()`, no Root required, default channel;
  - Root shell: `su -c input tap / swipe`, optional on rooted devices;
  - Auto mode prefers accessibility and falls back to Root; forced modes fail explicitly instead of silently degrading.
- **Once / loop** run modes; pause, resume, and a standalone floating stop button.
- Naturalization toggles (all off by default): random delay, random coordinate offset, swipe duration jitter; when off, the original coordinates and timing are replayed exactly.
- Profiles can be saved, saved-as, loaded and deleted; stored as JSON in the app's private directory.

### Adaptability & robustness
- Screen size comes from `DisplayMetrics.getRealMetrics()`; view sizes and distance thresholds are scaled by `dp × density` across resolutions and densities.
- Re-measures and rebuilds overlay elements on rotation / size change; the service declares `configChanges`.
- Point delays are awaited in short interruptible slices; pause/stop responds within tens of milliseconds. With no working channel, start/stop buttons are greyed out with a red warning and no fake run starts.

## 🎨 UI

- **Main screen**: light card layout with three permission cards (overlay / accessibility / battery whitelist), each showing granted/not-granted and opening the system settings; below: channel status, run state, point count, profile list (load/delete), save / save-as.
- **Floating control bar**: semi-transparent dark slim bar as wide as a single button, collapsible to a knob; expanded row: start/pause `▶`, record `● / ■`, stop `⏹`, point visibility `◉ / ○`, add `＋`, delete `－`, close `✕`. Collapsed: tap starts/pauses, long-press expands; after drag it snaps to the nearest screen edge (toggleable on the main screen).
- **Point bubbles**: hollow circles with green stroke and light number; they can be hidden all at once so markers do not block manual operation. During run/pause they are non-touchable so gestures pass through to the target app; idle they are draggable and long-press editable.
- **Parameter dialog**: edit action type, start/end coordinates, delay, touch duration, repeat count, random offset and random delay.
- **App icon**: white background, green dashed sight ring with a crosshair.

## 🧱 Tech stack

- Kotlin 1.9.24, Gradle 8.10.2, AGP 8.5.2, JDK 17.
- compileSdk / targetSdk 34, minSdk 28 (Android 9+).
- Dependencies: AndroidX AppCompat, Material, ConstraintLayout, RecyclerView, Kotlinx Coroutines (Apache-2.0).
- No `INTERNET` permission; sequences stay in the private app directory.

## 🛠️ Build from source (developers)

Clone this repository and build with the toolchain below. End users do not need this section — download an APK from the top of the page.

Requires JDK 17, Android SDK 34, build-tools 34.0.0, Gradle 8.10.2.

```bash
gradle assembleDebug   # debug APK with the local debug key
gradle assembleRelease # release APK: provide keystore/credentials via gradle.properties or env (see app/build.gradle)
```

## 🔗 Credits & third-party notice

- Floating panel, point markers and dual-channel replay follow common interaction and architecture patterns for accessibility-based auto-clickers; the code here is written independently and bundles no proprietary third-party source.
- Runtime dependencies are the Apache-2.0 AndroidX / Kotlin libraries listed above; their licenses and source are governed by their own repositories.

## 🧭 Limits / roadmap

- The accessibility channel delivers only UI state events, so recording uses the overlay capture layer; evdev-based global touch recording via Root is planned.
- Points are dispatched in recorded order with recorded gaps; strict multi-finger simultaneous press is not reproduced yet.
- On screens whose accessibility tree is restricted, pixel-coordinate targeting is required; compatibility depends on the real device.

## ⚖️ License

Non-commercial open source, see [`LICENSE`](./LICENSE). Commercial use is prohibited. Provided AS IS with no warranty.
