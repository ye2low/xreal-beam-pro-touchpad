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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class TouchpadAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TouchpadAccessibilityService? = null
            private set

        // Invoked when an editable view on the external display gains focus.
        var onExternalTextFocus: (() -> Unit)? = null

        // The field last focused on the external display. Held on to deliberately: once the
        // phone's keyboard opens, focus moves to this app, so the node can no longer be
        // looked up — but it is still the field the user means to type into.
        private var remoteField: android.view.accessibility.AccessibilityNodeInfo? = null

        // Writes straight into that field. Going through the node rather than synthesising
        // key events is what makes non-Latin text work at all: a key event carries a key
        // code, and there is no key code for "я".
        fun writeRemoteText(text: String): Boolean {
            val node = remoteField ?: return false
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            return runCatching {
                node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args,
                )
            }.getOrDefault(false)
        }

        fun remoteFieldText(): String = remoteField?.text?.toString().orEmpty()

        var cursorX = 540f
        var cursorY = 960f
        var externalDisplayWidth = 1920
        var externalDisplayHeight = 1080
        var externalDisplayId = Display.DEFAULT_DISPLAY

        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 650L
        private const val DOUBLE_GAP_MS = 100L
        private const val SCROLL_MS = 300L
        private const val SCROLL_SCALE = 200f
        private const val CLICK_VIBRATION_MS = 15L
        private const val LONG_VIBRATION_MS = 40L
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var vibrator: Vibrator

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = detectExternalDisplay()
        override fun onDisplayRemoved(displayId: Int) = detectExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = detectExternalDisplay()
    }

    // Called by Android when the service is bound and ready. Initializes DisplayManager and
    // Vibrator, registers the display listener, and detects the external display immediately.
    override fun onServiceConnected() {
        instance = this
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        displayManager.registerDisplayListener(displayListener, null)
        detectExternalDisplay()
    }

    // Finds the first non-default display (the glasses); falls back to the phone's window
    // bounds if none is found. Updates the companion-object dimensions and recenters the cursor.
    private fun detectExternalDisplay() {
        val external = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (external != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            external.getMetrics(metrics)
            externalDisplayWidth = metrics.widthPixels
            externalDisplayHeight = metrics.heightPixels
            externalDisplayId = external.displayId
        } else {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            externalDisplayWidth = bounds.width()
            externalDisplayHeight = bounds.height()
            externalDisplayId = Display.DEFAULT_DISPLAY
        }
        cursorX = externalDisplayWidth / 2f
        cursorY = externalDisplayHeight / 2f
    }

    // Updates the companion-object cursor position by (dx, dy), clamped to the display bounds.
    // Used to keep the gesture-dispatch coordinates in sync with the ViewModel's cursor.
    fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, (externalDisplayWidth - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (externalDisplayHeight - 1).toFloat())
    }

    // Dispatches a TAP_MS-duration tap gesture at the current cursor position and vibrates briefly.
    fun performClick() {
        dispatchTap(cursorX, cursorY, TAP_MS)
        vibrate(CLICK_VIBRATION_MS)
    }

    // Dispatches two tap strokes separated by DOUBLE_GAP_MS in a single GestureDescription
    // so the system recognizes them as a double-tap rather than two independent taps.
    fun performDoubleClick() {
        val x = cursorX
        val y = cursorY
        val s1 = stroke(x, y, x, y, 0, TAP_MS)
        val s2 = stroke(x, y, x, y, TAP_MS + DOUBLE_GAP_MS, TAP_MS)
        dispatchGesture(buildGesture(s1, s2), null, null)
        vibrate(CLICK_VIBRATION_MS)
    }

    // Dispatches a LONG_PRESS_MS-duration stationary press at the cursor position.
    // Desktop environments map long-press to right-click/context menu.
    fun performRightClick() {
        // Long-press at cursor position — most desktop launchers map this to right-click
        val path = Path().apply { moveTo(cursorX, cursorY) }
        val s = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS)
        dispatchGesture(buildGesture(s), null, null)
        vibrate(LONG_VIBRATION_MS)
    }

    // Converts (dx, dy) finger-pixel deltas into a swipe gesture: start at cursor position,
    // end SCROLL_SCALE px away in the scroll direction. The swipe duration is SCROLL_MS.
    fun performScroll(dx: Float, dy: Float) {
        val endX = (cursorX - dx * SCROLL_SCALE).coerceIn(0f, externalDisplayWidth.toFloat())
        val endY = (cursorY - dy * SCROLL_SCALE).coerceIn(0f, externalDisplayHeight.toFloat())
        dispatchGesture(buildGesture(stroke(cursorX, cursorY, endX, endY, 0, SCROLL_MS)), null, null)
    }

    // Builds a single-point stroke at (x, y) with the given duration and dispatches it as a gesture.
    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(buildGesture(GestureDescription.StrokeDescription(path, 0, durationMs)), null, null)
    }

    // Creates a StrokeDescription for a straight-line path from (x1,y1) to (x2,y2),
    // starting at startMs offset and lasting durationMs within the gesture timeline.
    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, startMs: Long, durationMs: Long) =
        GestureDescription.StrokeDescription(
            Path().apply {
                moveTo(x1, y1)
                if (x1 != x2 || y1 != y2) lineTo(x2, y2)
            },
            startMs,
            durationMs,
        )

    // Assembles a GestureDescription from one or more strokes. On Android 12+ with an external
    // display, attempts to set the target displayId via GestureDescription.Builder.setDisplayId
    // reflection so gestures land on the glasses screen instead of the phone.
    private fun buildGesture(vararg strokes: GestureDescription.StrokeDescription): GestureDescription {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }

        // Best-effort: try to target the external display via hidden API (Android 12+)
        if (externalDisplayId != Display.DEFAULT_DISPLAY) {
            try {
                val m = GestureDescription.Builder::class.java
                    .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(builder, externalDisplayId)
            } catch (_: Exception) {}
        }

        return builder.build()
    }

    // Triggers a single haptic pulse of the given duration. Uses VibrationEffect on API 26+;
    // falls back to the deprecated Vibrator.vibrate(long) on older devices.
    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    // Receives all accessibility events; filters for TYPE_VIEW_FOCUSED on an editable,
    // visible view whose window is on a non-default display (i.e. the glasses). When matched,
    // fires onExternalTextFocus so the ViewModel can show the phone keyboard proxy.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        val source = event.source ?: return
        if (!source.isEditable || !source.isVisibleToUser) return
        val displayId = source.window?.displayId ?: Display.DEFAULT_DISPLAY
        if (displayId != Display.DEFAULT_DISPLAY) {
            remoteField = source
            onExternalTextFocus?.invoke()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

}

