package com.controlswitch.domain.repository

import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.ControllerMapping
import com.controlswitch.domain.model.MappingKey

/**
 * Where a captured mapping lives between sessions.
 *
 * Keyed by the real controller rather than by the mirror source: this firmware republishes every
 * external pad under one shared vendor:product, so keying on what the mirror opens would hand the
 * second controller the first one's mapping.
 */
interface MappingRepository {
    /** The mapping saved for this controller, or an empty one when it has never been captured. */
    fun get(key: MappingKey): ControllerMapping

    fun save(key: MappingKey, mapping: ControllerMapping)

    /** Forget this controller's mapping, returning it to the daemon's defaults. */
    fun clear(key: MappingKey)

    /**
     * Whether the stored mapping is an untouched database default, as opposed to something the user
     * shaped. The distinction is presentational: a seeded pad just works and needs no callout, while
     * the "custom mapping" badge means the user made it and should only ever mean that.
     */
    fun isSeeded(key: MappingKey): Boolean = false

    fun setSeeded(key: MappingKey, seeded: Boolean) {}

    /**
     * The layout preset last applied to this controller; picks which console's button icons to show.
     * Nintendo when none has been: the app is built for the AYN Thor, a Nintendo-layout handheld.
     */
    fun layout(key: MappingKey): PadLayout = storedLayout(key) ?: PadLayout.NINTENDO

    /** The layout set for this controller, or null if none ever was (it then gets player 1's). */
    fun storedLayout(key: MappingKey): PadLayout? = null

    fun setLayout(key: MappingKey, layout: PadLayout) {}

    /**
     * The layout the user last picked, for player 1 whichever pad that is: the app exists to fix
     * player 1, so a layout belongs to the slot rather than to one controller. Null when there is no
     * choice to follow, when every pad keeps whatever it has.
     */
    fun player1Layout(): PadLayout? = null

    fun setPlayer1Layout(layout: PadLayout) {}

    /**
     * This controller's custom layout, kept apart from its live mapping so a preset can replace that
     * and switching back to Custom brings the user's own buttons back. Per controller, since the
     * bindings name that pad's own codes. Null until one has been set up.
     */
    fun customMapping(key: MappingKey): ControllerMapping? = null

    fun saveCustomMapping(key: MappingKey, mapping: ControllerMapping) {}
}
