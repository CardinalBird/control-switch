package com.controlswitch.domain.model

/**
 * One slot the user can point a control at: what the target should be told, and what to call it.
 *
 * The list of slots is the whole editor. A slot with no binding is not "unfinished", it is a control
 * forwarded exactly as the pad reports it, which is the right answer for most slots on most pads.
 */
data class MappingSlot(
    val label: String,
    val target: ControlRef,
    /** Which control this is on the pad, for the list to draw. */
    val icon: SlotIcon,
)

/**
 * Which control a slot stands for.
 *
 * Named rather than carrying artwork or a character: what the control *is* belongs here, and how it
 * is drawn belongs to whatever is drawing it.
 */
enum class SlotIcon {
    A, B, X, Y,
    LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    LEFT_STICK_UP, LEFT_STICK_DOWN, LEFT_STICK_LEFT, LEFT_STICK_RIGHT, LEFT_STICK_CLICK,
    RIGHT_STICK_UP, RIGHT_STICK_DOWN, RIGHT_STICK_LEFT, RIGHT_STICK_RIGHT, RIGHT_STICK_CLICK,
    VIEW, MENU,
    /** The handheld's own Home and Back actions, as targets for a pad button. */
    HOME, BACK,
    /** A Nintendo pad's Capture button. Drawn and lit in Controller Tests only, never a slot. */
    CAPTURE,
}

/**
 * What the mirror's target declares, read from the device rather than assumed.
 *
 * The lesson this type exists to encode: the kernel silently drops an event whose code the target
 * does not declare, and Android maps the codes it does receive through the device's key layout file
 * — by code number, not by the name the kernel headers give the code. Every wrong guess this feature
 * has shipped came from reasoning about either of those instead of reading them. `keys` and `axes`
 * come from the device's capability bitmasks; the label maps come from its resolved `.kl`.
 */
data class TargetTraits(
    val keys: Set<Int>,
    val axes: Set<Int>,
    /** evdev key code -> Android button name (`BUTTON_A`…), from the key layout file. */
    val keyLabels: Map<Int, String> = emptyMap(),
    /** evdev axis code -> Android axis name (`X`, `RZ`, `LTRIGGER`…), from the key layout file. */
    val axisLabels: Map<Int, String> = emptyMap(),
)

// Standard evdev codes, spelled out rather than pulled from a platform constant so the domain module
// stays free of Android.
private const val BTN_SOUTH = 0x130
private const val BTN_EAST = 0x131
private const val BTN_NORTH = 0x133
private const val BTN_WEST = 0x134
private const val BTN_TL = 0x136
private const val BTN_TR = 0x137
private const val BTN_TL2 = 0x138
private const val BTN_TR2 = 0x139
private const val BTN_SELECT = 0x13a
private const val BTN_START = 0x13b
private const val BTN_THUMBL = 0x13d
private const val BTN_THUMBR = 0x13e
private const val BTN_DPAD_UP = 0x220
private const val BTN_DPAD_DOWN = 0x221
private const val BTN_DPAD_LEFT = 0x222
private const val BTN_DPAD_RIGHT = 0x223
// Linux KEY_HOME / KEY_BACK. The daemon turns a binding aimed at these into the Android action
// rather than writing them to the target pad.
const val KEY_HOME = 0x66
const val KEY_BACK = 0x9e

private const val ABS_X = 0x00
private const val ABS_Y = 0x01
private const val ABS_Z = 0x02
private const val ABS_RX = 0x03
private const val ABS_RY = 0x04
private const val ABS_RZ = 0x05
private const val ABS_GAS = 0x09
private const val ABS_BRAKE = 0x0a
private const val ABS_HAT0X = 0x10
private const val ABS_HAT0Y = 0x11

/**
 * The measured traits of the Odin 2's internal pad, for when the live target cannot be inspected.
 *
 * Matches `/proc/bus/input/devices` (`ABS=30627`) and `Vendor_2020_Product_0112.kl` on the device.
 * A fallback and nothing more: the live path reads the same facts from the running system.
 */
