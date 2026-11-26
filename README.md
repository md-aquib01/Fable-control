# Fable Control

An iOS-style control center for stock Android (built for Moto phones). Tiny (no libraries), fast, fully customisable.

## Install
1. Download `FableControl.apk` from this repo's **Releases** and install it.
2. Open **Fable Control** and turn on each item under **Setup**.
   - If Android says *Restricted setting*: App info → ⋮ → **Allow restricted settings**, then try again.
3. Swipe down from the top-right corner.

## Features
- Wi-Fi, Bluetooth, mobile data, airplane, hotspot, location (live status; tap for Android's quick pop-ups, long-press for full settings)
- Now playing card with artwork, play/pause/skip and output picker
- iOS-style brightness and volume sliders (long-press brightness = auto-brightness)
- Focus (Do Not Disturb) and next alarm
- 17 buttons you can add, remove and reorder: flashlight, dark theme, calculator, rotation lock, QR scan, battery saver, screenshot, camera, sound mode, keep awake, timer, identify song, lock screen, power menu, split screen, notifications, settings
- Blur, darkness, size, roundness, accent colour, trigger corner/edge, haptics, sections on/off

Built automatically by GitHub Actions on every push.

## How the toggles work
Android doesn't let apps switch mobile data, airplane mode, hotspot, location, battery saver, NFC, data saver, dark theme or other apps' tiles. Fable Control presses the matching tile in your phone's own Quick Settings for you (through its Accessibility permission), so those tiles must be present in your Quick Settings. No Shizuku, root or developer options needed.
