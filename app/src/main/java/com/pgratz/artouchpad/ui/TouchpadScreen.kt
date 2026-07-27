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

package com.pgratz.artouchpad.ui

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as AKeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pgratz.artouchpad.DisplayInfo
import com.pgratz.artouchpad.TouchMode
import com.pgratz.artouchpad.TouchpadViewModel
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF1A2332)
private val SURFACE_DISABLED = Color(0xFF111820)
private val ACCENT = Color(0xFF4FC3F7)
private val ACCENT_DIM = Color(0xFF1A4A6A)
private val TEXT = Color(0xFFE0E0E0)
private val TEXT_DIM = Color(0xFF90A4AE)
private val TEXT_MUTED = Color(0xFF546E7A)
private val NAV_ICON = Color(0xFFB0BEC5)

private const val MOVE_THRESHOLD = 5f
private const val TAP_MAX_MS = 220L
private const val LONG_PRESS_MS = 600L
private const val DOUBLE_TAP_WINDOW_MS = 300L

// Root composable. Collects ViewModel state and renders either SettingsPanel (when
// showSettings is true) or the main layout: StatusBar → TouchpadSurface → optional
// KeyboardProxy → NavigationBar.
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TouchpadScreen(viewModel: TouchpadViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val imeVisible = WindowInsets.isImeVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
            // Standard behaviour: when the keyboard comes up, everything above it shrinks
            // to fit, so the touchpad simply gets shorter instead of being covered.
            .imePadding(),
    ) {
        StatusBar(
            shizukuAvailable = state.shizukuAvailable,
            shizukuPermission = state.shizukuPermission,
            mouseReady = state.mouseReady,
            serviceEnabled = state.isServiceEnabled,
            targetDisplay = state.targetDisplay,
            touchMode = state.touchMode,
            bootstrapBusy = state.bootstrapBusy,
            bootstrapStatus = state.bootstrapStatus,
            onBack = { viewModel.pressKey(AKeyEvent.KEYCODE_BACK) },
            onRecents = viewModel::showRecents,
            onSettingsClick = viewModel::toggleSettings,
            onStartShizuku = viewModel::startShizuku,
            onGrantShizuku = viewModel::requestShizukuPermission,
            onConnectMouse = { viewModel.mouse.bind() },
            onEnableService = {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        if (state.showSettings) {
            SettingsPanel(
                sensitivity = state.sensitivity,
                scrollSpeed = state.scrollSpeed,
                naturalScroll = state.naturalScroll,
                dexKeyboard = state.dexKeyboardEnabled,
                dexKeyboardActive = state.dexKeyboardActive,
                allDisplays = state.allDisplays,
                targetDisplay = state.targetDisplay,
                onSensitivity = viewModel::setSensitivity,
                onScrollSpeed = viewModel::setScrollSpeed,
                onNaturalScroll = viewModel::setNaturalScroll,
                scrollInertia = state.scrollInertia,
                onScrollInertia = viewModel::setScrollInertia,
                smoothScroll = state.smoothScroll,
                onSmoothScroll = viewModel::setSmoothScroll,
                smoothZoom = state.smoothZoom,
                onSmoothZoom = viewModel::setSmoothZoom,
                inertiaSeconds = state.inertiaSeconds,
                onInertiaSeconds = viewModel::setInertiaSeconds,
                onDexKeyboard = viewModel::setDexKeyboard,
                // What the display actually reports, not what was last saved.
                glassesDensity = state.targetDisplay?.density ?: 0,
                onGlassesDensity = viewModel::setGlassesDensity,
                nebulaEnabled = state.nebulaEnabled,
                onNebulaEnabled = viewModel::setNebulaEnabled,
                desktopMode = state.desktopMode,
                onDesktopMode = viewModel::setDesktopMode,
                freeformWindows = state.freeformWindows,
                onFreeformWindows = viewModel::setFreeformWindows,
                onDismiss = viewModel::toggleSettings,
            )
        } else {
            TouchpadSurface(
                modifier = Modifier.weight(1f),
                enabled = state.mouseReady,
                leftHeld = state.leftHeld,
                onMoveCursor = viewModel::moveCursor,
                onClick = { viewModel.performClick() },
                onDoubleClick = { viewModel.performDoubleClick() },
                onScroll = viewModel::performScroll,
                onPinch = viewModel::pinchZoom,
                onTouchModeChanged = viewModel::setTouchMode,
                onBoundsChanged = viewModel::setPadBounds,
            )
            // No navigation row: the glasses already take system swipes, so on-screen
            // Back/Home/Apps only ate height that the touchpad can use.

            // Clipboard keys, only while the keyboard is up. They sit between the pad and
            // the keyboard and take their strip from the pad, so the keyboard is not
            // covered. Typing on the glasses is the one time these are wanted and the one
            // time the pad can spare the room.
            if (imeVisible) {
                ClipboardBar(
                    onCopy = { viewModel.clipboard(AKeyEvent.KEYCODE_C) },
                    onCut = { viewModel.clipboard(AKeyEvent.KEYCODE_X) },
                    onPaste = { viewModel.clipboard(AKeyEvent.KEYCODE_V) },
                )
            }
        }
    }
}

// Copy / Cut / Paste, sent as Ctrl+C/X/V to the focused field on the glasses — the same
// route the text-selection drag already uses.
@Composable
private fun ClipboardBar(onCopy: () -> Unit, onCut: () -> Unit, onPaste: () -> Unit) {
    // 7 mm of actual glass, worked out from the panel's own dpi rather than assumed, so the
    // keys are the same size on a phone with a different pixel density.
    val ydpi = LocalContext.current.resources.displayMetrics.ydpi
    val height = with(LocalDensity.current) { (7f / 25.4f * ydpi).toDp() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("Copy" to onCopy, "Cut" to onCut, "Paste" to onPaste).forEach { (label, action) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SURFACE)
                    .clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = TEXT_DIM, fontSize = 13.sp)
            }
        }
    }
}

