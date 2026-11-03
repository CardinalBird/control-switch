package com.controlswitch.data

import com.controlswitch.domain.model.PadLayout
import android.content.Context
import com.controlswitch.domain.model.ControllerMapping
import com.controlswitch.domain.model.MappingKey
import com.controlswitch.domain.repository.MappingRepository

internal class AndroidMappingRepository(context: Context) : MappingRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: MappingKey): ControllerMapping = parseMapping(prefs.getString(keyOf(key), null))

    override fun save(key: MappingKey, mapping: ControllerMapping) {
        if (mapping.isEmpty) {
            // A wizard the user skipped their way through leaves nothing behind, rather than an
            // empty record that would read as "this controller has a custom mapping".
            clear(key)
            return
        }
        prefs.edit().putString(keyOf(key), mapping.serialize()).apply()
    }

    override fun clear(key: MappingKey) {
        // The flag goes with the mapping it describes: a cleared pad re-seeds as if never seen.
        prefs.edit().remove(keyOf(key)).remove(seededKeyOf(key)).apply()
    }

    override fun isSeeded(key: MappingKey): Boolean = prefs.getBoolean(seededKeyOf(key), false)

    override fun setSeeded(key: MappingKey, seeded: Boolean) {
        prefs.edit().apply {
            if (seeded) putBoolean(seededKeyOf(key), true) else remove(seededKeyOf(key))
        }.apply()
    }

    override fun storedLayout(key: MappingKey): PadLayout? =
        PadLayout.entries.firstOrNull { it.name == prefs.getString(layoutKeyOf(key), null) }

    override fun setLayout(key: MappingKey, layout: PadLayout) {
        prefs.edit().putString(layoutKeyOf(key), layout.name).apply()
    }

    override fun customMapping(key: MappingKey): ControllerMapping? =
        prefs.getString(customKeyOf(key), null)?.let(::parseMapping)?.takeIf { !it.isEmpty }

    override fun saveCustomMapping(key: MappingKey, mapping: ControllerMapping) {
        if (mapping.isEmpty) return
        prefs.edit().putString(customKeyOf(key), mapping.serialize()).apply()
    }

    // Null until the user picks one: [MappingEditorController.followPlayer1Layout] then gives only an
    // untouched pad the Nintendo default, rather than every pad that isn't already on Nintendo.
    override fun player1Layout(): PadLayout? =
        PadLayout.entries.firstOrNull { it.name == prefs.getString(PLAYER1_LAYOUT, null) }

    override fun setPlayer1Layout(layout: PadLayout) {
        prefs.edit().putString(PLAYER1_LAYOUT, layout.name).apply()
    }

    // Namespaced so a controller's mapping can never collide with the mirror's own settings, which
    // share this preferences file.
    private fun keyOf(key: MappingKey) = "$KEY_MAPPING_PREFIX${key.value}"

    private fun layoutKeyOf(key: MappingKey) = "${KEY_MAPPING_PREFIX}layout_${key.value}"

    private fun seededKeyOf(key: MappingKey) = "${KEY_MAPPING_PREFIX}seeded_${key.value}"

    private fun customKeyOf(key: MappingKey) = "${KEY_MAPPING_PREFIX}custom_${key.value}"

    private companion object {
        // Under the mapping prefix like the rest, but not shaped like a key: no pad's id is this.
        const val PLAYER1_LAYOUT = "${KEY_MAPPING_PREFIX}player1_layout"
    }
}
