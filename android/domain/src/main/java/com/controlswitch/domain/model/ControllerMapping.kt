package com.controlswitch.domain.model

/**
 * What one end of a binding refers to.
 *
 * The wire values are the daemon's, because this travels to it as numbers. Naming a kind is what lets
 * the two ends disagree: a controller that reports its triggers as axes can still fill a button slot,
 * and a button can stand in for a stick direction — neither of which was expressible while buttons and
 * axes lived in separate tables that could not cross.
 */
enum class ControlKind(val wire: Int) {
    /** A button. Direction is meaningless and always stored as 0. */
    BUTTON(0),

    /** A whole axis. Direction is its orientation: 1 as-is, -1 flipped. */
    AXIS(1),

    /** One half of an axis's travel, measured from rest. Direction says which half. */
    HALF_AXIS(2),
}

/**
 * One end of a binding: which control, and — on an axis — which way.
 *
 * @param direction 1 or -1 on an axis; 0 on a button.
 */
data class ControlRef(val kind: ControlKind, val code: Int, val direction: Int = 0) {
    companion object {
        fun button(code: Int) = ControlRef(ControlKind.BUTTON, code)
        fun axis(code: Int, direction: Int = 1) =
            ControlRef(ControlKind.AXIS, code, if (direction < 0) -1 else 1)

        fun half(code: Int, direction: Int) =
            ControlRef(ControlKind.HALF_AXIS, code, if (direction < 0) -1 else 1)
    }
}

/** One remapped control: what the pad reports, and what the target should be told instead. */
data class Binding(val source: ControlRef, val target: ControlRef)

/**
 * A target driven by 2 to [MAX_MEMBERS] controls held together. Kept apart from [Binding] so code that
 * reads one source per binding can never mistake a chord for its first member.
 */
data class Chord(val sources: List<ControlRef>, val target: ControlRef) {
    companion object {
        const val MAX_MEMBERS = 4

        /** The daemon's MAX_CHORDS: rows past it are never read. */
        const val MAX_PER_MAPPING = 16
    }
}

/** Linux BTN_SELECT / BTN_START: what every standard pad driver, Nintendo's included, reports them as. */
private const val BTN_SELECT_CODE = 0x13a
private const val BTN_START_CODE = 0x13b

/** Select + Start held together as the handheld's Back, the "Select + Start is Back" setting. */
val SELECT_START_BACK = Chord(
    listOf(ControlRef.button(BTN_SELECT_CODE), ControlRef.button(BTN_START_CODE)),
    ControlRef.button(KEY_BACK),
)

/** Whether this is [SELECT_START_BACK], held in either order. */
fun Chord.isSelectStartBack(): Boolean =
    target == SELECT_START_BACK.target && sources.toSet() == SELECT_START_BACK.sources.toSet()

/**
 * The mapping as the mirror should run it, with the "Select + Start is Back" setting applied: the
 * chord added when the setting is on and the user has not put Back on anything else, and gone when it
 * is off. Applied on the way to the daemon rather than saved, so the setting stays one switch for
 * every pad, and a Back the user bound themselves always wins.
 *
 * A Select + Start → Back chord already saved (Guided setup used to walk Back, and that is what most
 * people pressed there) is treated as the setting's, which is why it goes when the setting is off.
 */
fun ControllerMapping.withSelectStartBack(enabled: Boolean): ControllerMapping {
    val own = copy(chords = chords.filterNot { it.isSelectStartBack() })
    if (!enabled) return own
    val back = SELECT_START_BACK.target
    val backTaken = own.bindings.any { it.target == back } || own.chords.any { it.target == back }
    return if (backTaken) own else own.copy(chords = own.chords + SELECT_START_BACK)
}

/**
 * A user-captured correction for a controller whose layout doesn't match what the mirror assumes.
 *
 * Codes are raw evdev codes on both sides, because that is what the daemon forwards. A control the
 * user skipped simply isn't here, and the daemon forwards it untouched — which is what makes "skip"
 * mean "leave this one alone" rather than "break it".
 */
data class ControllerMapping(
    val bindings: List<Binding> = emptyList(),
    val chords: List<Chord> = emptyList(),
) {
    val isEmpty: Boolean get() = bindings.isEmpty() && chords.isEmpty()

    /** What the card reports: how many controls the user actually pinned down. */
    val boundControlCount: Int get() = bindings.size + chords.size

    /** True when some binding already reads this exact control, so a second one would fight it. */
    fun claims(source: ControlRef): Boolean = bindings.any { it.source == source }
}

/**
 * Which controller a mapping belongs to.
 *
 * NOT the mirror source's own vendor:product: this firmware republishes every external controller as
 * a `2020:0111` twin and deletes the original node, so that id is identical for every pad and
 * collides with a built-in virtual device. The real controller keeps its own entry in
 * `/proc/bus/input/devices`, and that is what identifies it.
 *
 * Two controllers of the same model share a key, and therefore a mapping — which is what their owner
 * would want anyway.
 */
@JvmInline
value class MappingKey(val value: String) {
    companion object {
        fun of(vendorId: Int, productId: Int): MappingKey =
            MappingKey(String.format("%04x:%04x", vendorId, productId))
    }
}