fun odinFallbackTraits(): TargetTraits = TargetTraits(
    keys = setOf(
        BTN_SOUTH, BTN_EAST, 0x132, BTN_NORTH, BTN_WEST, 0x135, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
        BTN_SELECT, BTN_START, 0x13c, BTN_THUMBL, BTN_THUMBR,
        BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT,
    ),
    axes = setOf(ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y),
    keyLabels = mapOf(
        BTN_SOUTH to "BUTTON_A", BTN_EAST to "BUTTON_B",
        BTN_NORTH to "BUTTON_X", BTN_WEST to "BUTTON_Y",
        BTN_TL to "BUTTON_L1", BTN_TR to "BUTTON_R1",
        BTN_TL2 to "BUTTON_L2", BTN_TR2 to "BUTTON_R2",
        BTN_SELECT to "BUTTON_SELECT", BTN_START to "BUTTON_START",
        BTN_THUMBL to "BUTTON_THUMBL", BTN_THUMBR to "BUTTON_THUMBR",
    ),
    axisLabels = mapOf(
        ABS_X to "X", ABS_Y to "Y", ABS_Z to "Z", ABS_RZ to "RZ",
        ABS_GAS to "RTRIGGER", ABS_BRAKE to "LTRIGGER",
        ABS_HAT0X to "HAT_X", ABS_HAT0Y to "HAT_Y",
    ),
)

/**
 * Build the slot list from what the target actually declares.
 *
 * Every decision here prefers the device's own answer over a convention:
 *
 * - **Face letters** come from the key layout file. On this firmware `0x133` is X and `0x134` is Y —
 *   the reverse of what the kernel header names (`BTN_NORTH`/`BTN_WEST`) suggest for an Xbox pad,
 *   and following the convention instead of the file is what once shipped X and Y swapped.
 * - **The d-pad** goes to the hat when the target has one, because that is what a pad with a hat
 *   reports natively and what its key layout listens to; only a target without a hat gets the
 *   `BTN_DPAD_*` keys.
 * - **Triggers** go to the axes the layout calls triggers (`LTRIGGER`/`RTRIGGER`/`BRAKE`/`GAS`),
 *   staying analog; a target without trigger axes gets the `BTN_TL2`/`BTN_TR2` keys.
 * - **The right stick** is whichever declared pair is not spoken for by the triggers: `Z`/`RZ` on
 *   HID-style pads (this one), `RX`/`RY` on xpad-style pads where `Z`/`RZ` are the triggers.
 * - A control the target simply does not have produces no slot at all — a slot that can only ever
 *   feed dropped events is worse than its absence.
 */
