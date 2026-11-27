# HyperFPS

A high-precision root & LSPosed real-time FPS monitor designed for Xiaomi HyperOS. Measures and presents **true on-screen refresh rate / presented frames** in-app, in an adaptive floating overlay, and directly injected into the HyperOS Status Bar. On LTPO / VRR displays, observe real-time downclocking to 1–3 fps at idle and immediate ramp-up during motion.

> [!IMPORTANT]
> **Verified Device & Compatibility:**
> HyperFPS is developed and strictly verified for:
> - **Device:** Xiaomi 17 (`pudding_global`, Model: `25113PN0EG`)
> - **OS:** Xiaomi HyperOS 4.0 (`OS4.0.3.0.XPCMIXM`, Android 17, Build: `CP2A.260605.016`)
> - **Root & Xposed:** KernelSU / APatch / Magisk with **LSPosed (libxposed API 102)**.
> - **Package Name:** `com.cramqin.hyperfps`
> Other Android ROMs or older HyperOS/MIUI builds are untested and may require adaptation.

---

## Features

### 1. Status Bar FPS (LSPosed SystemUI Hook)
- **Direct SystemUI Injection**: Uses native `NetworkSpeedView` components injected directly into PhoneStatusBarView containers:
  - **Left**: Adjacent to MiuiClock (`startSideContainer` / `clock.parent`).
  - **Right**: Adjacent to battery and system icons (`endSideContainer` / `IconManager.mGroup`).
- **Zero Conflict**: Does not hijack status bar icon lists, coexisting cleanly with HyperCeiler and custom status bar modules.
- **Custom Fonts & TTF**: Choose between System Default, Monospace, Sans-Serif, Serif, or load a **custom `.ttf` / `.otf` font** directly from device storage.
- **Full Typography & Alignment**: Adjust font size (sp), bold toggle, horizontal margins (dp), and vertical offset (dp) with live instant reload.
- **Dynamic Color Synchronization**: Automatically mirrors `MiuiClock` text color and alpha across light mode, dark mode, wallpaper palette shifts, and lockscreen states.
- **Fixed 2.0s Cadence**: Smooth, battery-friendly 2.0-second sampling cycle matching native system monitoring conventions.

### 2. Adaptive Floating Overlay
- **Draggable & Boundary-Clamped**: Full touch drag with elevation clamping:
  - **Portrait**: Sticks right against the status bar with zero gap/margin.
  - **Landscape / Fullscreen**: Max elevation sticks to the status bar when visible, or moves to the very top ($y = 0$) in full-screen games/video.
  - **Notification Shade Auto-Offset**: Automatically dips down when the status bar shade is pulled over the overlay, and snaps back when dismissed.
- **Rich Appearance Customization**:
  - **Font Styles & Custom Fonts**: Monospace, Sans-Serif, Serif, System Default, or load a **custom `.ttf` / `.otf` font** directly from storage.
  - **Font Weight**: Dedicated Bold / Normal toggle.
  - **Font Size**: Adjustable 9 sp – 24 sp.
  - **Color Palette**: Quick color swatches (Neon Green, Cyan, White, Gold, Red, Purple) or custom Hex input (`#RRGGBB` / `#AARRGGBB`).
  - **Background Styles**: Semi-Dark, Translucent Glass, Solid Dark, or None (Transparent with text drop shadow).
  - **Corner Radii & Shapes**: Capsule (Pill), Round (8dp), Soft (4dp), or Sharp (0dp).
  - **Padding**: Compact, Normal, or Relaxed.

### 3. System-Privileged Window Overlay
- Injected via `system_server` hook: exempts `com.cramqin.hyperfps` from `setHideOverlayWindows(true)`, allowing the floating FPS overlay to stay visible inside Android Settings, permission dialogs, and secure system surfaces.

### 4. Battery & Performance Optimization
- **Monotonic Timestats**: Samples SurfaceFlinger presentation timestamps without clearing or interfering with external monitors.
- **Deep Sleep Saver**: Completely halts CPU threads, polling timers, and dumpsys execution when the screen is turned off (0% standby drain).
- **Quick Settings Tile**: One-tap toggle to start or stop the overlay service from Android Control Center.

---

## How It Works

- **Engine**: SurfaceFlinger TimeStats delta sampling via root `dumpsys SurfaceFlinger --timestats`:
  $$\text{Presented FPS} = \frac{\Delta \text{totalFrames}}{\Delta \text{time (seconds)}}$$
- **Status Bar Integration**: Injected into `com.android.systemui` via LSPosed using libxposed API 102 specifications (`META-INF/xposed/module.prop`, `java_init.list`, `scope.list`). Because SystemUI runs as UID 1000 (`android.uid.system`) with native `DUMP` permission, status bar polling operates with zero root IPC overhead.

---

## Installation & Setup

1. Install `HyperFPS-v*.*.*.apk`.
2. Open **LSPosed Manager**:
   - Enable the **HyperFPS** module.
   - Recommended scope: **System UI** (`com.android.systemui`) and **System Framework** (`android`).
   - Reboot or tap `🔄 Restart UI` in the app header.
3. Open **HyperFPS**:
   - Grant Root access when prompted.
   - Grant "Display over other apps" permission for the floating overlay.
   - Configure your preferred Status Bar position, fonts, and overlay styling.

---

## Official Release & Upgrades

Official releases are signed with the project release keystore:
- **Keystore:** `hyperfps.jks` (Key Alias: `hyperfps`)
- **Seamless Updates**: Builds produced by GitHub Actions or local release scripts use this permanent keystore. You can install consecutive updates (e.g., `1.0.0` → `1.0.1` → `1.0.2`) seamlessly without Android signature mismatch errors.

### Release Workflow
Pushing a tag formatted as `v*.*.*` automatically triggers `.github/workflows/release.yml`:
```bash
git tag v1.0.0
git push origin v1.0.0
```
This builds and publishes a signed `HyperFPS-v1.0.0.apk` directly to GitHub Releases.
