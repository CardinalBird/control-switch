package com.controlswitch.ui

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.controlswitch.DsuClient
import com.controlswitch.MirrorStateStore
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import com.controlswitch.ui.theme.Palette
import com.controlswitch.ui.theme.Teal
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Controller Tests: pick a pad, then press everything and watch the drawing light up while the
 * checklist ticks itself off. The mirror is held off for as long as this page is open, so the pad
 * under test reports as itself rather than as the built-in one. Two steps: on the picker the pad
 * drives the page as usual; while testing, every event from the chosen pad is taken by the page
 * (its B included, which is why holding the button printed B is the way out).
 */
@Composable
internal fun ControllerTestScreen(state: MirrorUiState, active: Boolean, onBack: () -> Unit) {
    val internalName = state.localDevice?.name
    // Re-read when the supervisor sees pads come or go, and when the mirror stops and the external
    // pad reappears to Android.
    val pads = remember(state.devices, state.enabled) { connectedPads(internalName) }
    var padId by remember { mutableIntStateOf(-1) }
    val pad = pads.firstOrNull { it.id == padId } ?: pads.firstOrNull()
    var testingRaw by remember { mutableStateOf(false) }
    // The mirrored run: the mirror is let run and the test reads what comes through it, which is what
    // a game sees, from the handheld's own device. Each press buzzes the handheld, which the daemon
    // replays as rumble on the pad, so rumble and the round trip get checked by feel at the same time.
    var mirrored by remember { mutableStateOf(false) }

    // A pad that drops out mid-test ends the test rather than lighting a ghost. A mirrored run waits
    // for the mirror: before it is up, the pad's presses arrive raw, which is not what is being tested.
    val testing = testingRaw && pad != null && pad.id == padId && (!mirrored || state.enabled)
    var test by remember { mutableStateOf(PadTest(emptyList())) }
    // The motion step: the pad's gyro over the app's DSU port. For an external pad that is the
    // daemon's, so the mirror is let run for the step; the handheld's own is served while it's off.
    // Remembered at the start rather than re-read: once the mirror runs for the step, Android no
    // longer lists the external pad, so the picked device cannot be looked up again.
    var motion by remember { mutableStateOf(false) }
    var motionBuiltIn by remember { mutableStateOf(false) }
    var motionLabel by remember { mutableStateOf("") }
    var motionTest by remember { mutableStateOf(MotionTest()) }
    val builtIn = pad != null && pad.name.equals(internalName, ignoreCase = true)
    val holdWanted = !(motion && !motionBuiltIn) && !mirrored
    LifecycleStartEffect(holdWanted) {
        MirrorStateStore.setTestingPad(holdWanted)
        onStopOrDispose { MirrorStateStore.setTestingPad(false) }
    }
    val motionLive = motion && (motionBuiltIn || state.enabled)
    DisposableEffect(motionLive) {
        if (!motionLive) return@DisposableEffect onDispose { }
        val client = DsuClient { gyro, accel -> motionTest = motionTest.feed(gyro, accel) }
        client.start()
        onDispose { client.stop() }
    }
    // A pad without a gyro (or a daemon not serving one) never answers: say so after a moment.
    var motionWaited by remember { mutableStateOf(false) }
    LaunchedEffect(motionLive) {
        motionWaited = false
        if (motionLive) {
            delay(3000)
            motionWaited = true
        }
    }
    val layout = if (builtIn) PadLayout.NINTENDO else state.padLayout
    // The mirror is still being stood down: until it is, an external pad's presses arrive as the
    // built-in one, so there is nothing to test yet.
    val ready = !state.enabled && pad != null

    BackHandler(enabled = active) {
        when {
            testing || mirrored -> {
                testingRaw = false
                mirrored = false
            }
            motion -> motion = false
            else -> onBack()
        }
    }

    fun start() {
        val chosen = pad ?: return
        if (!ready) return
        val axes = padAxes { chosen.getMotionRange(it) != null }
        test = PadTest(
            items = padItems(hasKey = { chosen.hasKeys(it)[0] }, hasAxis = { chosen.getMotionRange(it) != null }, axes = axes, builtIn = builtIn),
        )
        padId = chosen.id
        testingRaw = true
    }

    fun startMirrored() {
        // Nothing to mirror alone: the handheld's own pad is the target, another pad is the source.
        if (!ready || pads.size < 2) return
        val internal = pads.firstOrNull { it.name.equals(internalName, ignoreCase = true) } ?: return
        val axes = padAxes { internal.getMotionRange(it) != null }
        test = PadTest(
            items = padItems(hasKey = { internal.hasKeys(it)[0] }, hasAxis = { internal.getMotionRange(it) != null }, axes = axes, builtIn = true),
        )
        padId = internal.id
        mirrored = true
        testingRaw = true
    }

    fun startMotion() {
        val chosen = pad ?: return
        if (!ready) return
        motionTest = MotionTest()
        motionBuiltIn = builtIn
        motionLabel = padLabel(chosen, pads, internalName)
        motion = true
    }

    // Every physical pad feeds the test, not only the picked one: with an external pad connected
    // this handheld routes presses through that pad's twin device, so filtering by device id would
    // miss them. The pick decides the checklist and the labels. Keys that are not pad buttons (the
    // handheld's own Back and Home) go on as normal, so there is always a way out.
    fun InputDevice?.isPad() = this != null && !isVirtual &&
        (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    val activity = LocalContext.current.findMainActivity()
    // Y on the picker starts the motion step, X the mirrored run. Taken at the activity rather than
    // through Compose focus, which is still being re-anchored right after a step ends; an unhandled
    // Y turns into Back and would close the page. Both halves are taken.
    DisposableEffect(activity, testing, motion, mirrored, ready, pad?.id) {
        if (activity == null || testing || motion || mirrored) return@DisposableEffect onDispose { }
        activity.onPadCapture = { event: KeyEvent ->
            val x = event.keyCode == KeyEvent.KEYCODE_BUTTON_X
            (x || event.keyCode == KeyEvent.KEYCODE_BUTTON_Y).also { mine ->
                if (mine && event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    if (x) startMirrored() else startMotion()
                }
            }
        }
        onDispose { activity.onPadCapture = null }
    }
    DisposableEffect(activity, testing, mirrored, pad?.id) {
        if (activity == null || !testing || pad == null) return@DisposableEffect onDispose { }
        val vibrator = if (mirrored) activity.getSystemService(Vibrator::class.java) else null
        val axes = padAxes { pad.getMotionRange(it) != null }
        val hasHat = pad.getMotionRange(MotionEvent.AXIS_HAT_X) != null
        // 0 at rest, 1 fully pulled, whatever the axis range: a trigger that reports -1..1 rests at -1.
        fun pull(axis: Int, value: Float): Float {
            val range = pad.getMotionRange(axis) ?: return 0f
            return if (range.range > 0f) ((value - range.min) / range.range).coerceIn(0f, 1f) else 0f
        }
        activity.onPadCapture = { event: KeyEvent ->
            val icon = PAD_KEYS[event.keyCode]
            (icon != null && event.device.isPad()).also { mine ->
                if (mine && event.repeatCount == 0) {
                    val down = event.action == KeyEvent.ACTION_DOWN
                    test = test.button(icon!!, down)
                    if (down) vibrator?.buzz()
                }
            }
        }
        activity.onPadMotion = { event: MotionEvent ->
            (event.device.isPad() && event.isFromSource(InputDevice.SOURCE_JOYSTICK)).also { mine ->
                if (mine) {
                    var next = test
                        .stick(right = false, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
                        .stick(right = true, event.getAxisValue(axes.rightX), event.getAxisValue(axes.rightY))
                    axes.leftTrigger?.let { next = next.trigger(right = false, pull(it, event.getAxisValue(it))) }
                    axes.rightTrigger?.let { next = next.trigger(right = true, pull(it, event.getAxisValue(it))) }
                    if (hasHat) next = next.hat(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
                    test = next
                }
            }
        }
        onDispose {
            activity.onPadCapture = null
            activity.onPadMotion = null
        }
    }

    // Holding the button printed B fills the ring and then ends the test; letting go earlier was a
    // test press like any other. The animation is the timer.
    val hold = remember { Animatable(0f) }
    val holding = testing && EXIT_ICON in test.down
    LaunchedEffect(holding) {
        hold.snapTo(0f)
        if (!holding) return@LaunchedEffect
        hold.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
        // The releases land after the hooks are gone, so nothing would ever come back up.
        test = test.copy(down = emptySet())
        testingRaw = false
        mirrored = false
    }

    // Same anchoring as Additional Settings, redone when the test ends and the row is back in charge.
    var hasFocus by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(active, testing, motion, mirrored) {
        if (!active || testing || motion || mirrored) return@LaunchedEffect
        repeat(24) {
            if (hasFocus) return@LaunchedEffect
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
            delay(50)
        }
    }

    val cycle = { by: Int ->
        if (pads.isNotEmpty() && pad != null) padId = pads[(pads.indexOf(pad) + by + pads.size) % pads.size].id
    }
    CompositionLocalProvider(LocalPadLayout provides layout) {
        Column(
            Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Header(title = "Controller Tests")
            Rule(Modifier.padding(horizontal = 28.dp))
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 40.dp, vertical = 2.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    MenuRow(
                        title = "Controller",
                        minHeight = 44.dp,
                        // Mirroring may run during the motion step, so a pad's A would reach this row.
                        onClick = if (motion || mirrored) null else ({ start() }),
                        onLeft = if (testing || motion || mirrored) null else ({ cycle(-1) }),
                        onRight = if (testing || motion || mirrored) null else ({ cycle(1) }),
                        modifier = Modifier.focusRequester(first),
                    ) {
                        RowValue(
                            when {
                                pad == null -> "No controller connected"
                                mirrored -> "Through the mirror"
                                !ready && !motion -> "Pausing mirroring…"
                                motion -> motionLabel
                                testing -> padLabel(pad, pads, internalName)
                                else -> "‹  ${padLabel(pad, pads, internalName)}  ›"
                            },
                            accent = ready && !testing && !motion,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(contentAlignment = Alignment.Center) {
                        PadDiagram(test, layout, builtIn, dim = !testing)
                        if (motion) Bubble(motionTest)
                    }
                }
                Spacer(Modifier.width(32.dp))
                if (motion) MotionChecklist(motionTest, Modifier.width(300.dp).padding(top = 8.dp))
                else Checklist(test, layout, Modifier.width(300.dp).padding(top = 8.dp))
            }
            if (motion) {
                val waiting = !motionBuiltIn && !state.enabled
                Footer(
                    status = when {
                        waiting && !state.docked && !state.mirrorWhenHandheld -> "Motion needs mirroring: dock, or set Allow mirroring to Always."
                        waiting -> "Starting mirroring…"
                        motionWaited && motionTest.samples == 0 -> "No motion data from this controller."
                        motionTest.samples == 0 -> "Connecting to the gyro…"
                        motionBuiltIn -> "Reading this handheld's gyro. Tip it forward, twist it flat, roll it on its side."
                        else -> "Reading the controller's gyro through the mirror. Tip it forward, twist it flat, roll it on its side."
                    },
                    hints = listOf(HintAction(SlotIcon.B, "Done") { motion = false }),
                )
            } else if (mirrored && !testing) {
                Footer(
                    status = when {
                        !state.autoMirrorEnabled -> "Mirroring is off: turn it on from the main screen first."
                        !state.docked && !state.mirrorWhenHandheld -> "This needs mirroring: dock, or set Allow mirroring to Always."
                        else -> "Starting mirroring…"
                    },
                    hints = listOf(HintAction(SlotIcon.B, "Cancel") { mirrored = false; testingRaw = false }),
                )
            } else if (testing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mirrored) "What games see through the mirror. Every press rumbles your controller."
                        else "Press every button, pull both triggers and turn each stick in a full circle.",
                        color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp).weight(1f),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { HoldHint(hold.value) }
                }
            } else {
                Footer(
                    status = when {
                        !ready -> "Waiting for the mirror to pause."
                        // With another pad connected the handheld's presses come through that pad, so
                        // the two can't be told apart.
                        builtIn && pads.size > 1 -> "Turn off other controllers to test this handheld alone."
                        pads.size > 1 -> "A tests the buttons, Y the motion. X tests through the mirror, as games see it."
                        else -> "Choose a controller: A tests the buttons, Y the motion."
                    },
                    hints = listOfNotNull(
                        HintAction(SlotIcon.B, "Back", onBack),
                        if (pads.size > 1) HintAction(SlotIcon.X, "Mirrored") { startMirrored() } else null,
                        HintAction(SlotIcon.Y, "Motion") { startMotion() },
                        HintAction(SlotIcon.A, "Buttons", null),
                    ),
                )
            }
        }
    }
}