fun mappingSlots(traits: TargetTraits): List<MappingSlot> {
    val slots = mutableListOf<MappingSlot>()

    fun key(code: Int) = code in traits.keys
    fun axis(code: Int) = code in traits.axes
    // The code the layout file maps to this Android button, falling back to the Generic.kl
    // convention for a target whose layout we could not read.
    fun labelled(label: String, fallback: Int): Int? {
        val code = traits.keyLabels.entries.firstOrNull { it.value == label }?.key ?: fallback
        return code.takeIf(::key)
    }

    // Face buttons, lettered by the layout file.
    labelled("BUTTON_A", BTN_SOUTH)?.let {
        slots += MappingSlot("A", ControlRef.button(it), SlotIcon.A)
    }
    labelled("BUTTON_B", BTN_EAST)?.let {
        slots += MappingSlot("B", ControlRef.button(it), SlotIcon.B)
    }
    labelled("BUTTON_X", BTN_NORTH)?.let {
        slots += MappingSlot("X", ControlRef.button(it), SlotIcon.X)
    }
    labelled("BUTTON_Y", BTN_WEST)?.let {
        slots += MappingSlot("Y", ControlRef.button(it), SlotIcon.Y)
    }

    labelled("BUTTON_L1", BTN_TL)?.let {
        slots += MappingSlot("Left bumper", ControlRef.button(it), SlotIcon.LEFT_BUMPER)
    }
    labelled("BUTTON_R1", BTN_TR)?.let {
        slots += MappingSlot("Right bumper", ControlRef.button(it), SlotIcon.RIGHT_BUMPER)
    }

    // Trigger axes: what the layout calls a trigger, wherever it lives.
    val leftTrigger = axisLabelled(traits, setOf("LTRIGGER", "BRAKE"), ABS_BRAKE)
    val rightTrigger = axisLabelled(traits, setOf("RTRIGGER", "GAS"), ABS_GAS)
    when {
        leftTrigger != null ->
            slots += MappingSlot("Left trigger", ControlRef.half(leftTrigger, 1), SlotIcon.LEFT_TRIGGER)
        key(BTN_TL2) ->
            slots += MappingSlot("Left trigger", ControlRef.button(BTN_TL2), SlotIcon.LEFT_TRIGGER)
    }
    when {
        rightTrigger != null ->
            slots += MappingSlot("Right trigger", ControlRef.half(rightTrigger, 1), SlotIcon.RIGHT_TRIGGER)
        key(BTN_TR2) ->
            slots += MappingSlot("Right trigger", ControlRef.button(BTN_TR2), SlotIcon.RIGHT_TRIGGER)
    }

    // D-pad: the hat when there is one, the key codes otherwise. evdev counts hat Y downward, so up
    // is the negative half.
    if (axis(ABS_HAT0X) && axis(ABS_HAT0Y)) {
        slots += MappingSlot("D-pad up", ControlRef.half(ABS_HAT0Y, -1), SlotIcon.DPAD_UP)
        slots += MappingSlot("D-pad down", ControlRef.half(ABS_HAT0Y, 1), SlotIcon.DPAD_DOWN)
        slots += MappingSlot("D-pad left", ControlRef.half(ABS_HAT0X, -1), SlotIcon.DPAD_LEFT)
        slots += MappingSlot("D-pad right", ControlRef.half(ABS_HAT0X, 1), SlotIcon.DPAD_RIGHT)
    } else if (key(BTN_DPAD_UP)) {
        slots += MappingSlot("D-pad up", ControlRef.button(BTN_DPAD_UP), SlotIcon.DPAD_UP)
        slots += MappingSlot("D-pad down", ControlRef.button(BTN_DPAD_DOWN), SlotIcon.DPAD_DOWN)
        slots += MappingSlot("D-pad left", ControlRef.button(BTN_DPAD_LEFT), SlotIcon.DPAD_LEFT)
        slots += MappingSlot("D-pad right", ControlRef.button(BTN_DPAD_RIGHT), SlotIcon.DPAD_RIGHT)
    }

    // Sticks. Up is the axis's NEGATIVE half: evdev counts Y downward.
    if (axis(ABS_X) && axis(ABS_Y)) {
        slots += MappingSlot("Left stick up", ControlRef.half(ABS_Y, -1), SlotIcon.LEFT_STICK_UP)
        slots += MappingSlot("Left stick down", ControlRef.half(ABS_Y, 1), SlotIcon.LEFT_STICK_DOWN)
        slots += MappingSlot("Left stick left", ControlRef.half(ABS_X, -1), SlotIcon.LEFT_STICK_LEFT)
        slots += MappingSlot("Left stick right", ControlRef.half(ABS_X, 1), SlotIcon.LEFT_STICK_RIGHT)
    }
    val taken = setOfNotNull(leftTrigger, rightTrigger)
    val rightPair = listOf(ABS_Z to ABS_RZ, ABS_RX to ABS_RY).firstOrNull { (x, y) ->
        axis(x) && axis(y) && x !in taken && y !in taken
    }
    if (rightPair != null) {
        val (rx, ry) = rightPair
        slots += MappingSlot("Right stick up", ControlRef.half(ry, -1), SlotIcon.RIGHT_STICK_UP)
        slots += MappingSlot("Right stick down", ControlRef.half(ry, 1), SlotIcon.RIGHT_STICK_DOWN)
        slots += MappingSlot("Right stick left", ControlRef.half(rx, -1), SlotIcon.RIGHT_STICK_LEFT)
        slots += MappingSlot("Right stick right", ControlRef.half(rx, 1), SlotIcon.RIGHT_STICK_RIGHT)
    }

    labelled("BUTTON_SELECT", BTN_SELECT)?.let {
        slots += MappingSlot("Select", ControlRef.button(it), SlotIcon.VIEW)
    }
    labelled("BUTTON_START", BTN_START)?.let {
        slots += MappingSlot("Start", ControlRef.button(it), SlotIcon.MENU)
    }
    labelled("BUTTON_THUMBL", BTN_THUMBL)?.let {
        slots += MappingSlot("Left stick click", ControlRef.button(it), SlotIcon.LEFT_STICK_CLICK)
    }
    labelled("BUTTON_THUMBR", BTN_THUMBR)?.let {
        slots += MappingSlot("Right stick click", ControlRef.button(it), SlotIcon.RIGHT_STICK_CLICK)
    }
    // The daemon writes these to the target node (the Thor declares and maps them), so a target
    // without them would silently drop the press: offered only when declared.
    if (key(KEY_HOME)) slots += MappingSlot("Home", ControlRef.button(KEY_HOME), SlotIcon.HOME)
    if (key(KEY_BACK)) slots += MappingSlot("Back", ControlRef.button(KEY_BACK), SlotIcon.BACK)

    return slots
}

