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

package com.pgratz.artouchpad

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Shizuku UserService — runs as shell uid.
 *
 * Creates a real virtual mouse via /dev/uinput using JNI (the only reliable
 * way to call ioctl with a value argument on Android 16, where Os.ioctl was
 * stripped down to ioctlInetAddress and ioctlInt(fd, req) — no generic variant).
 *
 * Shell uid is in the `input` group and has rw access to /dev/uinput, so no
 * additional permissions are needed beyond what Shizuku already grants.
 */
class MouseService : IMouseService.Stub() {

    private var displayId = android.view.Display.DEFAULT_DISPLAY
    private var displayWidth = 1920
    private var displayHeight = 1080
    private var cursorX = 960f
    private var cursorY = 540f

    private var uinputReady = false
    // Cached descriptor string for our uinput device; set after InputReader registers it.
    // Used by associateDeviceToDisplay() to pin the cursor to the external display.
    private var uinputDescriptor: String? = null

    // Sub-pixel accumulators: carry fractional remainders between calls so that
    // slow movements (e.g. 0.4 px/frame) accumulate cleanly instead of truncating
    // to zero every frame and producing sudden jumps.
    private var accumX = 0f
    private var accumY = 0f
    private var accumScrollX = 0f
    private var accumScrollY = 0f