private const val HOLD_MS = 1500
/** The buzz per press on a mirrored run: short enough that fast presses stay distinct. */
private const val BUZZ_MS = 60L

/**
 * One buzz, filed as media like a game's rumble is. Left unlabelled, Android files a short buzz as
 * touch feedback and drops it when the user has that off, and the test would say the pad's rumble
 * is broken when only the setting is.
 */
private fun Vibrator.buzz() {
    val effect = VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
        @Suppress("DEPRECATION")
        vibrate(effect)
    }
}



/** The real game controllers Android lists right now, the handheld's own first. */
private fun connectedPads(internalName: String?): List<InputDevice> =
    InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice)
        .filter { !it.isVirtual && (it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) }
        .sortedBy { !it.name.equals(internalName, ignoreCase = true) }

private fun padLabel(pad: InputDevice, pads: List<InputDevice>, internalName: String?): String = when {
    pad.name.equals(internalName, ignoreCase = true) -> "This handheld"
    // Two pads of one model share a name; the player number tells them apart.
    pads.count { it.name == pad.name } > 1 -> "${pad.name} (P${pad.controllerNumber})"
    else -> pad.name
}

/** The B hint with a ring that fills while the button is held. */
@Composable
private fun HoldHint(progress: Float) {
    Row(Modifier.padding(horizontal = 28.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp)) {
            PadIcon(SlotIcon.B, 30.dp)
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Teal, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, style = Stroke(3.dp.toPx()))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("Hold to stop", color = Palette.textPrimary, fontSize = 15.sp)
    }
}