// Top status bar showing the app title, three status dots (Mouse/Display/Nav),
// the current touch mode indicator, and contextual action buttons for each
// unmet setup step (grant Shizuku → connect mouse → enable accessibility service).
@Composable
private fun StatusBar(
    shizukuAvailable: Boolean,
    shizukuPermission: Boolean,
    mouseReady: Boolean,
    serviceEnabled: Boolean,
    targetDisplay: DisplayInfo?,
    touchMode: TouchMode,
    bootstrapBusy: Boolean,
    bootstrapStatus: String?,
    onBack: () -> Unit,
    onRecents: () -> Unit,
    onSettingsClick: () -> Unit,
    onStartShizuku: () -> Unit,
    onGrantShizuku: () -> Unit,
    onConnectMouse: () -> Unit,
    onEnableService: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    androidx.compose.ui.res.stringResource(com.pgratz.artouchpad.R.string.app_name),
                    color = TEXT,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(mouseReady, "Mouse")
                    StatusDot(targetDisplay != null, "Display")
                    StatusDot(serviceEnabled, "Nav")
                    val modeLabel = when (touchMode) {
                        TouchMode.SCROLL -> "↕ scroll"
                        TouchMode.SELECT -> "⊹ select"
                        TouchMode.CURSOR -> "⊹ cursor"
                        TouchMode.IDLE   -> null
                    }
                    modeLabel?.let { Text(it, color = ACCENT, fontSize = 11.sp) }
                }
            }
            // The one navigation key that cannot be replaced by a swipe. Swiping on the
            // phone drives whatever is on the phone's own screen; this Back is injected at
            // the display the cursor lives on, so it goes back inside the app being worked
            // on in the glasses. Home and recents there are reachable by other means.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Text("◀", color = NAV_ICON, fontSize = 20.sp)
                }
                // The phone's own task switcher. A window that moves to the glasses can
                // leave its task behind on the phone, where it keeps covering the home
                // screen with a blank frame; this is how those get swiped away.
                IconButton(onClick = onRecents, modifier = Modifier.size(44.dp)) {
                    Text("▢", color = NAV_ICON, fontSize = 20.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    // Shizuku dies on every reboot and cannot come back on its own without
                    // root, so this is the normal state after a restart rather than an
                    // error. Tapping starts it through the phone's own adb.
                    !shizukuAvailable ->
                        TextButton(
                            onClick = onStartShizuku,
                            enabled = !bootstrapBusy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (bootstrapBusy) "starting…" else "Start Shizuku",
                                color = Color(0xFFFF7043),
                                fontSize = 12.sp,
                            )
                        }
                    !shizukuPermission ->
                        TextButton(onClick = onGrantShizuku, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Grant Shizuku", color = Color(0xFFFF7043), fontSize = 12.sp)
                        }
                    !mouseReady ->
                        TextButton(onClick = onConnectMouse, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Connect", color = Color(0xFFFFB300), fontSize = 12.sp)
                        }
                    !serviceEnabled ->
                        TextButton(onClick = onEnableService, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Enable Nav", color = Color(0xFFFFB300), fontSize = 12.sp)
                        }
                }
                IconButton(onClick = onSettingsClick) {
                    Text("⚙", color = TEXT_DIM, fontSize = 22.sp)
                }
            }
        }

        bootstrapStatus?.let {
            Text(
                it,
                color = TEXT_MUTED,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    }
}


