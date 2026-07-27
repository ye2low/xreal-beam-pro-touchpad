# XREAL Beam Pro Touchpad

Turns the phone's screen into a real trackpad for Android desktop mode on AR glasses, so a
Beam Pro plus a pair of glasses is a workstation you can carry in a pocket — cursor, window
dragging, scrolling and typing, with nothing else to carry and no computer to plug into.

Built and used on an **XREAL Beam Pro (Android 14) with XREAL One** glasses. It is not
XREAL-specific: anything that exposes a USB-C DisplayPort screen and runs Android 14+ should
work.

> Based on [**AR Touchpad**](https://github.com/pgratz1/AR-Touchpad) by Paul Gratz, which is
> where the virtual-mouse-over-uinput idea and the original app come from. Licensed
> Apache-2.0; this fork keeps that license and those copyright notices. See
> [Relationship to AR Touchpad](#relationship-to-ar-touchpad) for what changed.

---

## Why this exists

Android's desktop mode puts app windows on the external display, but nothing moves the
cursor. A touchscreen is not a mouse: it has no persistent pointer, and every ready-made
touchpad app runs into the same wall — Android takes a window's touch away about 13 ms after
a mouse button goes down, so the app stops seeing the finger it is supposed to follow and
window dragging simply does not work.

This app creates a real virtual mouse through `/dev/uinput` and, for the button hold, reads
the touchscreen directly, which is the only vantage point from which the finger stays
visible.

---

## Requirements

| | |
|---|---|
| Android | 14 or newer, arm64 |
| [Shizuku](https://shizuku.rikka.app/) | Installed. **You do not have to start it by hand** — see below. |
| Wireless debugging | Enabled in developer options. This is what the app talks to. |
| Glasses | Any USB-C DisplayPort display, connected in extended (not mirrored) mode |

No root. No computer, after the one-time pairing described below.

---

## Install

1. Install `release/xreal-beam-pro-touchpad-*.apk`.
2. Enable **Developer options → Wireless debugging**.
3. Open the app and tap **Start Shizuku**.
4. A notification asks for a pairing code. Open **Developer options → Wireless debugging →
   Pair device with pairing code**, and — *leaving that system dialog on screen* — pull down
   the notification shade and type the six digits into the notification's reply field.
5. Tap **Grant** when Shizuku asks for permission.
6. Set up the [recommended configuration](#recommended-configuration), then — with the
   touchpad already open — plug in the glasses.

Step 4 happens once, ever. Android stops advertising its pairing service the moment its own
dialog leaves the screen, which is why the code is collected through a notification instead
of a screen of the app's own.

After a reboot, Shizuku is gone again — it cannot restart itself without root. Open the app
and tap **Start Shizuku**; it connects to the phone's own `adbd` over loopback and runs
Shizuku's starter. Note that wireless debugging only comes up **after the screen has been
unlocked** following a reboot.

The APK carries a debug signature. That is deliberate: a differently signed build can only be
installed over an uninstall, which wipes the app's files — including the key it pairs with,
so pairing would have to be done again.

---

## Gestures

| Gesture | Result |
|---|---|
| One finger, drag | Move the cursor |
| One finger, tap | Left click |
| One finger, double tap | Double click |
| One finger, hold | Hold the left button down — the pad edges light up |
| One finger, hold then drag | Drag a window, resize it from an edge, select text |
| Two fingers, drag | Scroll |
| Two fingers, pinch | Zoom page content |

Holding is the important one. Anything longer than a quick tap presses and holds the left
button, and the same finger then steers — the way a hand uses a real mouse. Release the
finger and the button releases.

Two buttons sit in the top bar: **Back**, aimed at the display the cursor is on (so it goes
back inside the app you are working in on the glasses, not on the phone), and the phone's own
**task switcher**.

There is also a quick-settings tile, so the app can be reached when the phone's home screen
is unusable — see [Troubleshooting](#troubleshooting).

---

## Settings

### Cursor Speed
How far the cursor travels per unit of finger movement. Movement is smoothed adaptively —
heavily for slow, fine motions, lightly for fast sweeps — so a trembling finger does not
jitter the pointer while a quick flick still keeps up.

### Scroll Speed
Multiplier on two-finger scrolling.

### Масштаб на очках / Glasses scale
A display-density override for the glasses **only**; the phone's own screen is untouched.
Type a number and press Apply. Useful values: **213** is this panel's own, **160** is what
Samsung uses for DeX, **120** is very small. The framework refuses anything below 72.

What it does and does not do, measured rather than guessed:

- Resolution never changes. 1920×1080 normal, 3840×1080 ultrawide, at either density.
- Window title bars are a fixed 42dp in the framework, so they follow density and nothing
  else: 56 px at 213 dpi, 42 px at 160.
- It is a uniform zoom-out, so it cannot make title bars thinner *relative to* their
  content — that ratio is fixed. What it buys is room: at the same window size, a settings
  list showed 5 rows at 213 dpi and 9 at 160.
- The override is stored against the panel's unique id, so it survives reboots and
  re-plugging. The field always shows what the display currently reports, not what was last
  saved, because those two drift apart.

### Force desktop mode
The developer option that puts a desktop on the external display.

**It has a serious side effect**: while it is on, the framework silently rewrites the
per-display keyboard policy, so the on-screen keyboard always appears on the glasses no
matter what any app asks for. This is in AOSP — `DisplayContent.getImePolicy()` returns
`LOCAL` whenever force-desktop-mode is set. Verified on device: requested 1, effective 0.

Takes effect when the glasses are re-connected.

### Enable freeform windows
The separate developer option that gives windows title bars, and lets them be moved and
resized. **Windowing survives with Force desktop mode off**, which is what makes the
recommended configuration below possible.

### Nebula
Switches XREAL's Nebula app off and on (`pm disable-user`, fully reversible).

Nebula takes over the glasses whenever Force desktop mode is off, and that is the only
reason that flag would have to stay on. Turning Nebula off breaks the deadlock. Check
whether your glasses' display modes (e.g. ultrawide) are switched through Nebula before
leaving it off.

### Natural Scroll
Off: content moves like a mouse wheel. On: content follows the fingers, as on a phone and as
macOS calls "natural". Affects both axes.

### Инерция прокрутки / Scroll inertia
Content keeps moving after the fingers leave and slows to a stop; touching the pad catches
it. Note the mouse wheel is detented, so the tail reads as scroll steps thinning out rather
than a truly smooth glide. **Затухание инерции** sets roughly how long a firm flick coasts,
in seconds.

### Keyboard on Phone
Asks the system to show the keyboard on the phone while the text field stays on the glasses —
the way Samsung DeX behaves. **This only works with Force desktop mode off**; with it on, the
framework overrides the request (see above).

---

## Recommended configuration

| Setting | Value | Why |
|---|---|---|
| **Nebula** | **off** | Otherwise it seizes the glasses the moment desktop mode is released |
| **Desktop on connect** | **on** | Handles the desktop-mode timing on its own — see below |
| **Freeform windows** | **on** | Windows keep title bars and stay resizable |
| **Keyboard on phone** | **on** | Typing happens on the phone, into the field on the glasses |
| Force desktop mode | leave alone | Driven by *Desktop on connect* |
| Interface scale | taste | 213 native, 160 for more room |

**Open the touchpad first, then plug in the glasses.** The app has to be running to catch the
moment they connect.

### Why "Desktop on connect" exists

The two things this setup needs are mutually exclusive, but only for an instant. Desktop mode
has to be **on** when the glasses register, or they come up mirroring the phone. It has to be
**off** afterwards, or the framework pins the keyboard to the glasses whatever any app asks
for. By hand that means: switch it on, unplug, plug back in, switch it off — which nobody
would guess. With this on, the app simply leaves the flag armed while nothing is connected and
drops it a few seconds after the desktop is up.

### The cost of turning Nebula off

Nebula **is** the "Glasses" app — one package, `com.xreal.evapro.nebula`. With it disabled
there is no display-mode chooser (Casting and the rest), and the display mode is decided
entirely by the desktop-mode flag at the moment of connection. That is what *Desktop on
connect* is for. If you need the mode chooser, turn Nebula back on and accept the keyboard
staying on the glasses — those two cannot both be had, and it is a framework decision rather
than a limitation of this app.

---

## Troubleshooting

### The phone's home screen is blank — icons flash, then a flat colour, and nothing responds
A window that moved to the glasses can leave its task behind on the phone: invisible, but
still composited. In SurfaceFlinger it shows up as a full-screen output layer with
`sourceCrop=[0 0 0 0]`, so the compositor clears the entire frame to a flat colour and the
launcher underneath never shows.

Open the task switcher — the **▢** button in the app's top bar, or the quick-settings tile if
you cannot reach the app — and swipe those tasks away.

It comes back after switching the glasses' display mode and after locking/unlocking.
Restarting the launcher does not help.

### No cursor after a reboot
Shizuku is not running. Open the app and tap **Start Shizuku**. If it says wireless debugging
is off, turn it on in developer options — Android also switches it off by itself whenever the
Wi-Fi network changes.

### The keyboard appears on the glasses
Force desktop mode is on. See that setting above.

### Holding a key on the keyboard lights up the pad instead of repeating
Fixed — the hold is confined to the touch surface's own bounds, which move up when the
keyboard appears. If you see it again, the app has lost track of its layout; reopen it.

---

## How it works

Two processes: the app, and a helper running as the `shell` uid through Shizuku.

- **The cursor** is a genuine virtual mouse created via `/dev/uinput`, so Android treats it as
  hardware and draws a real system pointer on the glasses. The device is pinned to the target
  display so its motion cannot leak onto the phone.
- **The button hold** is decided in the helper, reading `/dev/input` directly. It has to be:
  Android revokes the app window's touch about 13 ms after `BTN_LEFT` goes down — measured,
  zero events reached the window during a 9.5-second hold — so the app cannot see the finger
  it is following. The helper runs as `shell`, which may *read* input devices, so it keeps
  seeing everything. No exclusive grab, so normal touch handling is undisturbed.
- **Starting Shizuku** works because `adbd` listens on every interface, loopback included. The
  app connects to its own device as an ordinary ADB client and runs Shizuku's starter. The
  paired key lives in `/data/misc/adb/adb_keys` and its timestamp is refreshed on every
  connection, so it never expires.

Shizuku genuinely cannot be avoided: `/dev/uinput` carries the SELinux label `uhid_device`,
for which the `untrusted_app` domain has no rule at all, and `INTERNAL_SYSTEM_WINDOW` — needed
to move the keyboard between displays — is `signature|module`. No `pm grant` can bridge either.

---

## Known limits

- **Title bars cannot be made thinner relative to content.** 42dp is a framework resource
  compiled into SystemUI, and SystemUI ships no `overlayable.xml`, so a self-signed runtime
  overlay would be rejected. Density is the only lever.
- **Scrolling is detented.** Smooth, high-resolution scrolling needs the Linux
  `REL_WHEEL_HI_RES` axis, which Android's input reader only learned to handle in **Android
  16**, and there behind a flag. It is absent from Android 14 and 15.
- **Chrome's zoom is stepped.** Ctrl+wheel snaps to Blink's preset ladder (…0.9, 1.0, 1.1,
  1.25…) and one wheel event advances exactly one step, so this path cannot be made
  continuous.
- Wireless debugging is switched off by Android whenever the Wi-Fi network changes, and comes
  up only after the first unlock following a reboot.

---

## Building

```bash
./gradlew assembleDebug
```

Requires the Android SDK and NDK r25+ (the CMake build produces `libartouchpad.so` for
arm64). There are no tests; verification is on device.

---

## Relationship to AR Touchpad

This started as [AR Touchpad](https://github.com/pgratz1/AR-Touchpad) by Paul Gratz and keeps
its architecture: a Compose touch surface, a Shizuku user-service holding the uinput device,
and a JNI shim for `ioctl`. Retained wholesale.

Added here:

- Window dragging and resizing, by moving hold detection into the helper reading `/dev/input`
- Shizuku starting itself over the device's own `adbd`, with in-app pairing
- Per-display density control, and switches for Nebula and the two windowing developer options
- Scroll inertia; the task-switcher button and quick-settings tile
- The hold confined to the touch surface's bounds, so the on-screen keyboard keeps working
- DeX-style keyboard policy applied per display, reapplied on every reconnection

Removed: the bottom navigation row and the long-press right click, which conflicted with the
hold gesture.

The application id is still `com.pgratz.artouchpad`. Changing it would make the next install a
different app — settings and the ADB pairing key gone — so it stays.

---

## License

Copyright 2026 Paul Gratz and contributors.

[Apache License 2.0](LICENSE).
