package com.controlswitch.ui

import android.view.KeyEvent
import android.view.MotionEvent
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

// The Controller Tests model: what the pad under test has done so far, and which of it counts as a
// pass. Pure Kotlin over Android's resolved key codes and axes (only the int constants, which the
// compiler inlines), so it runs as a plain JVM test.

/** Where a stick is right now, and which of the eight directions it has reached at full tilt. */
internal data class StickState(val x: Float = 0f, val y: Float = 0f, val octants: Int = 0) {
    val reached: Int get() = Integer.bitCount(octants)
}

internal const val FULL_CIRCLE = 0xFF

/** The eighth of the circle a stick points into, 0 = right going clockwise on screen; null under full tilt. */
internal fun octant(x: Float, y: Float): Int? {
    if (hypot(x, y) < TILT) return null
    return ((atan2(y, x) / (PI / 4)).roundToInt() + 8) % 8
}

/**
 * Progress of one test run. [items] is the checklist (the controls this pad declares, in display
 * order); the two `*_STICK_UP` entries stand for "turn the stick in a full circle".
 */
internal data class PadTest(
    val items: List<SlotIcon>,
    val down: Set<SlotIcon> = emptySet(),
    val done: Set<SlotIcon> = emptySet(),
    val left: StickState = StickState(),
    val right: StickState = StickState(),
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    private val hatX: Float = 0f,
    private val hatY: Float = 0f,
) {
    // A trigger passes on its button too: the handheld republishes every pad with its own analog
    // trigger axes, and a Switch pad's ZL/ZR only ever arrive as presses through that twin.
    fun button(icon: SlotIcon, pressed: Boolean): PadTest = when {
        pressed -> copy(down = down + icon)
        icon !in down -> this
        else -> copy(down = down - icon, done = done + icon)
    }

    fun stick(right: Boolean, x: Float, y: Float): PadTest {
        val bit = octant(x, y)?.let { 1 shl it } ?: 0
        return if (right) copy(right = StickState(x, y, this.right.octants or bit))
        else copy(left = StickState(x, y, left.octants or bit))
    }

    /** [value] is 0 at rest and 1 fully pulled, whatever range the axis reports in. */
    fun trigger(right: Boolean, value: Float): PadTest {
        val icon = if (right) SlotIcon.RIGHT_TRIGGER else SlotIcon.LEFT_TRIGGER
        val passed = if (value >= PULL) done + icon else done
        return if (right) copy(rightTrigger = value, done = passed) else copy(leftTrigger = value, done = passed)
    }

    /** A hat D-pad, as presses and releases of the four directions. Only a change moves anything. */
    fun hat(x: Float, y: Float): PadTest {
        if (x == hatX && y == hatY) return this
        var test = copy(hatX = x, hatY = y)
        for ((icon, held) in listOf(
            SlotIcon.DPAD_LEFT to (x <= -HAT), SlotIcon.DPAD_RIGHT to (x >= HAT),
            SlotIcon.DPAD_UP to (y <= -HAT), SlotIcon.DPAD_DOWN to (y >= HAT),
        )) {
            if (held != (icon in test.down)) test = test.button(icon, held)
        }
        return test
    }

    fun passed(icon: SlotIcon): Boolean = when (icon) {
        SlotIcon.LEFT_STICK_UP -> left.octants == FULL_CIRCLE
        SlotIcon.RIGHT_STICK_UP -> right.octants == FULL_CIRCLE
        else -> icon in done
    }

    fun lit(icon: SlotIcon): Boolean = icon in down ||
        (icon == SlotIcon.LEFT_TRIGGER && leftTrigger > LIT) ||
        (icon == SlotIcon.RIGHT_TRIGGER && rightTrigger > LIT)

    val passedCount: Int get() = items.count(::passed)
}

/** Full tilt: a stick has to reach this far out for a direction to count. */
private const val TILT = 0.8f
/** A trigger pulled this far is a full pull. */
private const val PULL = 0.9f
private const val HAT = 0.5f
private const val LIT = 0.05f

