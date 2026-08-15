# miro

Accessibility service app for the OLAX Magic Q1 tablet (Android 12, API 31).

## Target device

| Property | Value |
|----------|-------|
| Manufacturer | OLAX |
| Model | Magic Q1 |
| Android | 12 (API 31) |
| Architecture | ARMv7 32-bit (armeabi-v7a) |
| RAM | 2 GB (low_ram=true) |
| Screen | 1024x600, density 160 |
| Root | No (build type: user) |

## AccessibilityService capabilities (API 31)

- `performGlobalAction()`: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG, TOGGLE_SPLIT_SCREEN, LOCK_SCREEN, TAKE_SCREENSHOT
- `dispatchGesture()`: simulated taps and gestures
- `getRootInActiveWindow()`: read window tree and content
- `ScreenshotResult`: real screenshot capture

## Limitations (no root, build user)

- Cannot enable ADB WiFi programmatically (requires WRITE_SECURE_SETTINGS)
- Cannot auto-enable the accessibility service — user must enable it once in Settings
- Auto-starts on boot after first screen unlock

## Build

```bash
./gradlew assembleRelease
# APK output: app/build/outputs/apk/release/app-release.apk
```

CI runs on every push (`.github/workflows/build.yml`).