/** The three rotation axes, with their live rate. */
@Composable
private fun MotionChecklist(test: MotionTest, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("${test.passedCount}/${Axis.entries.size} passed", color = Palette.textPrimary, fontSize = 17.sp, modifier = Modifier.padding(bottom = 6.dp))
        Rule()
        for (axis in Axis.entries) {
            val passed = test.passed(axis)
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(axis.label, color = Palette.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    String.format(Locale.ROOT, "%.1f°/s", test.rates[axis.ordinal]),
                    color = if (abs(test.rates[axis.ordinal]) >= 60f) Teal else Palette.textMuted, fontSize = 13.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text(if (passed) "✓" else "·", color = if (passed) Palette.custom else Palette.textMuted, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

/** A ball on the pad, from the accelerometer: it rolls to whichever side is low. */
@Composable
private fun Bubble(test: MotionTest) {
    val ring = Palette.textPrimary
    val face = Palette.screen
    Canvas(Modifier.size(90.dp)) {
        val r = size.minDimension / 2
        val c = Offset(r, r)
        drawCircle(face, r, c)
        drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
        drawCircle(ring, r * 0.25f, c, style = Stroke(1.dp.toPx()))
        var dx = test.tiltX
        var dy = test.tiltY
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        drawCircle(Teal, r * 0.16f, c + Offset(dx * r * 0.8f, dy * r * 0.8f))
    }
}

/** "N/N passed" and one line per control, two columns. */
@Composable
private fun Checklist(test: PadTest, layout: PadLayout, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            if (test.items.isEmpty()) "Not started" else "${test.passedCount}/${test.items.size} passed",
            color = Palette.textPrimary, fontSize = 17.sp, modifier = Modifier.padding(bottom = 6.dp),
        )
        Rule()
        for (pair in test.items.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (icon in pair) {
                    val passed = test.passed(icon)
                    Row(Modifier.weight(1f).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        PadIcon(icon, 20.dp, tint = if (test.lit(icon)) Teal else Palette.textPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            itemLabel(icon, layout), color = Palette.textSecondary, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        val stick = when (icon) {
                            SlotIcon.LEFT_STICK_UP -> test.left
                            SlotIcon.RIGHT_STICK_UP -> test.right
                            else -> null
                        }
                        Text(
                            if (passed) "✓" else if (stick != null) "${stick.reached}/8" else "·",
                            color = if (passed) Palette.custom else Palette.textMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// The drawing: Kenney's Switch Pro silhouette (the same art as the pad tile), scaled up, with the
// controls drawn into its cut-outs. Coordinates are the icon's own 64-unit grid, so a spot from
// the SVG is a spot here; [S] is dp per unit and the shoulders sit in a band above the body.
private const val S = 5.5f
private const val SHOULDERS = 22f
private const val PAD_LEFT = 8f
private const val PAD_TOP = 14.6f
private const val W = 48f * S
private const val H = 35.5f * S + SHOULDERS

// Kenney Input Prompts 1.5 (CC0): Nintendo Switch 2/Vector/controller_switch_pro.svg.
private const val PRO_PATH = "M51 25 Q51 24.15 50.4 23.55 49.85 23 49 23 48.15 23 47.6 23.55 47 24.15 47 25 47 25.8 47.6 26.4 48.15 27 49 27 49.85 27 50.4 26.4 51 25.8 51 25 M43 25 Q43 24.15 42.4 23.55 41.85 23 41 23 40.15 23 39.6 23.55 39 24.15 39 25 39 25.8 39.6 26.4 40.15 27 41 27 41.85 27 42.4 26.4 43 25.8 43 25 M47 22 Q47 21.15 46.4 20.55 45.85 20 45 20 44.15 20 43.6 20.55 43 21.15 43 22 43 22.8 43.6 23.4 44.15 24 45 24 45.85 24 46.4 23.4 47 22.8 47 22 M21.55 38.9 Q20.05 38.9 19.4 40.5 L16.2 46.75 Q14.4 49.7 11.65 48.8 8.05 47.55 8 43 8.85 33.25 10.8 23.75 11.8 19.2 13.2 18.3 14.3 15.45 18.05 14.5 22.25 13.45 24.7 14.6 L39.3 14.6 Q41.75 13.45 45.95 14.5 49.7 15.45 50.85 18.3 52.2 19.2 53.2 23.75 55.15 33.25 56 43 55.95 47.55 52.35 48.8 49.6 49.7 47.8 46.75 L44.6 40.5 Q43.95 38.9 42.45 38.9 L21.55 38.9 M24 29 L24 31 22 31 22 33 24 33 24 35 26 35 26 33 28 33 28 31 26 31 26 29 24 29 M41 32 Q41 30.75 40.1 29.85 39.25 29 38 29 36.75 29 35.85 29.85 35 30.75 35 32 35 33.2 35.85 34.05 36.75 35 38 35 39.25 35 40.1 34.05 41 33.2 41 32 M22 24 Q22 22.75 21.1 21.85 20.25 21 19 21 17.75 21 16.85 21.85 16 22.75 16 24 16 25.2 16.85 26.05 17.75 27 19 27 20.25 27 21.1 26.05 22 25.2 22 24 M47 28 Q47 27.15 46.4 26.55 45.85 26 45 26 44.15 26 43.6 26.55 43 27.15 43 28 43 28.8 43.6 29.4 44.15 30 45 30 45.85 30 46.4 29.4 47 28.8 47 28"

/** A point on the icon's grid. */
private data class Spot(val x: Float, val y: Float) {
    val dx: Float get() = (x - PAD_LEFT) * S
    val dy: Float get() = (y - PAD_TOP) * S + SHOULDERS
}

// The silhouette's cut-outs.
private val SOUTH = Spot(45f, 28f)
private val EAST = Spot(49f, 25f)
private val NORTH = Spot(45f, 22f)
private val WEST = Spot(41f, 25f)
private val LEFT_STICK = Spot(19f, 24f)
private val RIGHT_STICK = Spot(38f, 32f)
private val DPAD = Spot(25f, 32f)
// Not in the silhouette, placed where the Pro has them: − and + flanking the top, Capture under −,
// Home under +.
private val MINUS = Spot(28f, 18.5f)
private val PLUS = Spot(36f, 18.5f)
private val HOME = Spot(36f, 23f)
private val READOUT_Y = 52.5f

@Composable
private fun PadDiagram(test: PadTest, layout: PadLayout, builtIn: Boolean, dim: Boolean) {
    val nintendo = layout == PadLayout.NINTENDO
    // Faces by printed letter: Nintendo puts A east and X north, Xbox A south and X west.
    fun at(label: SlotIcon) = when (label) {
        SlotIcon.A -> if (nintendo) EAST else SOUTH
        SlotIcon.B -> if (nintendo) SOUTH else EAST
        SlotIcon.X -> if (nintendo) NORTH else WEST
        else -> if (nintendo) WEST else NORTH
    }
    val faces = listOf(SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y).map { it to at(it) }
    val body = Palette.borderStrong
    val hole = Palette.screen
    val good = Palette.custom
    val tint = if (dim) Palette.textMuted else Palette.textPrimary
    val showHome = SlotIcon.HOME in test.items || (test.items.isEmpty() && !builtIn)
    val silhouette = remember { PathParser().parsePathString(PRO_PATH).toPath() }

    Box(Modifier.size(W.dp, H.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / W
            fun Spot.px() = Offset(dx * u, dy * u)
            fun fill(icon: SlotIcon) = if (test.lit(icon)) Teal else hole
            fun passRing(icon: SlotIcon, center: Spot, radius: Float) {
                if (test.passed(icon)) drawCircle(good, radius * u, center.px(), style = Stroke(2f * u))
            }
            // Shoulders: a bar each for ZL/ZR (filling with the pull) and L/R, above the body.
            for ((icon, x, value) in listOf(
                Triple(SlotIcon.LEFT_TRIGGER, 12f, test.leftTrigger), Triple(SlotIcon.RIGHT_TRIGGER, 37f, test.rightTrigger),
            )) {
                val at = Offset((x - PAD_LEFT) * S * u, 0f)
                val sz = Size(15f * S * u, 9f * u)
                drawRoundRect(if (test.lit(icon)) Teal else body, at, sz, CornerRadius(4.5f * u))
                if (value > 0f) drawRoundRect(Teal, at, sz.copy(width = sz.width * value), CornerRadius(4.5f * u))
                if (test.passed(icon)) drawRoundRect(good, at, sz, CornerRadius(4.5f * u), style = Stroke(2f * u))
            }
            for ((icon, x) in listOf(SlotIcon.LEFT_BUMPER to 11f, SlotIcon.RIGHT_BUMPER to 36f)) {
                val at = Offset((x - PAD_LEFT) * S * u, 11f * u)
                val sz = Size(17f * S * u, 10f * u)
                drawRoundRect(if (test.lit(icon)) Teal else body, at, sz, CornerRadius(5f * u))
                if (test.passed(icon)) drawRoundRect(good, at, sz, CornerRadius(5f * u), style = Stroke(2f * u))
            }
            // The body, with the lit controls showing through its cut-outs.
            for ((stick, center, click) in listOf(
                Triple(test.left, LEFT_STICK, SlotIcon.LEFT_STICK_CLICK), Triple(test.right, RIGHT_STICK, SlotIcon.RIGHT_STICK_CLICK),
            )) {
                drawCircle(if (test.lit(click)) Teal else hole, 3f * S * u, center.px())
            }
            for ((icon, dx, dy) in listOf(
                Triple(SlotIcon.DPAD_UP, 0f, -2f), Triple(SlotIcon.DPAD_DOWN, 0f, 2f),
                Triple(SlotIcon.DPAD_LEFT, -2f, 0f), Triple(SlotIcon.DPAD_RIGHT, 2f, 0f),
            )) {
                val c = Spot(DPAD.x + dx, DPAD.y + dy).px()
                drawRect(fill(icon), c - Offset(S * u, S * u), Size(2f * S * u, 2f * S * u))
            }
            drawRect(hole, Spot(DPAD.x - 1f, DPAD.y - 1f).px(), Size(2f * S * u, 2f * S * u))
            for ((icon, spot) in faces) drawCircle(fill(icon), 2f * S * u, spot.px())
            withTransform({
                translate(-PAD_LEFT * S * u, (SHOULDERS - PAD_TOP * S) * u)
                scale(S * u, S * u, pivot = Offset.Zero)
            }) { drawPath(silhouette, body) }
            // On top of the body: stick rings and dots, −/+/Home, pass rings.
            for ((stick, center, click) in listOf(
                Triple(test.left, LEFT_STICK, SlotIcon.LEFT_STICK_CLICK), Triple(test.right, RIGHT_STICK, SlotIcon.RIGHT_STICK_CLICK),
            )) {
                val c = center.px()
                for (i in 0 until 8) {
                    val hit = stick.octants and (1 shl i) != 0
                    drawArc(
                        if (hit) good else hole, startAngle = i * 45f - 20f, sweepAngle = 40f, useCenter = false,
                        topLeft = c - Offset(3.9f * S * u, 3.9f * S * u), size = Size(7.8f * S * u, 7.8f * S * u), style = Stroke(2.5f * u),
                    )
                }
                drawCircle(if (test.lit(click)) Teal else tint, 1.1f * S * u, c + Offset(stick.x * 1.7f * S * u, stick.y * 1.7f * S * u))
                passRing(click, center, 3.3f * S)
            }
            for ((icon, dx, dy) in listOf(
                Triple(SlotIcon.DPAD_UP, 0f, -2f), Triple(SlotIcon.DPAD_DOWN, 0f, 2f),
                Triple(SlotIcon.DPAD_LEFT, -2f, 0f), Triple(SlotIcon.DPAD_RIGHT, 2f, 0f),
            )) {
                if (test.passed(icon)) {
                    val c = Spot(DPAD.x + dx, DPAD.y + dy).px()
                    drawRect(good, c - Offset(S * u, S * u), Size(2f * S * u, 2f * S * u), style = Stroke(2f * u))
                }
            }
            for ((icon, spot) in faces) passRing(icon, spot, 2.4f * S)
            for ((icon, spot) in listOf(SlotIcon.VIEW to MINUS, SlotIcon.MENU to PLUS)) {
                drawCircle(fill(icon), 1.6f * S * u, spot.px())
                passRing(icon, spot, 2f * S)
            }
            if (showHome) {
                drawCircle(fill(SlotIcon.HOME), 1.6f * S * u, HOME.px())
                passRing(SlotIcon.HOME, HOME, 2f * S)
            }
        }
        for ((icon, spot) in faces) Prompt(icon, spot, (3.6f * S).dp, tint)
        Prompt(SlotIcon.LEFT_TRIGGER, Spot(19.5f, PAD_TOP - SHOULDERS / S + 4.5f / S), 18.dp, tint)
        Prompt(SlotIcon.RIGHT_TRIGGER, Spot(44.5f, PAD_TOP - SHOULDERS / S + 4.5f / S), 18.dp, tint)
        Prompt(SlotIcon.LEFT_BUMPER, Spot(19.5f, PAD_TOP - SHOULDERS / S + 16f / S), 16.dp, tint)
        Prompt(SlotIcon.RIGHT_BUMPER, Spot(44.5f, PAD_TOP - SHOULDERS / S + 16f / S), 16.dp, tint)
        Prompt(SlotIcon.VIEW, MINUS, 16.dp, tint)
        Prompt(SlotIcon.MENU, PLUS, 16.dp, tint)
        if (showHome) Prompt(SlotIcon.HOME, HOME, 16.dp, tint)
        StickReadout(test.left, Spot(LEFT_STICK.x, READOUT_Y))
        StickReadout(test.right, Spot(RIGHT_STICK.x, READOUT_Y))
    }
}

/** A prompt icon centred on [spot] of the drawing. */
@Composable
private fun Prompt(icon: SlotIcon, spot: Spot, size: Dp, tint: Color) {
    Box(Modifier.offset(spot.dx.dp - size / 2, spot.dy.dp - size / 2)) { PadIcon(icon, size, tint) }
}

@Composable
private fun StickReadout(stick: StickState, spot: Spot) {
    Box(Modifier.offset(spot.dx.dp - 40.dp, spot.dy.dp - 6.dp).width(80.dp), contentAlignment = Alignment.Center) {
        Text(String.format(Locale.ROOT, "%+.2f  %+.2f", stick.x, stick.y), color = Palette.textMuted, fontSize = 10.sp, maxLines = 1)
    }
}
