// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad;

interface IMouseService {
    // Tells the service which display to target for cursor movement and key injection,
    // and provides its pixel dimensions so the cursor can be clamped correctly.
    void setDisplay(int displayId, int width, int height) = 1;

    // Moves the virtual mouse by (dx, dy) pixels relative to its current position.
    // Sub-pixel remainders are accumulated across calls so slow movements don't stall.
    void moveMouse(float dx, float dy) = 2;

    // Presses and releases a mouse button at the current cursor position.
    // button: MotionEvent.BUTTON_PRIMARY (left) or BUTTON_SECONDARY (right).
    void click(float x, float y, int button) = 3;

    // Scrolls the content under the cursor by (dx, dy) finger-pixel deltas.
    // dy is converted to REL_WHEEL detents, dx to REL_HWHEEL detents.
    void scroll(float dx, float dy) = 4;

    // Injects a key press+release for the given Android keycode (e.g. KEYCODE_BACK = 4)
    // targeted at the focused window on the configured display via InputManagerGlobal.
    void pressKey(int androidKeycode) = 5;

    // Converts a text string to KeyEvents via KeyCharacterMap and injects them
    // to the focused window on the configured display.
    void typeText(String text) = 6;

    // Writes the system font_scale setting (clamped 0.85–1.5) via `settings put system`.
    // Shell uid has permission to write system settings without root.
    void setFontScale(float scale) = 7;

    // Presses BTN_LEFT without releasing — used to begin a click-drag (text selection).
    void mouseDown() = 8;
    // Releases BTN_LEFT — used to end a click-drag (text selection).
    void mouseUp() = 9;

    // Injects Ctrl+keycode (Android keycode) via InputManagerGlobal — used for Copy/Cut/Paste/SelectAll
    // after a text selection drag without moving the cursor.
    void pressKeyWithCtrl(int keycode) = 10;

    // Injects a Ctrl+scroll MotionEvent at the current cursor position so Chrome and
    // WebView-based apps zoom their content without changing the system font scale.
    // amount: AXIS_VSCROLL value (positive = zoom in, negative = zoom out).
    void ctrlScroll(float amount) = 11;

    // Sets the per-display IME policy via IWindowManager.setDisplayImePolicy (reflection;
    // shell uid holds the required INTERNAL_SYSTEM_WINDOW permission).
    // policy: 0 = local (IME on the display that owns the field), 1 = fallback (IME on the
    // default display, i.e. the phone — DeX-style), 2 = hide.
    // Returns true if the call succeeded; false if unsupported on this build.
    boolean setImePolicy(int displayId, int policy) = 12;

    // Holds BTN_LEFT down and releases it when every finger has left the touchscreen.
    // The window cannot do this itself: Android revokes its touch ~13 ms after BTN_LEFT
    // goes down, so it never learns that the finger lifted. This service runs as shell,
    // which may read /dev/input directly, and so can watch the panel itself.
    // Watches the touchscreen directly for the whole session. A finger that rests without
    // moving for holdMs presses BTN_LEFT and keeps it down; from then on that finger drags,
    // and releasing it releases the button — the same thing a hand does with a mouse.
    //
    // This has to live in the service rather than the app: Android revokes the app window's
    // touch about 13 ms after BTN_LEFT goes down (measured — zero events reached the window
    // for an entire 9.5 s hold), so the app cannot see the finger it is supposed to follow.
    // The service runs as shell and may read /dev/input, so it keeps seeing everything.
    void startPanelWatch(float sensitivity, int holdMs, int slopPx) = 13;
    void stopPanelWatch() = 15;

    // True while the hold above is still in effect, so the button can stay lit.
    boolean isLeftHeld() = 14;

    // Opens the phone's task switcher. Deliberately aimed at the phone rather than the
    // configured display: it exists so leftover tasks sitting on the phone can be swiped
    // away by hand. Those tasks are what blanks the home screen — a window that has moved
    // to the glasses can leave its task behind, invisible but still composited with an
    // empty buffer, and the compositor then fills the whole phone screen with flat colour.
    void showRecents() = 17;