/** Android key code -> the control it is, before any Nintendo relabelling. */
internal val PAD_KEYS: Map<Int, SlotIcon> = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to SlotIcon.A,
    KeyEvent.KEYCODE_BUTTON_B to SlotIcon.B,
    KeyEvent.KEYCODE_BUTTON_X to SlotIcon.X,
    KeyEvent.KEYCODE_BUTTON_Y to SlotIcon.Y,
    KeyEvent.KEYCODE_BUTTON_L1 to SlotIcon.LEFT_BUMPER,
    KeyEvent.KEYCODE_BUTTON_R1 to SlotIcon.RIGHT_BUMPER,
    KeyEvent.KEYCODE_BUTTON_L2 to SlotIcon.LEFT_TRIGGER,
    KeyEvent.KEYCODE_BUTTON_R2 to SlotIcon.RIGHT_TRIGGER,
    KeyEvent.KEYCODE_BUTTON_SELECT to SlotIcon.VIEW,
    KeyEvent.KEYCODE_BUTTON_START to SlotIcon.MENU,
    KeyEvent.KEYCODE_BUTTON_MODE to SlotIcon.HOME,
    KeyEvent.KEYCODE_BUTTON_THUMBL to SlotIcon.LEFT_STICK_CLICK,
    KeyEvent.KEYCODE_BUTTON_THUMBR to SlotIcon.RIGHT_STICK_CLICK,
    KeyEvent.KEYCODE_DPAD_UP to SlotIcon.DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN to SlotIcon.DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT to SlotIcon.DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to SlotIcon.DPAD_RIGHT,
)

/** Which Android axes carry the right stick and the triggers on this pad. */
internal data class PadAxes(val rightX: Int, val rightY: Int, val leftTrigger: Int?, val rightTrigger: Int?)

/**
 * The same reading of a pad's axes as the mapping editor's slots: triggers are LTRIGGER/RTRIGGER,
 * else BRAKE/GAS (this handheld's layout); the right stick is Z/RZ, unless the pad has no trigger
 * axes of its own and RX/RY instead (xpad, DualShock), where Z/RZ are the triggers.
 */
internal fun padAxes(hasAxis: (Int) -> Boolean): PadAxes {
    val lt = listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE).firstOrNull(hasAxis)
    val rt = listOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS).firstOrNull(hasAxis)
    if (lt == null && rt == null && hasAxis(MotionEvent.AXIS_RX) && hasAxis(MotionEvent.AXIS_RY)) {
        return PadAxes(
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_Z.takeIf(hasAxis), MotionEvent.AXIS_RZ.takeIf(hasAxis),
        )
    }
    return PadAxes(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, lt, rt)
}

/**
 * The checklist for a pad: only what it declares. Home is left out for the handheld's own pad,
 * whose Home key goes to the launcher and never reaches an app.
 */
internal fun padItems(hasKey: (Int) -> Boolean, hasAxis: (Int) -> Boolean, axes: PadAxes, builtIn: Boolean): List<SlotIcon> =
    buildList {
        for ((key, icon) in FACE_KEYS) if (hasKey(key)) add(icon)
        if (axes.leftTrigger != null || hasKey(KeyEvent.KEYCODE_BUTTON_L2)) add(SlotIcon.LEFT_TRIGGER)
        if (axes.rightTrigger != null || hasKey(KeyEvent.KEYCODE_BUTTON_R2)) add(SlotIcon.RIGHT_TRIGGER)
        if (hasAxis(MotionEvent.AXIS_HAT_X) || hasKey(KeyEvent.KEYCODE_DPAD_UP)) {
            addAll(listOf(SlotIcon.DPAD_UP, SlotIcon.DPAD_DOWN, SlotIcon.DPAD_LEFT, SlotIcon.DPAD_RIGHT))
        }
        if (hasAxis(MotionEvent.AXIS_X) && hasAxis(MotionEvent.AXIS_Y)) add(SlotIcon.LEFT_STICK_UP)
        if (hasKey(KeyEvent.KEYCODE_BUTTON_THUMBL)) add(SlotIcon.LEFT_STICK_CLICK)
        if (hasAxis(axes.rightX) && hasAxis(axes.rightY)) add(SlotIcon.RIGHT_STICK_UP)
        if (hasKey(KeyEvent.KEYCODE_BUTTON_THUMBR)) add(SlotIcon.RIGHT_STICK_CLICK)
        if (hasKey(KeyEvent.KEYCODE_BUTTON_SELECT)) add(SlotIcon.VIEW)
        if (hasKey(KeyEvent.KEYCODE_BUTTON_START)) add(SlotIcon.MENU)
        if (hasKey(KeyEvent.KEYCODE_BUTTON_MODE) && !builtIn) add(SlotIcon.HOME)
    }