// Interface scale on the glasses: a density override on that display alone, the phone
// untouched. Density is the only thing window captions respond to — their height is a fixed
// 42dp in the framework — but it scales everything else by the same factor, so what it
// really buys is workspace: measured here, the same window fits 5 rows at 213 dpi and 9
// at 160.
//
// The field shows what the display currently reports rather than what was last saved. The
// glasses come back under a new display id on every re-plug and mode switch, so the saved
// number and reality can drift apart; the display is the one that knows.
@Composable
private fun GlassesScale(current: Int, onChange: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(if (current > 0) current.toString() else "") }
    val entered = text.toIntOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Interface scale", color = TEXT_DIM, fontSize = 14.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TEXT,
                    unfocusedTextColor = TEXT,
                    focusedBorderColor = ACCENT,
                    unfocusedBorderColor = ACCENT_DIM,
                ),
            )
            TextButton(
                onClick = { entered?.let(onChange) },
                enabled = entered != null && entered != current,
            ) {
                Text("Apply", color = if (entered != null) ACCENT else TEXT_MUTED, fontSize = 13.sp)
            }
        }
        Hint("213 native · 160 like DeX · 120 very small. Lower means smaller and more room; " +
            "the resolution never changes. Below 72 is refused.")
    }
}

// Version and build number, read from the package manager rather than BuildConfig so that
// what is shown is the build actually installed.
private fun appVersion(context: android.content.Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName} (${info.longVersionCode})"
}.getOrDefault("")

// Section heading, so the list reads as groups rather than one long ladder of switches.
@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = ACCENT,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// Hint line. lineHeight is set explicitly: the default leading for 11.sp is generous enough
// that a two-line hint took as much room as the setting it belonged to.
@Composable
private fun Hint(text: String) {
    Text(text, color = TEXT_MUTED, fontSize = 11.sp, lineHeight = 13.sp)
}

// A titled switch with an explanatory line under it.
@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = TEXT_DIM, fontSize = 14.sp)
            Hint(subtitle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ACCENT,
                checkedTrackColor = ACCENT_DIM,
            ),
        )
    }
}

// Small colored circle (green = active, gray = inactive) followed by a text label.
// Used in StatusBar to show Mouse/Display/Nav readiness at a glance.
@Composable
private fun StatusDot(active: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF4CAF50) else Color(0xFF424242))
        )
        Text(label, color = TEXT_MUTED, fontSize = 11.sp)
    }
}