private fun axisLabelled(traits: TargetTraits, labels: Set<String>, fallback: Int): Int? {
    val code = traits.axisLabels.entries.firstOrNull { it.value in labels }?.key ?: fallback
    return code.takeIf { it in traits.axes }
}

/**
 * How a bound control reads in the list.
 *
 * Named by the standard evdev meaning of its code, which is what nearly every pad reports: a reader
 * wants "A / South", not "Button 304". Both halves of a face name are given because the code says
 * where the button sits, not what is printed on it — a Nintendo pad's A reports as East. A code with
 * no standard meaning keeps its number.
 */
fun describeSource(source: ControlRef, axisLabels: Map<Int, String> = emptyMap()): String = when (source.kind) {
    ControlKind.BUTTON -> BUTTON_NAMES[source.code] ?: "Button ${source.code}"
    ControlKind.AXIS -> if (source.direction < 0) "Axis ${source.code} (inverted)" else "Axis ${source.code}"
    // The pad's own key layout beats the evdev default: xpad reports the triggers on Z/RZ, where
    // the default has the right stick.
    ControlKind.HALF_AXIS -> (axisLabels[source.code]?.let { ANDROID_AXIS_NAMES[it] } ?: HALF_AXIS_NAMES[source.code])
        ?.let { if (source.direction < 0) it.first else it.second }
        ?: "${if (source.direction < 0) "−" else "+"}Axis ${source.code}"
}

private val BUTTON_NAMES = mapOf(
    BTN_SOUTH to "A / South", BTN_EAST to "B / East", BTN_NORTH to "X / North", BTN_WEST to "Y / West",
    BTN_TL to "L1", BTN_TR to "R1", BTN_TL2 to "L2", BTN_TR2 to "R2",
    BTN_SELECT to "Select", BTN_START to "Start", 0x13c to "Guide", BTN_THUMBL to "L3", BTN_THUMBR to "R3",
    BTN_DPAD_UP to "D-pad up", BTN_DPAD_DOWN to "D-pad down",
    BTN_DPAD_LEFT to "D-pad left", BTN_DPAD_RIGHT to "D-pad right",
)

/** Android axis name -> (negative half, positive half). evdev counts Y downward, so up is negative. */
private val ANDROID_AXIS_NAMES = mapOf(
    "X" to ("Left stick left" to "Left stick right"),
    "Y" to ("Left stick up" to "Left stick down"),
    "Z" to ("Right stick left" to "Right stick right"),
    "RZ" to ("Right stick up" to "Right stick down"),
    "RX" to ("Right stick left" to "Right stick right"),
    "RY" to ("Right stick up" to "Right stick down"),
    "HAT_X" to ("D-pad left" to "D-pad right"),
    "HAT_Y" to ("D-pad up" to "D-pad down"),
    "LTRIGGER" to ("−L2" to "+L2"),
    "BRAKE" to ("−L2" to "+L2"),
    "RTRIGGER" to ("−R2" to "+R2"),
    "GAS" to ("−R2" to "+R2"),
)

