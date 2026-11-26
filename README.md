# LiveFPS

Tiny root app that shows the **real on-screen FPS** in a floating box, like the
"Show refresh rate" developer option. On LTPO/VRR panels it drops to 1–3 fps at
idle and rises when content changes.

## How it works
- Reads SurfaceFlinger TimeStats through `su`:
  `dumpsys SurfaceFlinger --timestats -dump` → `totalFrames`, then `-clear`.
- FPS = frames presented ÷ seconds since last read (updated every 1 s).
- TimeStats is enabled on start and disabled again on stop.

## Requirements
- Root (KernelSU / Magisk) — grant su to LiveFPS.
- "Display over other apps" permission.
- Android 11+ (minSdk 30).

## Use
Open LiveFPS → **Start overlay**. Drag the box to move it. Stop from the app or
the notification.

## Build
- CI: every push to `main` runs `.github/workflows/debug.yml` and uploads
  `LiveFPS-debug` (debug APK) as a workflow artifact.
- Local: `gradle assembleDebug` (Gradle 8.11, JDK 17, Android SDK 35).