private val FACE_KEYS = listOf(
    KeyEvent.KEYCODE_BUTTON_A to SlotIcon.A, KeyEvent.KEYCODE_BUTTON_B to SlotIcon.B,
    KeyEvent.KEYCODE_BUTTON_X to SlotIcon.X, KeyEvent.KEYCODE_BUTTON_Y to SlotIcon.Y,
    KeyEvent.KEYCODE_BUTTON_L1 to SlotIcon.LEFT_BUMPER, KeyEvent.KEYCODE_BUTTON_R1 to SlotIcon.RIGHT_BUMPER,
)

// Faces need no relabelling: the handheld republishes every pad through its own twin device, and
// that twin reports face buttons by their printed letter (a Switch pad's A arrives as BUTTON_A, by
// label, not by position as hid-nintendo would), the same as the handheld's own pad.

/** The control to hold to leave the test: the button printed B. */
internal val EXIT_ICON = SlotIcon.B

/** The checklist's name for a control, in the console's own words. */
internal fun itemLabel(icon: SlotIcon, layout: PadLayout): String {
    val nintendo = layout == PadLayout.NINTENDO
    return when (icon) {
        SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y -> icon.name
        SlotIcon.LEFT_BUMPER -> if (nintendo) "L" else "LB"
        SlotIcon.RIGHT_BUMPER -> if (nintendo) "R" else "RB"
        SlotIcon.LEFT_TRIGGER -> if (nintendo) "ZL" else "LT"
        SlotIcon.RIGHT_TRIGGER -> if (nintendo) "ZR" else "RT"
        SlotIcon.DPAD_UP -> "D-pad up"
        SlotIcon.DPAD_DOWN -> "D-pad down"
        SlotIcon.DPAD_LEFT -> "D-pad left"
        SlotIcon.DPAD_RIGHT -> "D-pad right"
        SlotIcon.LEFT_STICK_UP -> "Left stick"
        SlotIcon.RIGHT_STICK_UP -> "Right stick"
        SlotIcon.LEFT_STICK_CLICK -> "L3"
        SlotIcon.RIGHT_STICK_CLICK -> "R3"
        SlotIcon.VIEW -> "Select"
        SlotIcon.MENU -> "Start"
        SlotIcon.HOME -> if (nintendo) "Home" else "Guide"
        else -> icon.name
    }
}

/** The three rotation axes of the motion test, in DSU's order. */
internal enum class Axis(val label: String) { PITCH("Pitch"), YAW("Yaw"), ROLL("Roll") }

/** Progress of the motion test: an axis passes once the pad has been turned fast enough about it. */
internal data class MotionTest(
    // deg/s: pitch, yaw, roll
    val rates: List<Float> = listOf(0f, 0f, 0f),
    // The ball: where it rolls to, which is the low side. Gravity's sideways and forward parts, in g.
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val done: Set<Axis> = emptySet(),
    val samples: Int = 0,
) {
    /** [gyro] pitch/yaw/roll in deg/s, [accel] x/y/z in g (DSU axes: x right, y up, z toward you). */
    fun feed(gyro: FloatArray, accel: FloatArray): MotionTest = copy(
        rates = gyro.toList(),
        tiltX = -accel[0],
        tiltY = accel[2],
        done = done + Axis.entries.filter { abs(gyro[it.ordinal]) >= TURN },
        samples = samples + 1,
    )

    fun passed(axis: Axis): Boolean = axis in done
    val passedCount: Int get() = done.size
}

/** A turn this fast about an axis is a deliberate one: a quarter turn in well under a second. */
private const val TURN = 60f