/** Axis code -> Android axis name, by the standard evdev meaning: what Generic.kl calls each code. */
private val GENERIC_AXIS_NAMES = mapOf(
    ABS_X to "X", ABS_Y to "Y", ABS_Z to "Z", ABS_RZ to "RZ", ABS_RX to "RX", ABS_RY to "RY",
    ABS_HAT0X to "HAT_X", ABS_HAT0Y to "HAT_Y", ABS_BRAKE to "BRAKE", ABS_GAS to "GAS",
)

/** Axis code -> halves, by the standard evdev meaning. */
private val HALF_AXIS_NAMES = GENERIC_AXIS_NAMES.mapValues { ANDROID_AXIS_NAMES.getValue(it.value) }

/**
 * The prompt icon for a control the pad reports, for showing a chord as icons; null when the code has
 * no standard meaning (the caller falls back to [describeSource]). Face codes say where a button sits,
 * so on a Nintendo-layout pad they are drawn with the letter printed there.
 */
fun sourceIcon(source: ControlRef, axisLabels: Map<Int, String> = emptyMap(), layout: PadLayout = PadLayout.XBOX): SlotIcon? {
    val icon = when (source.kind) {
        ControlKind.BUTTON -> BUTTON_ICONS[source.code]
        ControlKind.AXIS -> null
        ControlKind.HALF_AXIS -> (axisLabels[source.code] ?: GENERIC_AXIS_NAMES[source.code])
            ?.let { AXIS_ICONS[it] }
            ?.let { if (source.direction < 0) it.first else it.second }
    } ?: return null
    return if (layout == PadLayout.NINTENDO) NINTENDO_FACES[icon] ?: icon else icon
}

private val BUTTON_ICONS = mapOf(
    BTN_SOUTH to SlotIcon.A, BTN_EAST to SlotIcon.B, BTN_NORTH to SlotIcon.X, BTN_WEST to SlotIcon.Y,
    BTN_TL to SlotIcon.LEFT_BUMPER, BTN_TR to SlotIcon.RIGHT_BUMPER,
    BTN_TL2 to SlotIcon.LEFT_TRIGGER, BTN_TR2 to SlotIcon.RIGHT_TRIGGER,
    BTN_SELECT to SlotIcon.VIEW, BTN_START to SlotIcon.MENU, 0x13c to SlotIcon.HOME,
    BTN_THUMBL to SlotIcon.LEFT_STICK_CLICK, BTN_THUMBR to SlotIcon.RIGHT_STICK_CLICK,
    BTN_DPAD_UP to SlotIcon.DPAD_UP, BTN_DPAD_DOWN to SlotIcon.DPAD_DOWN,
    BTN_DPAD_LEFT to SlotIcon.DPAD_LEFT, BTN_DPAD_RIGHT to SlotIcon.DPAD_RIGHT,
)

/** Android axis name -> (negative half, positive half) icon; null where a half means nothing (a trigger's). */
private val AXIS_ICONS: Map<String, Pair<SlotIcon?, SlotIcon?>> = mapOf(
    "X" to (SlotIcon.LEFT_STICK_LEFT to SlotIcon.LEFT_STICK_RIGHT),
    "Y" to (SlotIcon.LEFT_STICK_UP to SlotIcon.LEFT_STICK_DOWN),
    "Z" to (SlotIcon.RIGHT_STICK_LEFT to SlotIcon.RIGHT_STICK_RIGHT),
    "RX" to (SlotIcon.RIGHT_STICK_LEFT to SlotIcon.RIGHT_STICK_RIGHT),
    "RZ" to (SlotIcon.RIGHT_STICK_UP to SlotIcon.RIGHT_STICK_DOWN),
    "RY" to (SlotIcon.RIGHT_STICK_UP to SlotIcon.RIGHT_STICK_DOWN),
    "HAT_X" to (SlotIcon.DPAD_LEFT to SlotIcon.DPAD_RIGHT),
    "HAT_Y" to (SlotIcon.DPAD_UP to SlotIcon.DPAD_DOWN),
    "LTRIGGER" to (null to SlotIcon.LEFT_TRIGGER),
    "BRAKE" to (null to SlotIcon.LEFT_TRIGGER),
    "RTRIGGER" to (null to SlotIcon.RIGHT_TRIGGER),
    "GAS" to (null to SlotIcon.RIGHT_TRIGGER),
)

