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

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pgratz.artouchpad.adb.AdbPairingService
import com.pgratz.artouchpad.adb.ShizukuBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TouchMode { IDLE, CURSOR, SCROLL, SELECT }

// Anything that outlasts a short tap counts as holding the left button, so the threshold
// sits just past the tap window rather than at some longer "long press" delay. Movement
// still cancels it: a finger that sets off across the pad wants to steer the cursor, not
// to press anything.
private const val HOLD_TO_PRESS_MS = 240
private const val HOLD_SLOP_PX = 25

private const val NEBULA_PACKAGE = "com.xreal.evapro.nebula"

private const val KEY_DESKTOP_MODE = "force_desktop_mode_on_external_displays"
private const val KEY_FREEFORM = "enable_freeform_support"

// WindowManager DISPLAY_IME_POLICY_* values, passed to IWindowManager.setDisplayImePolicy.
private const val IME_POLICY_LOCAL = 0     // IME on the display that owns the focused field
private const val IME_POLICY_FALLBACK = 1  // IME on the default display (phone) — DeX-style

data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    // Effective density in dpi, override included. Read from the display rather than from
    // the saved preference: the glasses come back under a new display id every time they
    // are re-plugged or switch mode, and only the display itself knows what is in force.
    val density: Int = 0,
)

data class TouchpadState(
    val isServiceEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val mouseReady: Boolean = false,
    val allDisplays: List<DisplayInfo> = emptyList(),
    val targetDisplay: DisplayInfo? = null,
    val cursorX: Float = 0f,
    val cursorY: Float = 0f,
    val sensitivity: Float = 0.5f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = false,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    val showKeyboard: Boolean = false,
    // True while the on-screen left button holds BTN_LEFT down.
    val leftHeld: Boolean = false,
    // Self-start of Shizuku over this device's own adb. bootstrapStatus is a short line
    // shown under the status bar.
    val bootstrapStatus: String? = null,
    val bootstrapBusy: Boolean = false,
    // User preference: show the keyboard on the phone (IME fallback policy) instead of
    // on the glasses. dexKeyboardActive reflects whether the policy actually took effect
    // (false when `wm set-display-ime-policy` is unsupported on this build).
    val dexKeyboardEnabled: Boolean = true,
    val dexKeyboardActive: Boolean = false,
    // Density override applied to the glasses, in dpi; 0 means the panel's own value.
    // It is the only lever on the thickness of window title bars.
    val glassesDensity: Int = 0,
    // Whether XREAL's Nebula is enabled. It takes over the glasses whenever the desktop-mode
    // flag is off, so switching it off is what makes turning that flag off possible.
    val nebulaEnabled: Boolean = true,
    // The two developer options this whole setup rests on. Desktop mode is what puts a
    // desktop on the glasses at all — and also what forces the keyboard to stay there.
    val desktopMode: Boolean = false,
    val freeformWindows: Boolean = false,
) {
    val externalDisplayConnected get() = targetDisplay != null
    val displayWidth get() = targetDisplay?.width ?: 1920
    val displayHeight get() = targetDisplay?.height ?: 1080
}

class TouchpadViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("touchpad_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TouchpadState(
        sensitivity = prefs.getFloat("sensitivity", 0.5f),
        scrollSpeed = prefs.getFloat("scroll_speed", 0.8f),
        naturalScroll = prefs.getBoolean("natural_scroll", false),
        dexKeyboardEnabled = prefs.getBoolean("dex_keyboard", true),
        glassesDensity = prefs.getInt("glasses_density", 0),
    ))
    val state: StateFlow<TouchpadState> = _state.asStateFlow()

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val mouse = ShizukuMouseController()

    private var pinchAccum = 0f
    private var smoothDx = 0f
    private var smoothDy = 0f

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        mouse.init(::refresh)
        displayManager.registerDisplayListener(displayListener, null)
        refresh()
        mouse.bind()
        // Tapping a text field on the glasses raises the keyboard here, on the phone, by
        // giving focus to a hidden field inside this window. The keyboard follows the
        // window that owns the focused field, and this window lives on the phone — which
        // is the whole trick, since the system's own per-display IME policy is applied
        // successfully on this build and still leaves the keyboard on the glasses.
        TouchpadAccessibilityService.onExternalTextFocus = {
            _state.update { it.copy(showKeyboard = true) }
        }
        startShizukuIfNeeded()
    }

    // Nothing works until Shizuku is up, and after a reboot it never is. Called on every
    // return to the app rather than only at creation, because coming back to a process that
    // is still alive does not rebuild this ViewModel — which is exactly the case after
    // Shizuku has been killed while the app sat in the background.
    fun startShizukuIfNeeded() {
        if (!mouse.hasShizuku() && !_state.value.bootstrapBusy) startShizuku()
    }

    // Mirrors what is typed here into the field on the glasses. Text is written into the
    // field directly rather than replayed as key presses, so Cyrillic and anything else
    // without a key code arrives intact.
    fun writeRemoteText(text: String) {
        TouchpadAccessibilityService.writeRemoteText(text)
    }

    fun hideKeyboard() = _state.update { it.copy(showKeyboard = false) }

    // Enumerates all displays via DisplayManager; picks the first non-default display as the
    // target (glasses). Updates state with display list, cursor center, and all status flags.
    // Also calls setDisplay on MouseService so key/cursor events reach the right display.
    fun refresh() {
        val allDisplays = displayManager.displays.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            DisplayInfo(
                d.displayId,
                d.name ?: "Display ${d.displayId}",
                m.widthPixels,
                m.heightPixels,
                m.densityDpi,
            )
        }

        // Pick the external display: prefer any non-default display,
        // fall back to presentation category.
        val external = allDisplays.firstOrNull { it.id != Display.DEFAULT_DISPLAY }
            ?: run {
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull()
                    ?.let { d ->
                        val m = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        d.getMetrics(m)
                        DisplayInfo(
                            d.displayId,
                            d.name ?: "Presentation",
                            m.widthPixels,
                            m.heightPixels,
                            m.densityDpi,
                        )
                    }
            }

        if (external != null && mouse.isConnected) {
            mouse.setDisplay(external.id, external.width, external.height)
            applyImePolicy(external.id)
        }

        _state.update {
            it.copy(
                isServiceEnabled = TouchpadAccessibilityService.instance != null,
                shizukuAvailable = mouse.hasShizuku(),
                shizukuPermission = mouse.hasPermission(),
                mouseReady = mouse.hasPermission() && mouse.isConnected,
                leftHeld = it.leftHeld,
                allDisplays = allDisplays,
                targetDisplay = external,
                cursorX = if (external != null) external.width / 2f else it.cursorX,
                cursorY = if (external != null) external.height / 2f else it.cursorY,
                nebulaEnabled = readNebulaEnabled(),
                desktopMode = readFlag(KEY_DESKTOP_MODE),
                freeformWindows = readFlag(KEY_FREEFORM),
            )
        }

        ensurePanelWatch()
    }

    // Opens the Shizuku permission dialog so the user can grant shell-uid access.
    fun requestShizukuPermission() = mouse.requestPermission()

    // Shizuku is dead after every reboot and cannot restart itself without root, which used
    // to mean plugging the phone into a computer before the touchpad would work. Instead the
    // app talks to this device's own adbd over loopback and runs the starter — so opening
    // the app is the whole procedure. Called automatically on launch; the button in the
    // status bar just runs it again after a failure.
    fun startShizuku() {
        if (_state.value.bootstrapBusy) return
        _state.update { it.copy(bootstrapBusy = true, bootstrapStatus = "запускаю Shizuku…") }
        viewModelScope.launch {
            val result = ShizukuBootstrap.start(getApplication())
            val message = when (result) {
                ShizukuBootstrap.Result.Started -> "Shizuku запущен"
                ShizukuBootstrap.Result.AlreadyRunning -> null
                ShizukuBootstrap.Result.WirelessDebuggingOff ->
                    "включи отладку по Wi-Fi в настройках разработчика"
                ShizukuBootstrap.Result.PairingRequired ->
                    "нужно один раз связаться с отладкой по Wi-Fi — смотри уведомление"
                ShizukuBootstrap.Result.ShizukuNotInstalled -> "Shizuku не установлен"
                is ShizukuBootstrap.Result.Failed -> "не вышло: ${result.reason}"
            }
            // Pairing cannot happen in a window of ours: the system stops advertising its
            // pairing service as soon as its own dialog is backgrounded, so the code is
            // collected through a notification instead.
            if (result == ShizukuBootstrap.Result.PairingRequired) {
                AdbPairingService.start(getApplication())
            }
            _state.update { it.copy(bootstrapBusy = false, bootstrapStatus = message) }
            if (result == ShizukuBootstrap.Result.Started) {
                // The starter returns before Shizuku's binder is up.
                delay(1500)
                refresh()
                mouse.bind()
            }
        }
    }


    // Tracks the (displayId, policy) last applied successfully, so refresh() calls
    // (which fire on every display event) don't re-exec the wm command needlessly.
    private var appliedImePolicy: Pair<Int, Int>? = null

    // Applies the IME policy matching the dexKeyboardEnabled preference to the external
    // display: fallback → keyboard appears on the phone, local → on the glasses.
    // Runs on IO because MouseService execs a `wm` subprocess. dexKeyboardActive is set
    // only when the fallback policy actually took effect on this build.
    private fun applyImePolicy(displayId: Int) {
        val policy = if (_state.value.dexKeyboardEnabled) IME_POLICY_FALLBACK else IME_POLICY_LOCAL
        // Deliberately not skipped when it looks already applied. The external display is
        // re-registered under a new id whenever the glasses change mode, and the policy is
        // attached to the id — caching it once let the keyboard drift back onto the external
        // screen after such a change, with nothing in the log to show for it.
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mouse.setImePolicy(displayId, policy)
            appliedImePolicy = if (ok) displayId to policy else null
            _state.update { it.copy(dexKeyboardActive = ok && policy == IME_POLICY_FALLBACK) }
        }
    }

    // Input: raw pixel deltas from the touch event.
    // Applies velocity-adaptive exponential smoothing (heavy for slow/fine moves,
    // light for fast moves) before scaling by sensitivity. This suppresses finger
    // tremor on precise movements without adding noticeable lag on fast sweeps.
    fun moveCursor(rawDx: Float, rawDy: Float) {
        val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
        val alpha = (speed / 8f).coerceIn(0.30f, 0.85f)
        smoothDx = alpha * rawDx + (1f - alpha) * smoothDx
        smoothDy = alpha * rawDy + (1f - alpha) * smoothDy

        val sens = _state.value.sensitivity
        val dx = smoothDx * sens
        val dy = smoothDy * sens
        if (dx == 0f && dy == 0f) return
        mouse.moveMouse(dx, dy)

        val w = _state.value.displayWidth.toFloat()
        val h = _state.value.displayHeight.toFloat()
        _state.update {
            it.copy(
                cursorX = (it.cursorX + dx).coerceIn(0f, w - 1f),
                cursorY = (it.cursorY + dy).coerceIn(0f, h - 1f),
                touchMode = TouchMode.CURSOR,
            )
        }
    }

    // Updates touchMode in state, which drives the cursor/scroll indicator shown in the status bar.
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) { smoothDx = 0f; smoothDy = 0f }
        _state.update { it.copy(touchMode = mode) }
    }

    // Delegate clicks to MouseService at the current tracked cursor position.
    fun performClick() = mouse.click(_state.value.cursorX, _state.value.cursorY)
    fun performDoubleClick() = mouse.doubleClick(_state.value.cursorX, _state.value.cursorY)
    fun performRightClick() = mouse.rightClick(_state.value.cursorX, _state.value.cursorY)

    // The on-screen left button. The press starts here, but the release is decided by the
    // service watching the panel directly: Android takes this window's touch away about
    // 13 ms after BTN_LEFT goes down, so the UI would otherwise believe the finger had
    // already lifted. While held, the UI polls so the light matches the mouse button.
    // Starts the service watching the panel, and mirrors the button state into the UI so the
    // touchpad edges can light up. Polling is the only way: while the button is held the
    // window receives no touch at all, so it cannot know anything by itself.
    private var watchStarted = false

    private var holdPollJob: kotlinx.coroutines.Job? = null

    fun ensurePanelWatch() {
        if (watchStarted || !_state.value.mouseReady) return
        watchStarted = true
        mouse.startPanelWatch(_state.value.sensitivity, HOLD_TO_PRESS_MS, HOLD_SLOP_PX)
        holdPollJob?.cancel()
        holdPollJob = viewModelScope.launch {
            while (true) {
                delay(50)
                val held = mouse.isLeftHeld()
                if (held != _state.value.leftHeld) _state.update { it.copy(leftHeld = held) }
            }
        }
    }

    // Stops watching the panel while the touchpad is not on screen. The service reads
    // /dev/input directly, so without this it keeps treating any finger resting anywhere on
    // the phone as a held mouse button — scrolling a settings list would drag the cursor
    // around the glasses. stopPanelWatch releases the button first, so nothing is left down.
    fun pausePanelWatch() {
        if (!watchStarted) return
        watchStarted = false
        holdPollJob?.cancel()
        holdPollJob = null
        mouse.stopPanelWatch()
        _state.update { it.copy(leftHeld = false) }
    }

    // Presses BTN_LEFT without releasing; moveCursor calls while held extend a text selection.
    fun startSelectDrag() = mouse.mouseDown()

    // Releases BTN_LEFT to end the selection drag, then immediately injects Ctrl+C to copy
    // the selection into the clipboard before any cursor movement can clear it.
    fun endSelectDrag() {
        mouse.mouseUp()
        mouse.pressKeyWithCtrl(android.view.KeyEvent.KEYCODE_C)
    }

    // Applies scrollSpeed multiplier and natural-scroll direction inversion, then
    // forwards the adjusted delta to MouseService for wheel-detent conversion.
    fun performScroll(dx: Float, dy: Float) {
        val speed = _state.value.scrollSpeed
        val dir = if (_state.value.naturalScroll) 1f else -1f
        mouse.scroll(dx * speed * dir, dy * speed * dir)
        _state.update { it.copy(touchMode = TouchMode.SCROLL) }
    }

    // Forwards an Android keycode to MouseService for injection on the glasses display.
    fun pressKey(linuxKeyCode: Int) = mouse.pressKey(linuxKeyCode)
    // Converts text to key events and injects them to the focused window on the glasses display.
    fun typeText(text: String) = mouse.typeText(text)

    // Input: dDist — span change in pixels this frame (positive = spreading = zoom in).
    // Accumulates until 200 px threshold to avoid jitter; each 200 px = 1 AXIS_VSCROLL detent,
    // which Chrome/WebView maps to one zoom step (~10%) without affecting the system font scale.
    fun pinchZoom(dDist: Float) {
        pinchAccum += dDist
        val detents = (pinchAccum / 200f).toInt()
        if (detents != 0) {
            pinchAccum -= detents * 200f
            mouse.ctrlScroll(detents.toFloat())
        }
    }
    // Toggles showKeyboard in state, which shows or hides the KeyboardProxy strip in the UI.
    fun toggleKeyboard() = _state.update { it.copy(showKeyboard = !it.showKeyboard) }

    // Input: text accumulated in the phone keyboard proxy.
    // Dismisses the phone keyboard first (to avoid IME session conflicts), waits 200 ms for
    // the IME to tear down, then injects the text followed by Enter to the glasses display.
    fun sendKeyboardText(text: String) {
        if (text.isEmpty()) { toggleKeyboard(); return }
        toggleKeyboard()
        viewModelScope.launch {
            delay(200)
            mouse.typeText(text)
            mouse.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
        }
    }

    // Delegates an AccessibilityService global action (e.g. GLOBAL_ACTION_BACK) to the service instance.
    fun performGlobalAction(action: Int) =
        TouchpadAccessibilityService.instance?.performGlobalAction(action)

    // Settings state updaters — each writes one field into TouchpadState and persists it
    // so tuned values survive app restarts.
    fun setSensitivity(v: Float) {
        prefs.edit().putFloat("sensitivity", v).apply()
        _state.update { it.copy(sensitivity = v) }
    }
    fun setScrollSpeed(v: Float) {
        prefs.edit().putFloat("scroll_speed", v).apply()
        _state.update { it.copy(scrollSpeed = v) }
    }
    fun setNaturalScroll(v: Boolean) {
        prefs.edit().putBoolean("natural_scroll", v).apply()
        _state.update { it.copy(naturalScroll = v) }
    }
    fun setDexKeyboard(v: Boolean) {
        prefs.edit().putBoolean("dex_keyboard", v).apply()
        _state.update { it.copy(dexKeyboardEnabled = v) }
        _state.value.targetDisplay?.let { applyImePolicy(it.id) }
    }
    // Opens the phone's task switcher. Tasks left behind on the phone blank its home screen,
    // and this is how they get swiped away.
    fun showRecents() = mouse.showRecents()

    // Density of the glasses display, in dpi; 0 restores the panel's own value. Measured on
    // this device: a window caption is 56 px at the panel's own 213 dpi and 42 px at 160,
    // which is what Samsung uses for DeX. Any value is allowed — the framework refuses
    // anything below 72 — but density scales everything on the display by the same factor,
    // so it buys workspace rather than thinner captions relative to their content.
    fun setGlassesDensity(density: Int) {
        val value = if (density <= 0) 0 else density.coerceIn(72, 640)
        prefs.edit().putInt("glasses_density", value).apply()
        _state.update { it.copy(glassesDensity = value) }
        val display = _state.value.targetDisplay ?: return
        viewModelScope.launch(Dispatchers.IO) {
            mouse.setDisplayDensity(display.id, value)
            // The display reports its new density only after the change lands.
            delay(600)
            refresh()
        }
    }

    // Reads Nebula's current state straight from the package manager, so the switch reflects
    // the device rather than a remembered value — it can be changed from Android's own app
    // settings just as well. MATCH_UNINSTALLED_PACKAGES is needed because a disabled package
    // is otherwise invisible.
    private fun readNebulaEnabled(): Boolean = runCatching {
        getApplication<Application>().packageManager
            .getApplicationInfo(NEBULA_PACKAGE, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .enabled
    }.getOrDefault(true)

    fun setNebulaEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            mouse.setNebulaEnabled(enabled)
            _state.update { it.copy(nebulaEnabled = readNebulaEnabled()) }
        }
    }

    // Global settings are readable by any app; only writing them needs shell.
    private fun readFlag(key: String): Boolean =
        Settings.Global.getInt(getApplication<Application>().contentResolver, key, 0) == 1

    fun setDesktopMode(enabled: Boolean) = setFlag(KEY_DESKTOP_MODE, enabled)
    fun setFreeformWindows(enabled: Boolean) = setFlag(KEY_FREEFORM, enabled)

    private fun setFlag(key: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            mouse.setWindowingFlag(key, enabled)
            _state.update {
                it.copy(
                    desktopMode = readFlag(KEY_DESKTOP_MODE),
                    freeformWindows = readFlag(KEY_FREEFORM),
                )
            }
        }
    }

    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

    // Cleans up the accessibility callback, display listener, and mouse service when the
    // ViewModel is destroyed (e.g. app process ends or activity is permanently finished).
    override fun onCleared() {
        TouchpadAccessibilityService.onExternalTextFocus = null
        displayManager.unregisterDisplayListener(displayListener)
        // Restore the glasses-side IME before the service goes away. Synchronous call:
        // viewModelScope is already cancelled by the time onCleared runs.
        if (appliedImePolicy?.second == IME_POLICY_FALLBACK) {
            _state.value.targetDisplay?.let { mouse.setImePolicy(it.id, IME_POLICY_LOCAL) }
        }
        mouse.destroy()
    }
}