    // Overrides the density of one display via `wm density <n> -d <id>`, or restores the
    // panel's own value when density is 0. This is the only thing that changes the
    // thickness of freeform window captions: their height is a fixed 42dp in the framework,
    // so it scales with display density and with nothing else.
    boolean setDisplayDensity(int displayId, int density) = 18;

    // Enables or disables XREAL's Nebula package. Nebula takes over the glasses whenever the
    // desktop-mode flag is off, which is the only reason that flag has to stay on — and with
    // it on, the system forces the keyboard onto the glasses no matter what policy is asked
    // for. Turning Nebula off is what breaks that deadlock. Uses `pm disable-user`, which is
    // reversible: `pm enable` brings it straight back.
    boolean setNebulaEnabled(boolean enabled) = 19;

    // Writes one of the two windowing developer options in Settings.Global. Shell holds
    // WRITE_SECURE_SETTINGS, which a normal app cannot be granted. Only these two keys are
    // accepted; anything else is refused, so this stays a windowing switch rather than a
    // way to write any secure setting at all.
    //   "force_desktop_mode_on_external_displays" — desktop mode on the glasses
    //   "enable_freeform_support"                 — freeform (resizable) windows
    boolean setWindowingFlag(String key, boolean enabled) = 20;

    // Where the touch surface sits, as fractions of screen height. Contacts landing outside
    // this band are ignored by the hold above: this service watches the whole touchscreen and
    // knows nothing about windows, so without it a key held down on the keyboard — backspace
    // above all — was read as a finger resting on the pad and became a held mouse button,
    // and the key stopped repeating. Passing 0 and 1 watches the whole panel again.
    void setPanelBounds(float topFraction, float bottomFraction) = 21;

    // Scrolls by injecting a scroll event straight at the focused window, with a fractional
    // amount, instead of turning the movement into whole mouse-wheel detents. Detents are all
    // a uinput wheel can express: the high-resolution wheel axis Linux offers is only read by
    // Android from 16 onwards, and this device is on 14.
    void scrollFine(float vScroll, float hScroll) = 22;

    // A real two-finger pinch on the glasses, as a synthetic touch stream centred on the
    // cursor. Ctrl+wheel — the other way to zoom — cannot be smooth: Chrome snaps it to a
    // fixed ladder of zoom factors and advances exactly one step per event, however small
    // the amount. A genuine pinch is continuous, and works in any app that handles one.
    void pinchBegin() = 23;
    void pinchUpdate(float spanDelta) = 24;
    void pinchEnd() = 25;

    // Swaps the virtual device between mouse and touchpad. As a touchpad, Android itself
    // recognises the gestures — scroll, pinch, tap, two-finger tap, multi-finger swipes —
    // and its scrolling is a synthetic finger drag rather than wheel detents, so apps fling
    // it the way they fling a finger. The two cannot coexist on one device: BTN_LEFT is also
    // BTN_MOUSE, so relative axes beside absolute ones would build both mappers at once.
    // maxX/maxY are the panel's axis maxima; resX/resY its resolution in units per mm, which
    // is the ruler every gesture threshold is measured against.
    boolean setTouchpadMode(boolean enabled, int maxX, int maxY, int resX, int resY) = 26;

    // Creates or tears down the virtual input device. It is not created at startup on
    // purpose: a mouse that exists while no external display is attached puts its cursor
    // on the phone's own screen, where it clicks the touchpad's own interface and drags
    // the notification shade around — the pad ends up fighting itself. Tearing down
    // releases any held button first, so nothing is left pressed.
    boolean setDeviceEnabled(boolean enabled) = 27;

    // Closes the uinput file descriptor and marks the device not ready.
    void destroy() = 16777114;
}
