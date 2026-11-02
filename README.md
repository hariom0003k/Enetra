# PeepGuard 🛡️

**Privacy protection for your Android screen.** PeepGuard uses your phone's front camera and on-device AI to detect when someone is peeking at your screen. When detected, it instantly blurs the display and alerts you.

## Features

- 🔍 **Real-time face detection** using Google ML Kit (100% on-device, no internet needed)
- 🔒 **Instant screen blur** when an unauthorized viewer is detected
- 📱 **Per-app protection** — choose exactly which apps to guard
- 🔕 **Smart suppression** — mark viewers as Friend or Ignore with a custom timer
- 🔋 **Battery efficient** — ~2-3 FPS analysis, stops camera when not needed
- 🔐 **Privacy first** — no data leaves your device

## Setup (3 Steps)

1. **Grant Permissions** — Camera + Notifications
2. **Allow Draw Over Apps** — Required for the privacy overlay
3. **Enable Accessibility Service** — Required to detect which app is on screen

Then select which apps to protect and tap **Start Protection**.

## Auto-Build APK via GitHub Actions

Every time you push or trigger manually via GitHub Actions, an installable APK (`PeepGuard-debug-apk`) is generated and available under the **Actions** -> **Artifacts** tab.