    // Reflection handles for display-targeted key injection and device-display association.
    // injectInputEvent(event, mode) on InputManagerGlobal respects the displayId
    // embedded in the event, routing the key to the focused window on that display.
    private val imgClass by lazy {
        runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
    }
    private val imgInstance by lazy {
        imgClass?.getMethod("getInstance")?.invoke(null)
    }
    private val imgInjectEvent by lazy {
        imgClass?.getMethod("injectInputEvent",
            android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod by lazy {
        runCatching {
            android.view.InputEvent::class.java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.getOrNull()
    }
    private val imgGetDeviceIds by lazy {
        runCatching { imgClass?.getMethod("getInputDeviceIds") }.getOrNull()
    }
    private val imgGetDevice by lazy {
        runCatching {
            imgClass?.getMethod("getInputDevice", Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val imgSetDisplayAssoc by lazy {
        runCatching {
            imgClass?.getMethod("setInputDeviceDisplayAssociation",
                String::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    // Reflection handles for IWindowManager.setDisplayImePolicy — controls which display
    // shows the IME for fields focused on a given display. Shell uid holds the required
    // INTERNAL_SYSTEM_WINDOW permission, so the binder call passes the WMS check.
    private val iwmInstance by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as android.os.IBinder
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }
    private val iwmSetImePolicy by lazy {
        runCatching {
            iwmInstance?.javaClass?.getMethod("setDisplayImePolicy",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    // No device is created here. A virtual mouse that exists before the glasses do puts its
    // cursor on the phone's own screen, and the app then clicks its own interface — see
    // setDeviceEnabled, which the app calls once it has somewhere to put the cursor.

    // Geometry of the physical panel, as the virtual touchpad reports it: axis maxima in
    // panel units and resolution in units per millimetre. The resolution is what every
    // gesture threshold is scaled by, so a wrong value makes the library judge distances
    // against the wrong ruler.
    private var padMaxX = 1079
    private var padMaxY = 2399
    private var padResX = 16
    private var padResY = 16
    @Volatile private var touchpadMode = false

    // Whether the app wants a device at all. Kept separate from uinputReady so the touchpad
    // mode can be changed while nothing is connected: the choice is remembered and applied
    // the next time a device is actually created.
    @Volatile private var deviceWanted = false

    // Creates or tears down the virtual device. Nothing exists until the app asks, because a
    // mouse with no external display puts its cursor on the phone's own screen — where it
    // clicks the touchpad's own interface and drags the shade about.
    override fun setDeviceEnabled(enabled: Boolean): Boolean {
        deviceWanted = enabled
        if (enabled == uinputReady) return uinputReady
        if (enabled) {
            if (touchpadMode) uinputReady = initTouchpad() else initUinput()
        } else {
            // Releases a held button before the descriptor goes away; otherwise whatever was
            // being dragged on the glasses would stay stuck to a device that no longer exists.
            stopPanelWatch()
            UinputNative.nClose()
            uinputReady = false
            uinputDescriptor = null
        }
        Log.i(TAG, "setDeviceEnabled($enabled) ready=$uinputReady")
        return uinputReady
    }

    // Swaps the virtual device between mouse and touchpad. They cannot coexist on one
    // device — BTN_LEFT is also BTN_MOUSE, so relative axes next to absolute ones would give
    // Android both a cursor mapper and a touchpad mapper, moving the pointer twice.
    override fun setTouchpadMode(enabled: Boolean, maxX: Int, maxY: Int, resX: Int, resY: Int): Boolean {
        val modeUnchanged = enabled == touchpadMode
        padMaxX = maxX; padMaxY = maxY
        padResX = resX; padResY = resY
        touchpadMode = enabled
        // With no device wanted there is nothing to rebuild: the mode is remembered and
        // applied by setDeviceEnabled when the glasses turn up.
        if (!deviceWanted) return false
        if (modeUnchanged && uinputReady) return true
        stopPanelWatch()
        UinputNative.nClose()
        uinputReady = false
        if (enabled) {
            uinputReady = initTouchpad()
        } else {
            initUinput()
        }
        Log.i(TAG, "setTouchpadMode($enabled) ready=$uinputReady")
        return uinputReady
    }

    // Declares the virtual device as a TOUCHPAD rather than a mouse, which hands every
    // gesture to Android itself: two-finger scroll, pinch, tap-to-click, two-finger tap as
    // right click, three- and four-finger swipes, palm rejection, pointer acceleration. The
    // scroll it produces is a synthetic finger drag rather than wheel detents, so apps apply
    // their own fling — the same motion a finger on the screen produces.
    //
    // Three declarations are load-bearing and easy to get wrong:
    //   * INPUT_PROP_POINTER is what separates a touchpad from a touchscreen. Without it the
    //     device drives absolute touches; with INPUT_PROP_DIRECT it certainly would.
    //   * No EV_REL at all. BTN_LEFT is also BTN_MOUSE, so relative axes alongside the
    //     absolute ones would have Android build a cursor mapper *and* a touchpad mapper on
    //     the same device, and the pointer would move twice per gesture.
    //   * No stylus tool buttons. Any of BTN_TOOL_PEN..AIRBRUSH silently demotes the device
    //     to a plain multitouch surface, with nothing logged to say why.
    private fun initTouchpad(): Boolean {
        val fd = UinputNative.nOpen()
        if (fd < 0) { Log.e(TAG, "nOpen failed"); return false }

        fun ioctl(req: Int, value: Int) {
            val r = UinputNative.nIoctl(req, value)
            if (r < 0) Log.w(TAG, "ioctl(0x${req.toString(16)}, $value) returned $r")
        }

        ioctl(UI_SET_PROPBIT, INPUT_PROP_POINTER)
        ioctl(UI_SET_PROPBIT, INPUT_PROP_BUTTONPAD)

        ioctl(UI_SET_EVBIT, EV_SYN)
        ioctl(UI_SET_EVBIT, EV_KEY)
        ioctl(UI_SET_EVBIT, EV_ABS)

        // The finger-count buttons are not decoration: Android reads exactly these to decide
        // how many fingers are down, and a gesture with no BTN_TOOL_* set counts as zero.
        ioctl(UI_SET_KEYBIT, BTN_TOUCH)
        ioctl(UI_SET_KEYBIT, BTN_TOOL_FINGER)
        ioctl(UI_SET_KEYBIT, BTN_TOOL_DOUBLETAP)
        ioctl(UI_SET_KEYBIT, BTN_TOOL_TRIPLETAP)
        ioctl(UI_SET_KEYBIT, BTN_TOOL_QUADTAP)
        ioctl(UI_SET_KEYBIT, BTN_LEFT)
        ioctl(UI_SET_KEYBIT, BTN_RIGHT)

        ioctl(UI_SET_ABSBIT, ABS_MT_SLOT)
        ioctl(UI_SET_ABSBIT, ABS_MT_TRACKING_ID)
        ioctl(UI_SET_ABSBIT, ABS_MT_POSITION_X)
        ioctl(UI_SET_ABSBIT, ABS_MT_POSITION_Y)
        ioctl(UI_SET_ABSBIT, ABS_MT_PRESSURE)

        UinputNative.nAbsSetup(ABS_MT_SLOT, 0, MAX_SLOTS - 1, 0)
        UinputNative.nAbsSetup(ABS_MT_TRACKING_ID, 0, 65535, 0)
        UinputNative.nAbsSetup(ABS_MT_POSITION_X, 0, padMaxX, padResX)
        UinputNative.nAbsSetup(ABS_MT_POSITION_Y, 0, padMaxY, padResY)
        UinputNative.nAbsSetup(ABS_MT_PRESSURE, 0, 255, 0)

        if (UinputNative.nDevSetup("AR Touchpad Pad") < 0) return false
        ioctl(UI_DEV_CREATE, 0)

        Thread.sleep(400)
        Log.i(TAG, "touchpad device ready: ${padMaxX}x$padMaxY res=$padResX/$padResY")
        return true
    }

    // Opens /dev/uinput via JNI, declares mouse capabilities (REL_X/Y, wheel, buttons),
    // writes the device name, and issues UI_DEV_CREATE. Waits 400 ms for InputReader to
    // enumerate the new device before marking uinputReady = true.
    private fun initUinput() {
        try {
            val fd = UinputNative.nOpen()
            if (fd < 0) { Log.e(TAG, "nOpen failed"); return }

            fun ioctl(req: Int, value: Int) {
                val r = UinputNative.nIoctl(req, value)
                if (r < 0) Log.w(TAG, "ioctl(0x${req.toString(16)}, $value) returned $r")
            }

            ioctl(UI_SET_EVBIT,  EV_SYN)
            ioctl(UI_SET_EVBIT,  EV_KEY)
            ioctl(UI_SET_EVBIT,  EV_REL)
            ioctl(UI_SET_RELBIT, REL_X)
            ioctl(UI_SET_RELBIT, REL_Y)
            ioctl(UI_SET_RELBIT, REL_WHEEL)
            ioctl(UI_SET_RELBIT, REL_HWHEEL)
            ioctl(UI_SET_KEYBIT, BTN_LEFT)
            ioctl(UI_SET_KEYBIT, BTN_RIGHT)
            ioctl(UI_SET_KEYBIT, BTN_MIDDLE)
            ioctl(UI_SET_KEYBIT, KEY_BACK)
            ioctl(UI_SET_KEYBIT, KEY_HOME)
            ioctl(UI_SET_KEYBIT, KEY_APPSWITCH)

            val n = UinputNative.nWriteDevInfo("AR Touchpad Mouse")
            if (n < 0) { Log.e(TAG, "nWriteDevInfo failed"); return }

            ioctl(UI_DEV_CREATE, 0)

            Thread.sleep(400) // give InputReader time to register the device
            uinputReady = true
            uinputDescriptor = findUinputDescriptor()
            Log.i(TAG, "uinput device ready, descriptor=$uinputDescriptor")
        } catch (e: Exception) {
            Log.e(TAG, "initUinput failed: $e")
        }
    }

    // Scans all registered input devices via InputManagerGlobal and returns the descriptor
    // of the one named "AR Touchpad Mouse". Returns null if not found or reflection fails.
    private fun findUinputDescriptor(): String? = runCatching {
        val instance = imgInstance ?: return@runCatching null
        val ids = imgGetDeviceIds?.invoke(instance) as? IntArray ?: return@runCatching null
        val getDevice = imgGetDevice ?: return@runCatching null
        ids.toList().mapNotNull { id ->
            (getDevice.invoke(instance, id) as? android.view.InputDevice)
                ?.takeIf { it.name == "AR Touchpad Mouse" }
                ?.descriptor
        }.firstOrNull()
    }.getOrNull()

    // Tells Android's InputReader to route this uinput device's cursor to targetDisplayId.
    // Falls back to accessing the raw IInputManager binder via the mIm field if
    // setInputDeviceDisplayAssociation is not exposed directly on InputManagerGlobal.
    private fun associateDeviceToDisplay(descriptor: String, targetDisplayId: Int) {
        runCatching {
            val instance = imgInstance ?: return
            val method = imgSetDisplayAssoc ?: run {
                // Fallback: call through the raw IInputManager binder held by InputManagerGlobal.
                val mImField = imgClass?.getDeclaredField("mIm")
                    ?.also { it.isAccessible = true }
                val iim = mImField?.get(instance) ?: return
                val iimMethod = iim.javaClass.getMethod("setInputDeviceDisplayAssociation",
                    String::class.java, Int::class.javaPrimitiveType)
                iimMethod.invoke(iim, descriptor, targetDisplayId)
                Log.i(TAG, "associated uinput device (via mIm) to display $targetDisplayId")
                return
            }
            method.invoke(instance, descriptor, targetDisplayId)
            Log.i(TAG, "associated uinput device to display $targetDisplayId")
        }.onFailure { Log.w(TAG, "associateDeviceToDisplay failed: $it") }
    }

    // Writes a single struct input_event{type, code, value} to the open uinput fd via JNI.
    private fun ev(type: Int, code: Int, value: Int) = UinputNative.nWriteEvent(type, code, value)
    // Flushes all buffered events to the input dispatcher with an EV_SYN/SYN_REPORT marker.
    private fun sync() = ev(EV_SYN, SYN_REPORT, 0)

    // Stores the target display id and pixel dimensions; resets cursor to center and clears
    // accumulators. Associates the uinput device to the target display so that REL_X/Y events
    // move the cursor on the glasses screen instead of the phone screen. Sends a 1-px nudge
    // to wake the OS cursor on the new display.
    override fun setDisplay(id: Int, width: Int, height: Int) {
        displayId = id
        displayWidth = width
        displayHeight = height
        cursorX = width / 2f
        cursorY = height / 2f
        accumX = 0f
        accumY = 0f
        accumScrollX = 0f
        accumScrollY = 0f

        // Pin the uinput device to this display so cursor movement lands on the glasses screen.
        // Without this, Android may default the virtual mouse to the phone's display on some setups.
        uinputDescriptor?.let { associateDeviceToDisplay(it, id) }

        // Nudge the pointer to wake the cursor.
        if (uinputReady) {
            ev(EV_REL, REL_X, 1); ev(EV_REL, REL_Y, 1); sync()
            Thread.sleep(50)
            ev(EV_REL, REL_X, -1); ev(EV_REL, REL_Y, -1); sync()
        }
        Log.i(TAG, "setDisplay id=$id ${width}x${height} uinputReady=$uinputReady")
    }

    // Accumulates fractional deltas in accumX/Y; only emits REL_X/REL_Y events for the
    // whole-pixel portion, carrying the remainder forward. Also clamps the tracked cursor
    // position to the display bounds so the ViewModel overlay stays in sync.
    override fun moveMouse(dx: Float, dy: Float) {
        if (!uinputReady) return
        accumX += dx
        accumY += dy
        val idx = accumX.toInt()
        val idy = accumY.toInt()
        if (idx == 0 && idy == 0) return
        accumX -= idx
        accumY -= idy
        cursorX = (cursorX + idx).coerceIn(0f, displayWidth - 1f)
        cursorY = (cursorY + idy).coerceIn(0f, displayHeight - 1f)
        ev(EV_REL, REL_X, idx)
        ev(EV_REL, REL_Y, idy)
        sync()
    }

    // Presses (value=1) then releases (value=0) BTN_LEFT or BTN_RIGHT with a 50 ms hold.
    // x/y are accepted for interface symmetry but cursor position is already tracked by moveMouse.
    override fun click(x: Float, y: Float, button: Int) {
        if (!uinputReady) return
        val btn = if (button == MotionEvent.BUTTON_SECONDARY) BTN_RIGHT else BTN_LEFT
        ev(EV_KEY, btn, 1); sync()
        Thread.sleep(50)
        ev(EV_KEY, btn, 0); sync()
    }

    // Input: Android keycode (e.g. KeyEvent.KEYCODE_BACK = 4).
    // Creates ACTION_DOWN + ACTION_UP KeyEvents, stamps each with the target displayId via
    // InputEvent.setDisplayId reflection, then calls InputManagerGlobal.injectInputEvent so
    // the key reaches the focused window on the glasses display rather than the phone.
    override fun pressKey(androidKeycode: Int) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, androidKeycode, 0)
            setDisp.invoke(down, displayId)
            inject.invoke(instance, down, 0 /*INJECT_INPUT_EVENT_MODE_ASYNC*/)
            Thread.sleep(20)
            val up = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, androidKeycode, 0)
            setDisp.invoke(up, displayId)
            inject.invoke(instance, up, 0)
            Log.d(TAG, "pressKey keycode=$androidKeycode displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "pressKey $androidKeycode failed: $e")
        }
    }

    // Input: a plain text string. Converts the full char array to a KeyEvent sequence via
    // KeyCharacterMap.VIRTUAL_KEYBOARD; falls back to per-character conversion for strings
    // that can't be mapped in one shot (e.g. mixed scripts). Delegates to injectKeyEvents.
    override fun typeText(text: String) {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray())
        if (events != null) {
            injectKeyEvents(events)
        } else {
            for (ch in text) {
                val evs = kcm.getEvents(charArrayOf(ch)) ?: continue
                injectKeyEvents(evs)
            }
        }
        Log.d(TAG, "typeText \"$text\" displayId=$displayId")
    }

    // For each KeyEvent in the array, constructs a new event carrying the same action/keycode/
    // meta state, stamps it with displayId via reflection, then calls injectInputEvent so it
    // lands on the focused window of the target display.
    private fun injectKeyEvents(events: Array<out KeyEvent>) {
        val instance = imgInstance ?: return
        val inject   = imgInjectEvent ?: return
        val setDisp  = setDisplayIdMethod ?: return
        for (ev in events) {
            val targeted = KeyEvent(ev.downTime, ev.eventTime, ev.action,
                                    ev.keyCode, ev.repeatCount, ev.metaState)
            setDisp.invoke(targeted, displayId)
            inject.invoke(instance, targeted, 0)
        }
    }

    // Input: (dx, dy) finger-pixel deltas (positive = fingers moving right/down).
    // Converts each axis to wheel detents at 20 px/detent using accumScrollX/Y; emits
    // REL_WHEEL/REL_HWHEEL only for whole detents, carrying sub-detent remainders forward.
    override fun scroll(dx: Float, dy: Float) {
        if (!uinputReady) return
        // 20px of finger movement = 1 wheel detent (adjustable via scrollSpeed in ViewModel).
        accumScrollX += dx / 20f
        accumScrollY += dy / 20f
        val stepsX = accumScrollX.toInt()
        val stepsY = accumScrollY.toInt()
        if (stepsX == 0 && stepsY == 0) return
        accumScrollX -= stepsX
        accumScrollY -= stepsY
        if (stepsY != 0) ev(EV_REL, REL_WHEEL, -stepsY)
        if (stepsX != 0) ev(EV_REL, REL_HWHEEL, -stepsX)
        sync()
    }

    // Input: desired font scale (clamped internally to 0.85–1.5).
    // Runs `settings put system font_scale <value>` as a subprocess; shell uid has permission
    // to write system settings, so no additional privileges are needed.
    override fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.5f)
        try {
            Runtime.getRuntime().exec(
                arrayOf("settings", "put", "system", "font_scale", "%.2f".format(clamped))
            ).waitFor()
            Log.d(TAG, "setFontScale $clamped")
        } catch (e: Exception) {
            Log.e(TAG, "setFontScale failed: $e")
        }
    }

    // Opens the phone's task switcher, so leftover tasks can be swiped away by hand.
    // `-d 0` is deliberate: every other injection here aims at the glasses, but the tasks
    // that blank the home screen sit on the phone, and that is where recents has to appear.
    override fun showRecents() {
        try {
            Runtime.getRuntime().exec(
                arrayOf("input", "-d", "0", "keyevent", "187")  // KEYCODE_APP_SWITCH
            ).waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "showRecents failed: $e")
        }
    }

    // Input: displayId and a density in dpi, or 0 to drop the override.
    // Freeform window captions are a fixed 42dp in the framework, so their thickness follows
    // the display's density and nothing else. The override is stored against the display's
    // unique id, so it survives reboots and re-plugging the glasses.
    override fun setDisplayDensity(displayId: Int, density: Int): Boolean = try {
        val args = if (density <= 0) {
            arrayOf("wm", "density", "reset", "-d", displayId.toString())
        } else {
            arrayOf("wm", "density", density.toString(), "-d", displayId.toString())
        }
        val exit = Runtime.getRuntime().exec(args).waitFor()
        Log.d(TAG, "setDisplayDensity($displayId, $density) exit=$exit")
        exit == 0
    } catch (e: Exception) {
        Log.e(TAG, "setDisplayDensity failed: $e")
        false
    }

    // Writes one of the two windowing developer options. The key is checked against a fixed
    // pair rather than passed through: shell can write any secure setting, and there is no
    // reason for this interface to be able to.
    override fun setWindowingFlag(key: String, enabled: Boolean): Boolean {
        if (key !in WINDOWING_FLAGS) {
            Log.e(TAG, "refusing to write $key")
            return false
        }
        return try {
            val exit = Runtime.getRuntime()
                .exec(arrayOf("settings", "put", "global", key, if (enabled) "1" else "0"))
                .waitFor()
            Log.d(TAG, "setWindowingFlag($key, $enabled) exit=$exit")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "setWindowingFlag failed: $e")
            false
        }
    }

    // Turns XREAL's Nebula on or off. `disable-user` rather than `disable`: it is the form
    // shell is allowed to use, it applies to this user only, and `pm enable` undoes it.
    override fun setNebulaEnabled(enabled: Boolean): Boolean = try {
        val verb = if (enabled) "enable" else "disable-user"
        val exit = Runtime.getRuntime()
            .exec(arrayOf("pm", verb, "--user", "0", NEBULA_PACKAGE))
            .waitFor()
        Log.d(TAG, "setNebulaEnabled($enabled) exit=$exit")
        exit == 0
    } catch (e: Exception) {
        Log.e(TAG, "setNebulaEnabled failed: $e")
        false
    }

    // Input: displayId and IME policy (0 = local, 1 = fallback, 2 = hide).
    // Calls IWindowManager.setDisplayImePolicy via reflection (there is no `wm` shell
    // subcommand for this). With policy 1 (fallback), a field focused on the target display
    // shows its IME on the default display (the phone) while the InputConnection stays with
    // the field — DeX-style typing. Returns false when unsupported on this build.
    override fun setImePolicy(displayId: Int, policy: Int): Boolean {
        val iwm = iwmInstance ?: run { Log.e(TAG, "IWindowManager unavailable"); return false }
        val method = iwmSetImePolicy ?: run { Log.e(TAG, "setDisplayImePolicy unavailable"); return false }
        return runCatching {
            method.invoke(iwm, displayId, policy)
            // Read it straight back. AOSP's DisplayContent.getImePolicy() rewrites
            // FALLBACK_DISPLAY to LOCAL whenever force_desktop_mode_on_external_displays is
            // on, so the write can "succeed" and mean nothing — this is the only way to see
            // what the system will actually use.
            val effective = runCatching {
                iwm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
                    .invoke(iwm, displayId) as? Int
            }.getOrNull()
            Log.i(TAG, "setDisplayImePolicy display=$displayId requested=$policy effective=$effective")
            true
        }.getOrElse {
            Log.w(TAG, "setDisplayImePolicy failed: $it")
            false
        }
    }

    // Input: Android keycode (e.g. KEYCODE_C). Injects Ctrl+keycode via InputManagerGlobal
    // with META_CTRL_ON|META_CTRL_LEFT_ON so apps see a real Ctrl+key shortcut.
    // Used for Copy/Cut/Paste/SelectAll after a text selection without moving the cursor.
    override fun pressKeyWithCtrl(keycode: Int) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

            val ctrlDown = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
            setDisp.invoke(ctrlDown, displayId); inject.invoke(instance, ctrlDown, 0)

            val kDown = KeyEvent(t, t, KeyEvent.ACTION_DOWN, keycode, 0, ctrlMeta)
            setDisp.invoke(kDown, displayId); inject.invoke(instance, kDown, 0)
            Thread.sleep(20)

            val kUp = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keycode, 0, ctrlMeta)
            setDisp.invoke(kUp, displayId); inject.invoke(instance, kUp, 0)

            val ctrlUp = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
            setDisp.invoke(ctrlUp, displayId); inject.invoke(instance, ctrlUp, 0)

            Log.d(TAG, "pressKeyWithCtrl keycode=$keycode displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "pressKeyWithCtrl failed: $e")
        }
    }

    // Injects a Ctrl+scroll MotionEvent (ACTION_SCROLL + META_CTRL_ON + AXIS_VSCROLL) at the
    // tracked cursor position. Chrome and WebView-based apps zoom their page content in response;
    // positive amount = zoom in, negative = zoom out. Does not affect the system font scale.
    override fun ctrlScroll(amount: Float) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = cursorX; y = cursorY; pressure = 0f; size = 0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
            })
            val event = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_SCROLL,
                1, props, coords,
                ctrlMeta, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0
            )
            setDisp.invoke(event, displayId)
            inject.invoke(instance, event, 0)
            event.recycle()
            Log.d(TAG, "ctrlScroll $amount displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "ctrlScroll failed: $e")
        }
    }

    // Scrolls without going through the wheel. A uinput wheel can only express whole detents,
    // so scrolling built from it arrives in steps however finely the fingers move; the
    // high-resolution wheel axis that would fix it is only read by Android from 16 onwards.
    // Injecting the scroll event directly sidesteps the wheel: the amount is a float all the
    // way to the app, and every app already reads this axis.
    override fun scrollFine(vScroll: Float, hScroll: Float) {
        val instance = imgInstance ?: return
        val inject = imgInjectEvent ?: return
        val setDisp = setDisplayIdMethod ?: return
        try {
            val t = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = cursorX; y = cursorY; pressure = 0f; size = 0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            })
            val event = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_SCROLL,
                1, props, coords,
                0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0
            )
            setDisp.invoke(event, displayId)
            inject.invoke(instance, event, 0)
            event.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "scrollFine failed: $e")
        }
    }

    // A synthetic two-finger pinch centred on the cursor. The two contacts sit either side of
    // it and move apart or together as the fingers on the pad do, which is what makes the
    // zoom continuous — Android's gesture detector works on geometry alone and has no way to
    // tell this from a hand on a touchscreen.
    private var pinchDownTime = 0L
    private var pinchHalfSpan = 0f
    private var pinching = false

    override fun pinchBegin() {
        if (pinching) return
        pinchDownTime = SystemClock.uptimeMillis()
        pinchHalfSpan = PINCH_START_SPAN
        pinching = true
        injectPinch(MotionEvent.ACTION_DOWN, 1)
        injectPinch(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2
        )
    }

    override fun pinchUpdate(spanDelta: Float) {
        if (!pinching) return
        // Half, because the span is shared between the two contacts.
        pinchHalfSpan = (pinchHalfSpan + spanDelta / 2f)
            .coerceIn(PINCH_MIN_SPAN, maxOf(PINCH_MIN_SPAN + 1f, displayWidth / 2f - 8f))
        injectPinch(MotionEvent.ACTION_MOVE, 2)
    }

    override fun pinchEnd() {
        if (!pinching) return
        injectPinch(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2
        )
        injectPinch(MotionEvent.ACTION_UP, 1)
        pinching = false
    }

    // downTime stays fixed for the whole gesture and eventTime advances, which is what makes
    // the stream a gesture rather than a series of unrelated taps.
    private fun injectPinch(action: Int, pointerCount: Int) {
        val instance = imgInstance ?: return
        val inject = imgInjectEvent ?: return
        val setDisp = setDisplayIdMethod ?: return
        try {
            val props = Array(pointerCount) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(pointerCount) { i ->
                MotionEvent.PointerCoords().apply {
                    val offset = if (i == 0) -pinchHalfSpan else pinchHalfSpan
                    x = (cursorX + offset).coerceIn(0f, displayWidth - 1f)
                    y = cursorY.coerceIn(0f, displayHeight - 1f)
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                pinchDownTime, SystemClock.uptimeMillis(), action,
                pointerCount, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            setDisp.invoke(event, displayId)
            inject.invoke(instance, event, 0)
            event.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "pinch inject failed: $e")
        }
    }

    // Presses BTN_LEFT without releasing — the paired mouseUp() call ends the drag.
    // Used for text selection: button held while moveMouse moves the cursor.
    override fun mouseDown() {
        if (!uinputReady) return
        ev(EV_KEY, BTN_LEFT, 1); sync()
        Log.d(TAG, "mouseDown displayId=$displayId")
    }

    // Releases BTN_LEFT previously pressed by mouseDown().
    override fun mouseUp() {
        if (!uinputReady) return
        ev(EV_KEY, BTN_LEFT, 0); sync()
        Log.d(TAG, "mouseUp displayId=$displayId")
    }

    @Volatile private var leftHeld = false
    private var watchers = mutableListOf<java.io.FileInputStream>()

    override fun isLeftHeld(): Boolean = leftHeld

    @Volatile private var watching = false

    override fun stopPanelWatch() {
        watching = false
        releaseHold()
        synchronized(watchers) {
            watchers.forEach { runCatching { it.close() } }
            watchers.clear()
        }
    }

    // Watches the panel for the whole session and turns "finger resting in place" into a
    // held left button, then steers from that same finger.
    //
    // The app cannot do any of this itself: pressing BTN_LEFT makes Android revoke the app
    // window's touch within about 13 ms, so the finger vanishes from the app's point of view
    // while still resting on the glass. This service runs as shell (uid 2000, group input),
    // which may READ /dev/input — no grab, no interference with normal touch handling — so
    // it keeps seeing the finger the window has lost.
    override fun startPanelWatch(sensitivity: Float, holdMs: Int, slopPx: Int) {
        if (!uinputReady || watching) return
        watching = true
        Log.d(TAG, "panel watch on (hold=${holdMs}ms slop=${slopPx}px)")

        // The touchscreen node cannot be identified up front — /proc/bus/input/devices is
        // not readable at this uid — so every readable node is watched and whichever one
        // reports touches is the panel.
        val files = java.io.File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?.sortedByDescending { it.name } ?: emptyList()

        synchronized(watchers) { watchers.clear() }
        for (file in files) {
            val stream = runCatching { java.io.FileInputStream(file) }.getOrNull() ?: continue
            synchronized(watchers) { watchers.add(stream) }
            val maxY = readAbsMax(file.absolutePath)
            Thread { watchLoop(stream, maxY, sensitivity, holdMs, slopPx) }
                .apply { isDaemon = true }.start()
        }

        // A finger lying perfectly still produces no events at all, so the hold cannot be
        // detected while reading the panel — it would only fire if the finger happened to
        // twitch at exactly the right moment. This ticks on its own instead.
        Thread {
            while (watching) {
                Thread.sleep(25)
                val since = restingSince
                if (!leftHeld && since != 0L && System.currentTimeMillis() - since >= holdMs) {
                    leftHeld = true
                    ev(EV_KEY, BTN_LEFT, 1); sync()
                    Log.d(TAG, "hold: BTN_LEFT down")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // When the single resting contact landed, or 0 when there is nothing to wait for
    // (no finger, several fingers, or the finger has wandered off).
    @Volatile private var restingSince = 0L

    // The band of the panel the touch surface occupies, as fractions of its height.
    @Volatile private var padTop = 0f
    @Volatile private var padBottom = 1f

    override fun setPanelBounds(topFraction: Float, bottomFraction: Float) {
        padTop = topFraction
        padBottom = bottomFraction
        Log.d(TAG, "panel bounds $topFraction..$bottomFraction")
    }

    // The vertical extent of a panel, from `getevent -p`. There is no way to identify the
    // touchscreen up front at this uid, so every node is opened and asked; the ones that are
    // not touchscreens report nothing and are simply never filtered.
    private fun readAbsMax(path: String): Int = runCatching {
        val out = Runtime.getRuntime().exec(arrayOf("getevent", "-p", path))
            .inputStream.bufferedReader().readText()
        Regex("""0036\s*:\s*value\s*-?\d+,\s*min\s*-?\d+,\s*max\s*(\d+)""")
            .find(out)?.groupValues?.get(1)?.toInt() ?: 0
    }.getOrDefault(0)

    private class Contact {
        var x = 0
        var y = 0
        var downX = 0
        var downY = 0
        var downAt = 0L
        var prevX = 0
        var prevY = 0
        // The panel announces a contact's slot and tracking id BEFORE its coordinates, so
        // the starting point can only be recorded once a full frame has arrived. Taking it
        // any earlier captures the previous finger's position, which then reads as a huge
        // jump and marks the contact as wandering the moment it truly appears.
        var placed = false
        var strayed = false  // travelled beyond the slop, so it is a swipe, not a rest
        var outside = false  // landed off the touch surface — the keyboard, most often
    }

    private fun watchLoop(
        stream: java.io.FileInputStream,
        maxY: Int,
        sensitivity: Float,
        holdMs: Int,
        slopPx: Int,
    ) {
        val buf = ByteArray(EVENT_SIZE)
        val contacts = HashMap<Int, Contact>()
        var slot = 0
        var pendingX = 0
        var pendingY = 0
        var accX = 0f
        var accY = 0f
        var steering = false
        try {
            while (watching) {
                var read = 0
                while (read < EVENT_SIZE) {
                    val n = stream.read(buf, read, EVENT_SIZE - read)
                    if (n < 0) return
                    read += n
                }
                val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val type = bb.getShort(16).toInt()
                val code = bb.getShort(18).toInt() and 0xFFFF
                val value = bb.getInt(20)

                if (type == EV_KEY && code == BTN_TOUCH && value == 0) {
                    contacts.clear()
                    restingSince = 0L
                    steering = false
                    releaseHold()
                    continue
                }
                if (type == EV_ABS) {
                    when (code) {
                        ABS_MT_SLOT -> slot = value
                        ABS_MT_TRACKING_ID ->
                            if (value < 0) contacts.remove(slot) else contacts[slot] = Contact()
                        ABS_MT_POSITION_X -> contacts[slot]?.x = value
                        ABS_MT_POSITION_Y -> contacts[slot]?.y = value
                    }
                    continue
                }
                if (type != EV_SYN || code != SYN_REPORT) continue

                if (touchpadMode) {
                    relayFrame(contacts, maxY)
                    continue
                }

                for (c in contacts.values) {
                    if (!c.placed) {
                        // First complete frame for this contact: this is where it landed.
                        c.placed = true
                        c.downX = c.x
                        c.downY = c.y
                        c.prevX = c.x
                        c.prevY = c.y
                        c.downAt = System.currentTimeMillis()
                        // Only a finger that landed on the touch surface can hold the button.
                        // A key on the keyboard held down is a key, not a resting finger.
                        if (maxY > 0) {
                            val f = c.y.toFloat() / maxY
                            c.outside = f < padTop || f > padBottom
                        }
                        continue
                    }
                    val dist = kotlin.math.hypot((c.x - c.downX).toFloat(), (c.y - c.downY).toFloat())
                    if (dist > slopPx) c.strayed = true
                }

                // Publish what the ticker needs: a lone finger on the pad that has not
                // wandered off. Fingers outside the pad are not counted at all, so typing
                // with one hand does not stop the other from holding.
                val onPad = contacts.values.filter { !it.outside }
                val candidate = onPad.singleOrNull()?.takeIf { it.placed && !it.strayed }
                restingSince = candidate?.downAt ?: 0L

                if (leftHeld) {
                    if (!steering) {
                        // Start steering from where the fingers are now, so the press itself
                        // does not fling the cursor by the distance accumulated before it.
                        onPad.forEach { it.prevX = it.x; it.prevY = it.y }
                        steering = true
                    }
                    for (c in onPad) {
                        accX += (c.x - c.prevX) * sensitivity
                        accY += (c.y - c.prevY) * sensitivity
                        c.prevX = c.x
                        c.prevY = c.y
                    }
                    val ix = accX.toInt()
                    val iy = accY.toInt()
                    if (ix != 0 || iy != 0) {
                        accX -= ix
                        accY -= iy
                        if (ix != 0) ev(EV_REL, REL_X, ix)
                        if (iy != 0) ev(EV_REL, REL_Y, iy)
                        sync()
                    }
                } else {
                    steering = false
                }
            }
        } catch (_: Exception) {
        }
    }

    // Forwards one frame of real fingers to the virtual touchpad, so Android sees the phone's
    // glass as a trackpad and does the gesture work itself. Protocol B: a slot is addressed,
    // then its tracking id and coordinates follow.
    //
    // Fingers outside the touch surface are dropped rather than forwarded — the keyboard is
    // below it, and a key held down there is a key, not a finger on the pad.
    private var relaySlots = HashMap<Int, Boolean>()

    private fun relayFrame(contacts: Map<Int, Contact>, maxY: Int) {
        val onPad = contacts.filterValues { c ->
            c.placed && (maxY <= 0 || (c.y.toFloat() / maxY).let { it >= padTop && it <= padBottom })
        }

        // Retire slots whose finger has gone, then publish the ones still down.
        for ((slot, live) in relaySlots.entries.toList()) {
            if (live && !onPad.containsKey(slot)) {
                ev(EV_ABS, ABS_MT_SLOT, slot)
                ev(EV_ABS, ABS_MT_TRACKING_ID, -1)
                relaySlots[slot] = false
            }
        }
        for ((slot, c) in onPad) {
            if (slot >= MAX_SLOTS) continue
            ev(EV_ABS, ABS_MT_SLOT, slot)
            if (relaySlots[slot] != true) {
                ev(EV_ABS, ABS_MT_TRACKING_ID, slot)
                relaySlots[slot] = true
            }
            ev(EV_ABS, ABS_MT_POSITION_X, c.x.coerceIn(0, padMaxX))
            ev(EV_ABS, ABS_MT_POSITION_Y, c.y.coerceIn(0, padMaxY))
            ev(EV_ABS, ABS_MT_PRESSURE, 128)
        }

        // Android counts fingers from these buttons alone, not from the slots — a frame with
        // no BTN_TOOL_* set reads as nothing touching, however many slots are live.
        val n = onPad.size
        ev(EV_KEY, BTN_TOUCH, if (n > 0) 1 else 0)
        ev(EV_KEY, BTN_TOOL_FINGER, if (n == 1) 1 else 0)
        ev(EV_KEY, BTN_TOOL_DOUBLETAP, if (n == 2) 1 else 0)
        ev(EV_KEY, BTN_TOOL_TRIPLETAP, if (n == 3) 1 else 0)
        ev(EV_KEY, BTN_TOOL_QUADTAP, if (n >= 4) 1 else 0)
        sync()
    }

    private fun releaseHold() {
        if (!leftHeld) return
        leftHeld = false
        ev(EV_KEY, BTN_LEFT, 0); sync()
        Log.d(TAG, "hold: BTN_LEFT up")
    }

    // Closes the uinput file descriptor via JNI (sends UI_DEV_DESTROY internally) and marks
    // the device unavailable so subsequent calls are no-ops rather than crashing.
    override fun destroy() {
        UinputNative.nClose()
        uinputReady = false
    }

    companion object {
        private const val TAG = "MouseService"
        private const val NEBULA_PACKAGE = "com.xreal.evapro.nebula"
        // Where the two synthetic contacts start, and how close together they may get.
        private const val PINCH_START_SPAN = 140f
        private const val PINCH_MIN_SPAN = 24f
        private val WINDOWING_FLAGS = setOf(
            "force_desktop_mode_on_external_displays",
            "enable_freeform_support",
        )

        // How many fingers the pad reports, and the axis codes a touchpad needs.
        const val MAX_SLOTS = 5
        const val UI_SET_PROPBIT = 0x4004556e
        const val UI_SET_ABSBIT  = 0x40045567
        const val INPUT_PROP_POINTER = 0x00
        const val INPUT_PROP_BUTTONPAD = 0x02
        const val BTN_TOOL_FINGER = 0x145
        const val BTN_TOOL_DOUBLETAP = 0x14d
        const val BTN_TOOL_TRIPLETAP = 0x14e
        const val BTN_TOOL_QUADTAP = 0x14f
        const val ABS_MT_PRESSURE = 0x3a

        const val UI_SET_EVBIT  = 0x40045564
        const val UI_SET_KEYBIT = 0x40045565
        const val UI_SET_RELBIT = 0x40045566
        const val UI_DEV_CREATE  = 0x5501
        const val UI_DEV_DESTROY = 0x5502

        const val EV_SYN = 0; const val EV_KEY = 1; const val EV_REL = 2
        const val REL_X = 0; const val REL_Y = 1; const val REL_HWHEEL = 6; const val REL_WHEEL = 8
        const val BTN_LEFT = 0x110; const val BTN_RIGHT = 0x111; const val BTN_MIDDLE = 0x112
        const val KEY_BACK = 158; const val KEY_HOME = 102; const val KEY_APPSWITCH = 580
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14a
        const val EV_ABS = 3
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        // struct input_event on a 64-bit kernel: two 8-byte timeval fields, then
        // __u16 type, __u16 code, __s32 value.
        const val EVENT_SIZE = 24
    }
}