/**
 * Which layout a pad is on: the two presets, by which way round its face buttons are printed, or the
 * user's own [CUSTOM] one, set up once with Guided setup and kept to switch back to. A custom layout
 * is drawn with the Xbox prompts, Android's own naming for the buttons.
 */
enum class PadLayout { XBOX, NINTENDO, CUSTOM }

/**
 * A whole mapping for a pad of this layout: every slot bound to the control that is printed with the
 * slot's name, Home and Back left alone.
 *
 * Keyed by slot, not by code: the target side comes from [slots] (the live target's own key layout),
 * so this only has to decide which SOURCE code drives each one. The source codes are what the source
 * pad declares, read through the same rules [mappingSlots] applies to a target — hat or d-pad keys,
 * trigger axes or trigger keys, Z/RZ or RX/RY — so a Switch Pro (d-pad keys, digital ZL/ZR, RX/RY
 * stick) gets its own codes rather than an Xbox pad's. With nothing to inspect it assumes the
 * standard HID pad: hat d-pad, Z/RZ right stick, BRAKE/GAS triggers.
 *
 * The face letters, worked through for the Thor:
 * - Its target (`2020:0111`) layout says 0x130 BUTTON_A, 0x131 BUTTON_B, 0x133 BUTTON_X, 0x134
 *   BUTTON_Y, so the "A" slot's target is 0x130, and so on.
 * - The daemon applies its Nintendo A/B, X/Y swap only to UNBOUND events (`forward_event`'s `bound`
 *   flag). A preset binds all four, so a bound event reaches the target as the slot's own code
 *   unswapped: whatever drives the A slot arrives as 0x130 and Android reads BUTTON_A.
 * - So the only question is what the pad reports for its printed A. An Xbox pad reports by label in
 *   evdev's own naming: A 0x130, B 0x131, X 0x133, Y 0x134. A Nintendo pad under hid-nintendo reports
 *   by POSITION in that same Xbox naming: its A sits east (0x131), B south (0x130), X north where
 *   Xbox has Y (0x134), Y west where Xbox has X (0x133). Hence A←0x131, B←0x130, X←0x134, Y←0x133.
 */
fun presetBindings(layout: PadLayout, slots: List<MappingSlot>, source: TargetTraits?): Map<ControlRef, ControlRef> {
    // A label-free read of the source, so its face codes are the Xbox naming above, never its own
    // .kl's idea of them (which would swap a Nintendo pad a second time).
    val traits = source?.copy(keyLabels = emptyMap(), axisLabels = emptyMap()) ?: odinFallbackTraits()
    val sourceFor = mappingSlots(traits)
        .filter { it.icon != SlotIcon.HOME && it.icon != SlotIcon.BACK }
        .associate { it.icon to it.target }
    return slots.mapNotNull { slot ->
        val icon = if (layout == PadLayout.NINTENDO) NINTENDO_FACES[slot.icon] ?: slot.icon else slot.icon
        sourceFor[icon]?.let { slot.target to it }
    }.toMap()
}

/** The Xbox-named control that sits where a Nintendo pad prints this letter. */
private val NINTENDO_FACES = mapOf(
    SlotIcon.A to SlotIcon.B, SlotIcon.B to SlotIcon.A,
    SlotIcon.X to SlotIcon.Y, SlotIcon.Y to SlotIcon.X,
)
