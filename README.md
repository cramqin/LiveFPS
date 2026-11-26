# LiveFPS

Tiny root app that shows the **real on-screen FPS** both in-app and in a floating box, like the "Show refresh rate" developer option. On LTPO/VRR panels it drops to 1–3 fps at idle and rises when content changes.

## Features
- **In-App Real-Time Display**: Live bold digital FPS readout right inside the app.
- **Floating Overlay**: Draggable, translucent overlay that displays above all other apps and games.
- **Modern Dark Mode**: Sleek dark aesthetic designed for OLED/AMOLED screens.
- **Configurable Polling Rate**: Switch between 250ms (4 Hz), 500ms (2 Hz), 1.0s (1 Hz), and 2.0s (0.5 Hz) sampling intervals.
- **Battery Saver**: Automatically pauses polling, halts CPU wakeups, and disables SurfaceFlinger stats whenever the screen turns off.

## How it works
- Reads SurfaceFlinger TimeStats through `su`:
  `dumpsys SurfaceFlinger --timestats -dump` → `totalFrames`, then `-clear`.
- $\text{FPS} = \text{frames presented} \div \text{seconds since last read}$.
- TimeStats is enabled on start and disabled again on stop or when screen is turned off.

## Requirements
- Root (KernelSU / Magisk / APatch) — grant `su` to LiveFPS.
- "Display over other apps" permission (for floating overlay).
- Android 11+ (minSdk 30).

## Use
Open LiveFPS → choose your polling rate → tap **Start FPS Overlay**. Drag the box to move it. Stop from the app or the notification.

## Build
- CI: every push to `main` runs `.github/workflows/debug.yml` and uploads `LiveFPS-debug` (debug APK) as a workflow artifact.
- Local: `gradle assembleDebug` (Gradle 8.11, JDK 17, Android SDK 35).
