package com.controlswitch.domain.repository

import com.controlswitch.domain.model.AppTheme
import com.controlswitch.domain.model.HomeButtonMode
import com.controlswitch.domain.model.MirrorSettings
import com.controlswitch.domain.model.MirrorStartRequest

interface MirrorSettingsRepository {
    fun getSettings(): MirrorSettings
    fun saveStarted(request: MirrorStartRequest, startedAt: Long)
    fun setExpectedRunning(expectedRunning: Boolean)
    fun setHomeMode(mode: HomeButtonMode)
    fun setComboHoldKillAppEnabled(enabled: Boolean)

    fun setRumbleStrength(percent: Int)
    fun setAutoMirrorEnabled(enabled: Boolean)
    fun setMirrorWhenHandheld(enabled: Boolean)
    fun setTheme(theme: AppTheme)
    fun setManualInternalController(guid: String?)
    fun setPlayer1(name: String?, path: String?)

    /**
     * Advance the config generation without changing a setting.
     *
     * Saving a mapping has to reach the running daemon, and the generation is what makes it look:
     * the supervisor pushes the config when the number it sees differs from the one stored here.
     */
    fun bumpConfigGeneration()
}