// The main touch surface. Intercepts raw pointer events with a single pointerInput handler:
//   1 finger: tap (click), double-tap, long-press (right-click), long-press+drag (select text),
//             or drag (cursor move).
//   2 fingers: pinch (spread > translate → font zoom) or drag (translate > spread → scroll).
// Renders a dot grid and live touch point indicators on a Canvas.
@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    enabled: Boolean,
    leftHeld: Boolean,
    onMoveCursor: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onTouchModeChanged: (TouchMode) -> Unit,
    onBoundsChanged: (Float, Float) -> Unit,
) {
    var touchPoints by remember { mutableStateOf(listOf<Offset>()) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier.padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // The service watches the whole touchscreen and knows nothing about windows,
                // so it is told where this surface actually sits. Its bottom edge moves up
                // when the keyboard appears, and holding a key down there must stay a key
                // press rather than becoming a held mouse button.
                .onGloballyPositioned {
                    val top = it.positionInWindow().y
                    onBoundsChanged(top, top + it.size.height)
                }
                .clip(RoundedCornerShape(20.dp))
                .background(if (enabled) SURFACE else SURFACE_DISABLED)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    var lastPositions = mapOf<PointerId, Offset>()
                    var downTime = 0L
                    var didMove = false
                    var lastTapTime = 0L
                    var lastLoggedFingers = -1

                    coroutineScope {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val now = System.currentTimeMillis()
                                val pressed = event.changes.filter { it.pressed }
                                val justPressed = event.changes.filter { it.pressed && !it.previousPressed }
                                val justReleased = event.changes.filter { !it.pressed && it.previousPressed }

                                touchPoints = pressed.map { it.position }

                                // Diagnostic: how many fingers this surface actually sees.
                                // Kept on because the window goes deaf whenever BTN_LEFT is
                                // held, and this is the only way to tell "no events arrived"
                                // apart from "events arrived and we mishandled them".
                                if (pressed.size != lastLoggedFingers) {
                                    lastLoggedFingers = pressed.size
                                    android.util.Log.d(
                                        "TouchpadSurface",
                                        "fingers=${pressed.size} down=${justPressed.size} up=${justReleased.size}",
                                    )
                                }

                                if (justPressed.isNotEmpty() && pressed.size == 1) {
                                    downTime = now
                                    didMove = false
                                    lastPositions = pressed.associate { it.id to it.position }
                                    // No long-press timer: a real touchpad has no such
                                    // gesture, and here it only got in the way — resting a
                                    // finger buzzed and then behaved like a stray click.
                                }

                                when (pressed.size) {
                                    1 -> {
                                        val p = pressed.first()
                                        val last = lastPositions[p.id]
                                        if (last != null) {
                                            val dx = p.position.x - last.x
                                            val dy = p.position.y - last.y
                                            val moved = abs(dx) > MOVE_THRESHOLD || abs(dy) > MOVE_THRESHOLD

                                            if (!didMove && moved) didMove = true
                                            if (didMove) {
                                                onMoveCursor(dx, dy)
                                                onTouchModeChanged(TouchMode.CURSOR)
                                            }
                                        }
                                        lastPositions = pressed.associate { it.id to it.position }
                                        p.consume()
                                    }
                                    2 -> {
                                        val newPositions = pressed.associate { it.id to it.position }
                                        if (lastPositions.size == 2) {
                                            val ids = pressed.map { it.id }
                                            val p0prev = lastPositions[ids[0]]
                                            val p1prev = lastPositions[ids[1]]
                                            val p0curr = newPositions[ids[0]]
                                            val p1curr = newPositions[ids[1]]
                                            if (p0prev != null && p1prev != null && p0curr != null && p1curr != null) {
                                                val dx = ((p0curr.x - p0prev.x) + (p1curr.x - p1prev.x)) / 2f
                                                val dy = ((p0curr.y - p0prev.y) + (p1curr.y - p1prev.y)) / 2f
                                                val pdx = p1prev.x - p0prev.x; val pdy = p1prev.y - p0prev.y
                                                val cdx = p1curr.x - p0curr.x; val cdy = p1curr.y - p0curr.y
                                                val dSpan = sqrt(cdx * cdx + cdy * cdy) - sqrt(pdx * pdx + pdy * pdy)
                                                // Fingers changing their separation faster than
                                                // they travel together is a pinch; otherwise both
                                                // are going the same way, which is a scroll.
                                                if (abs(dSpan) > abs(dx) + abs(dy)) {
                                                    if (dSpan != 0f) { onPinch(dSpan); didMove = true }
                                                } else if (dx != 0f || dy != 0f) {
                                                    onScroll(dx, dy)
                                                    onTouchModeChanged(TouchMode.SCROLL)
                                                    didMove = true
                                                }
                                            }
                                        }
                                        lastPositions = newPositions
                                        pressed.forEach { it.consume() }
                                    }
                                }

                                if (justReleased.isNotEmpty() && pressed.isEmpty()) {
                                    val duration = now - downTime
                                    onTouchModeChanged(TouchMode.IDLE)

                                    // A quick tap that went nowhere is a left click, the same
                                    // as tapping a physical touchpad. Holding still is not a
                                    // gesture here — that is what the button below is for.
                                    if (!didMove && duration < TAP_MAX_MS) {
                                        if (now - lastTapTime < DOUBLE_TAP_WINDOW_MS) {
                                            onDoubleClick()
                                            lastTapTime = 0L
                                        } else {
                                            onClick()
                                            lastTapTime = now
                                        }
                                    }
                                    lastPositions = emptyMap()
                                    touchPoints = emptyList()
                                }
                            }
                        }
                    }
                },
        ) {
            // Edges glow while the left button is held — the only outward sign that the
            // button is down, since the finger doing it looks like any other.
            if (leftHeld) {
                val w = 10.dp.toPx()
                drawRect(
                    color = ACCENT,
                    topLeft = Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    style = Stroke(width = w),
                )
            }

            // Dot grid
            val spacing = 32.dp.toPx()
            val cols = (size.width / spacing).toInt() + 1
            val rows = (size.height / spacing).toInt() + 1
            val xOffset = (size.width - (cols - 1) * spacing) / 2f
            val yOffset = (size.height - (rows - 1) * spacing) / 2f
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    drawCircle(
                        color = Color(0xFF263545),
                        radius = 1.8.dp.toPx(),
                        center = Offset(xOffset + col * spacing, yOffset + row * spacing),
                    )
                }
            }

            if (!enabled) return@Canvas

            // Live touch points
            touchPoints.forEach { pt ->
                drawCircle(color = ACCENT.copy(alpha = 0.2f), radius = 28.dp.toPx(), center = pt)
                drawCircle(color = ACCENT, radius = 6.dp.toPx(), center = pt)
                drawCircle(
                    color = ACCENT.copy(alpha = 0.5f),
                    radius = 18.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        // Disabled overlay text (outside Canvas, inside Box)
        if (!enabled) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Touchpad Disabled", color = TEXT_MUTED, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Grant Shizuku permission to activate", color = Color(0xFF37474F), fontSize = 14.sp)
            }
        }
    }
}

