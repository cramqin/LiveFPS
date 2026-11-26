# LiveFPS

Tiny root & LSPosed app that shows the **real on-screen FPS** in-app, in a floating overlay, and directly inside the HyperOS/Android Status Bar. On LTPO/VRR panels it drops to 1–3 fps at idle and rises when content changes.

## Features
- **In-App Real-Time Display**: Live digital FPS readout right inside the app.
- **Quick Settings Tile**: One-tap toggle of the LiveFPS floating overlay directly from Android's Control Center / Quick Settings.
- **Adaptive Floating Overlay**: 
  - Draggable, translucent overlay with strict screen boundary clamping.
  - **Portrait**: Sticks right against the bottom edge of the status bar with zero gap/margin.
  - **Fullscreen / Landscape Rotation**: Can be placed at the very top ($y = 0$) when the status bar is hidden.
  - **Notification Shade Auto-Offset**: If the notification shade or status bar is swiped down over the overlay, it temporarily shifts downward to prevent overlap, and automatically snaps back to its exact saved position when the shade is dismissed.
- **Status Bar Integration (LSPosed)**: Injects live FPS directly into the status bar (Left near clock or Right near icons area) with a fixed 2.0s refresh rate (matching HyperCeiler) independent of the overlay polling rate.
- **Independent Controls**: Floating Overlay and Status Bar operate 100% independently—show both, either, or neither.
- **System-Privileged Overlay**: Hooks WindowManager in `system_server` so `setHideOverlayWindows(true)` in the Settings app and permission dialogs does not hide the overlay.
- **Configurable Polling Rate**: Switch between 250ms (4 Hz), 500ms (2 Hz), 1.0s (1 Hz), 2.0s (0.5 Hz), or enter a **custom interval in seconds** (e.g. 1.5s) for the in-app and overlay displays.
- **Battery Saver**: Automatically pauses polling, halts CPU wakeups, and disables SurfaceFlinger stats whenever the screen turns off (0% drain while locked).
- **Modern Dark Mode**: Sleek dark aesthetic designed for OLED/AMOLED screens.

## How it works
- **App & Overlay**: Reads SurfaceFlinger TimeStats through `su`:
  `dumpsys SurfaceFlinger --timestats -dump` → `totalFrames`, then `-clear`.
- **Status Bar Hook**: Injected into `com.android.systemui` via LSPosed. Implements modern LSPosed 2.x API 102 specifications (`META-INF/xposed/module.prop`, `java_init.list`, `scope.list`) alongside legacy `assets/xposed_init`. SystemUI runs as `android.uid.system` (UID 1000) with native Android `DUMP` permission, querying SurfaceFlinger TimeStats directly without `su` overhead.
- $\text{FPS} = \text{frames presented} \div \text{seconds since last read}$.

## Requirements
- Root (KernelSU / Magisk / APatch) — grant `su` to LiveFPS.
- LSPosed framework (optional, for Status Bar FPS and system-privileged overlay).
- "Display over other apps" permission (for floating overlay).
- Android 11+ (minSdk 30).

## Use
Open LiveFPS:
1. **Status Bar FPS**: Toggle ON/OFF and select position (Left near clock / Right near battery). Updates in real-time without restarting SystemUI.
2. **Floating Overlay**: Toggle ON/OFF to display the draggable on-screen box.
3. **Restart UI**: Use the `🔄 Restart UI` button in the header if you need to manually restart SystemUI.

## Build
- CI: `.github/workflows/debug.yml` builds `LiveFPS-debug` (debug APK) as a workflow artifact.
- Local: `gradle assembleDebug` (Gradle 8.11, JDK 17, Android SDK 35).
