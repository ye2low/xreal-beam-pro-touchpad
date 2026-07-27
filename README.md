# AR Touchpad

An Android app that turns your phone's touchscreen into a trackpad for controlling **Android desktop mode** on [Viture XR Pro](https://www.viture.com/) glasses (or any USB-C DisplayPort display running Android's extended desktop).

## How it works

The glasses connect via USB-C DisplayPort and appear as a second display in Android's desktop mode. This app creates a virtual mouse device using the Linux `uinput` subsystem (via [Shizuku](https://shizuku.rikka.app/)), so Android sees it as a real hardware mouse and shows a proper system cursor on the glasses display.

- **Single-finger drag** — moves the cursor
- **Tap** — left click
- **Double tap** — double click
- **Hold, then drag** — holds the left button down: this is what drags windows and resizes them
- **Two-finger drag** — scroll
- **Two-finger pinch** — zoom page content
- **Top bar** — Back, aimed at the display the cursor is on, and the phone's own task switcher

## Install

`release/ar-touchpad-3.0.apk` installs as-is. It carries the debug signature, and that is
deliberate: a differently signed build can only be installed over an uninstall, which wipes the
app's files — including the key it pairs with wireless debugging, so pairing would have to be
done again.

Shizuku does not have to be started by hand. The app connects to this device's own `adbd` over
loopback and runs Shizuku's starter itself. The six-digit pairing code is asked for once,
through a notification, and never again. Wireless debugging has to be on in developer options,
and note that it only comes up once the screen has been unlocked after a reboot.

## Requirements

| Requirement | Details |
|---|---|
| Android | 14+ (minSdk 34) |
| Architecture | arm64-v8a |
| [Shizuku](https://shizuku.rikka.app/) | Installed. The app starts it by itself — no computer needed. |
| Wireless debugging | On, in developer options. It is what the app talks to. |

Tested on **Pixel 10 + Viture XR Pro** running Android 16, and on **XREAL Beam Pro + XREAL One**
running Android 14.

## Setup

### 1. Install AR Touchpad
Install `release/ar-touchpad-3.0.apk`, or build it:

```bash
./gradlew assembleDebug
```

### 2. Start Shizuku
Open the app. If Shizuku is not running it says so, and starting it is one tap. The first time,
a notification asks for a pairing code: open **Developer options → Wireless debugging → Pair
device with pairing code** and type the six digits into that notification, leaving the system
dialog on screen. That happens once — from then on the app connects on its own.

### 3. Grant permission
Open the app → tap **Grant** when Shizuku asks.

The Accessibility Service is optional and currently unused; the touchpad works entirely through
the virtual mouse.

### 4. Connect the glasses
Plug in the Viture XR Pro. Pull down the notification shade and switch to **Desktop / Extended** mode (not Mirror). The app's status bar shows all detected displays — the glasses should appear as a second display.

## Architecture

```
app/
├── src/main/
│   ├── aidl/          IMouseService.aidl — Binder interface to Shizuku service
│   ├── cpp/           uinput_jni.cpp — JNI: open /dev/uinput, ioctl, write events
│   └── java/…/
│       ├── MouseService.kt              — Shizuku UserService (runs as shell uid)
│       ├── ShizukuMouseController.kt    — Binds/unbinds the Shizuku service
│       ├── TouchpadViewModel.kt         — State, display detection, gesture dispatch
│       ├── UinputNative.kt             — Kotlin wrapper for the JNI library
│       ├── TouchpadAccessibilityService.kt — Global nav actions (Back/Home/etc.)
│       └── ui/TouchpadScreen.kt        — Compose UI: touch surface + status bar
```

**Why JNI for uinput?**  
Android 16 removed the generic `Os.ioctl(FileDescriptor, int/long, long)` method from `android.system.Os`, leaving only specialised variants. A small native library is the only reliable way to call `ioctl` with an arbitrary integer value — required for `UI_SET_EVBIT`, `UI_SET_RELBIT`, etc.

**Why Shizuku?**  
Creating a `uinput` device requires the `input` group (gid 1004). Shizuku grants shell uid (2000), which is in that group. No root needed.

## Building from source

Requires Android Studio (for the SDK) and NDK r25+.

```bash
# Debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

The NDK CMake build compiles `libartouchpad.so` for `arm64-v8a` automatically.

## License

Copyright 2026 Paul Gratz

Licensed under the [Apache License, Version 2.0](LICENSE).