// The left mouse button, sitting between the touchpad surface and the navigation row.
// BTN_LEFT is held for exactly as long as a finger rests here, so the other hand can drag
// windows or their edges — dragging on Android 14 needs a held button, and a touchpad
// gesture cannot supply one (tap-and-drag only arrives in Android 15).
@Composable
private fun LeftMouseButton(
    enabled: Boolean,
    held: Boolean,
    onDown: (buttonTopY: Int) -> Unit,
) {
    // Where this button sits on the panel, in screen rows. The service needs it to tell the
    // finger that is holding the button from the finger that is steering.
    var topY by remember { mutableStateOf(Int.MAX_VALUE) }
    // The same press tracking the navigation buttons below use, rather than a hand-rolled
    // pointer loop: the framework owns the press state, so the button lights up and holds
    // exactly like every other button in the app.
    val interaction = remember { MutableInteractionSource() }
    val touching by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // A finger landing starts the hold; the service ends it when the panel says every
    // finger is gone. `held` therefore comes from the mouse button's real state, not from
    // this window's touch — which Android revokes the instant the button goes down.
    LaunchedEffect(touching) {
        if (touching) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onDown(topY)
        }
    }

    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .onGloballyPositioned { topY = it.positionInWindow().y.toInt() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    !enabled -> SURFACE_DISABLED
                    held -> ACCENT
                    else -> SURFACE
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (held) "ДЕРЖУ" else "левая кнопка",
            color = if (held) Color(0xFF06202E) else TEXT_DIM,
            fontSize = 15.sp,
            fontWeight = if (held) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// A thin input strip containing an Android EditText that hosts the system keyboard (Gboard).
// Text accumulates in the EditText; tapping ↵ or the send button calls onSend with the full
// string so it can be injected to the glasses after the phone IME is dismissed.
// Accumulates on the phone so Gboard swipe/autocorrect work normally without interfering
// with the glasses display's IME session.
@Composable
private fun KeyboardProxy(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val editRef = remember { mutableStateOf<EditText?>(null) }

    val doSend: () -> Unit = {
        val text = editRef.value?.text?.toString() ?: ""
        editRef.value?.setText("")
        onSend(text)
    }

    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    hint = "Type here, then tap ↵"
                    setHintTextColor(android.graphics.Color.parseColor("#546E7A"))
                    setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    maxLines = 2
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
                    }
                }
            },
            update = { view ->
                editRef.value = view
                view.requestFocus()
                val imm = view.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = doSend, modifier = Modifier.size(40.dp)) {
            Text("↵", color = ACCENT, fontSize = 20.sp)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Text("✕", color = TEXT_DIM, fontSize = 18.sp)
        }
    }
}

// An invisible one-line field that exists only to own the keyboard. Because it lives in
// this window, and this window is on the phone, the keyboard opens on the phone — while the
// text goes to the field the user actually tapped, over on the glasses.
@Composable
private fun RemoteTextInput(
    onText: (String) -> Unit,
    onDone: () -> Unit,
) {
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextColor(android.graphics.Color.TRANSPARENT)
                isCursorVisible = false
                isFocusable = true
                isFocusableInTouchMode = true
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    // Every keystroke is mirrored immediately, so the text appears in the
                    // remote field as it is typed instead of arriving in one lump.
                    override fun afterTextChanged(s: android.text.Editable?) {
                        onText(s?.toString().orEmpty())
                    }
                })
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) { onDone(); true } else false
                }
            }
        },
        update = { view ->
            view.requestFocus()
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp),
    )
}

// Full-screen settings overlay (shown instead of the touchpad when gear is tapped).
// Contains cursor/scroll speed sliders, natural scroll toggle, connected display list,
// and a gesture reference guide. Dismissed via the "Done" button.
@Composable
private fun SettingsPanel(
    sensitivity: Float,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    dexKeyboard: Boolean,
    dexKeyboardActive: Boolean,
    allDisplays: List<DisplayInfo>,
    targetDisplay: DisplayInfo?,
    onSensitivity: (Float) -> Unit,
    onScrollSpeed: (Float) -> Unit,
    onNaturalScroll: (Boolean) -> Unit,
    scrollInertia: Boolean,
    onScrollInertia: (Boolean) -> Unit,
    smoothScroll: Boolean,
    onSmoothScroll: (Boolean) -> Unit,
    smoothZoom: Boolean,
    onSmoothZoom: (Boolean) -> Unit,
    inertiaSeconds: Float,
    onInertiaSeconds: (Float) -> Unit,
    onDexKeyboard: (Boolean) -> Unit,
    glassesDensity: Int,
    onGlassesDensity: (Int) -> Unit,
    nebulaEnabled: Boolean,
    onNebulaEnabled: (Boolean) -> Unit,
    desktopMode: Boolean,
    onDesktopMode: (Boolean) -> Unit,
    freeformWindows: Boolean,
    onFreeformWindows: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The list outgrew the screen once the windowing switches arrived, and without
            // this everything below the fold was simply unreachable.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Settings", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(appVersion(LocalContext.current), color = TEXT_MUTED, fontSize = 11.sp)
            }
            TextButton(onClick = onDismiss) {
                Text("Done", color = ACCENT, fontSize = 14.sp)
            }
        }

        SectionHeader("Pointer")
        SettingSlider("Cursor speed", sensitivity, 0.4f..2.0f, "%.1f×", onSensitivity)

        SectionHeader("Scrolling")
        SettingSlider("Scroll speed", scrollSpeed, 0.3f..1.3f, "%.1f×", onScrollSpeed)
        SettingSwitch(
            title = "Natural scroll",
            subtitle = "Content follows the fingers, as on a phone",
            checked = naturalScroll,
            onChange = onNaturalScroll,
        )
        SettingSwitch(
            title = "Smooth scrolling",
            subtitle = "Fractional scrolling, injected past the mouse wheel. The wheel can " +
                "only step; this does not.",
            checked = smoothScroll,
            onChange = onSmoothScroll,
        )
        SettingSwitch(
            title = "Smooth zoom",
            subtitle = "Pinch as a real two-finger gesture on the glasses. Off, zoom goes " +
                "through Ctrl+wheel, which Chrome snaps to fixed steps.",
            checked = smoothZoom,
            onChange = onSmoothZoom,
        )
        SettingSwitch(
            title = "Scroll inertia",
            subtitle = "Keeps moving after the fingers leave; touch the pad to catch it",
            checked = scrollInertia,
            onChange = onScrollInertia,
        )
        if (scrollInertia) {
            SettingSlider("Coast time", inertiaSeconds, 0.3f..4.0f, "%.1f s", onInertiaSeconds)
        }

        SectionHeader("Glasses")
        GlassesScale(current = glassesDensity, onChange = onGlassesDensity)
        SettingSwitch(
            title = "Force desktop mode",
            subtitle = "Desktop on the glasses. While on, the system keeps the keyboard " +
                "there whatever an app asks for. Applies on reconnect.",
            checked = desktopMode,
            onChange = onDesktopMode,
        )
        SettingSwitch(
            title = "Freeform windows",
            subtitle = "Title bars, moving and resizing. Survives desktop mode being off.",
            checked = freeformWindows,
            onChange = onFreeformWindows,
        )
        SettingSwitch(
            title = "Nebula",
            subtitle = "Off, it stops seizing the glasses. Check it is not what switches " +
                "your display modes.",
            checked = nebulaEnabled,
            onChange = onNebulaEnabled,
        )

        SectionHeader("Keyboard")
        SettingSwitch(
            title = "Keyboard on phone",
            subtitle = when {
                dexKeyboard && dexKeyboardActive ->
                    "Glasses text fields open the phone keyboard, DeX style"
                dexKeyboard ->
                    "Requested, but overridden — turn Force desktop mode off"
                else ->
                    "Off: the keyboard opens on the glasses"
            },
            checked = dexKeyboard,
            onChange = onDexKeyboard,
        )

        SectionHeader("Displays")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (allDisplays.isEmpty()) {
                Text("No displays detected", color = Color(0xFFFF7043), fontSize = 12.sp)
            } else {
                allDisplays.forEach { d ->
                    val isTarget = d.id == targetDisplay?.id
                    Text(
                        if (isTarget) "▶ ${d.name}  ${d.width}×${d.height}" else "  ${d.name}  ${d.width}×${d.height}",
                        color = if (isTarget) ACCENT else TEXT_MUTED,
                        fontSize = 12.sp,
                    )
                }
            }
            if (allDisplays.size == 1) {
                Text(
                    "Only 1 display — glasses may be in mirror mode. Switch to Desktop/Extended.",
                    color = Color(0xFFFFB300), fontSize = 11.sp,
                )
            }
        }

        SectionHeader("Gestures")
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            GestureHint("1 finger drag", "Move cursor")
            GestureHint("1 finger tap", "Left click")
            GestureHint("1 finger double-tap", "Double click")
            // Holding is how the left button is held down, which is what drags windows and
            // resizes them; the old right-click and text-select gestures are gone.
            GestureHint("1 finger hold", "Hold left button")
            GestureHint("1 finger hold + drag", "Drag window / resize")
            GestureHint("2 finger drag", "Scroll")
            GestureHint("2 finger pinch", "Zoom page")
        }
    }
}

// A labeled Slider with the formatted current value displayed to its right.
// label: display name; value/range: current value and allowed bounds; format: printf string
// for the value (e.g. "%.1f×"); onChange: callback with the new float value.
@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TEXT_DIM, fontSize = 14.sp)
            Text(format.format(value), color = ACCENT, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ACCENT,
                activeTrackColor = ACCENT_DIM,
                inactiveTrackColor = Color(0xFF263545),
            ),
        )
    }
}

// A single row in the gesture guide: gesture description on the left, resulting action on the right.
@Composable
private fun GestureHint(gesture: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, color = TEXT_MUTED, fontSize = 12.sp)
        Text(action, color = TEXT_DIM, fontSize = 12.sp)
    }
}
