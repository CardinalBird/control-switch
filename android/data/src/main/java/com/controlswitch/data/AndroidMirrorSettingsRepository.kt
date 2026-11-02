package com.controlswitch.data

import com.controlswitch.domain.model.AppTheme
import com.controlswitch.domain.model.HomeButtonMode
import android.content.Context
import com.controlswitch.domain.model.MirrorSettings
import com.controlswitch.domain.model.MirrorStartRequest
import com.controlswitch.domain.repository.MirrorSettingsRepository

class AndroidMirrorSettingsRepository(context: Context) : MirrorSettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun getSettings(): MirrorSettings {
        return MirrorSettings(
            source = prefs.getString(KEY_SOURCE, null),
            target = prefs.getString(KEY_TARGET, null),
            sourceGuid = prefs.getString(KEY_SOURCE_GUID, null),
            targetGuid = prefs.getString(KEY_TARGET_GUID, null),
            homeMode = readHomeMode(),
            comboHoldKillApp = prefs.getBoolean(KEY_COMBO_HOLD_KILL_APP, false),
            rumbleStrength = prefs.getInt(KEY_RUMBLE_STRENGTH, 80),
            autoMirrorEnabled = prefs.getBoolean(KEY_AUTO_MIRROR_ENABLED, true),
            mirrorWhenHandheld = prefs.getBoolean(KEY_MIRROR_WHEN_HANDHELD, false),
            theme = AppTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: AppTheme.DARK,
            expectedRunning = prefs.getBoolean(KEY_EXPECTED_RUNNING, false),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            manualInternalGuid = prefs.getString(KEY_MANUAL_INTERNAL_GUID, null),
            player1Name = prefs.getString(KEY_PLAYER1_NAME, null),
            player1Path = prefs.getString(KEY_PLAYER1_PATH, null),
            configGeneration = prefs.getLong(KEY_CONFIG_GENERATION, 0L),
        )
    }

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        prefs.edit()
            .putString(KEY_SOURCE, request.source)
            .putString(KEY_TARGET, request.target)
            .putString(KEY_SOURCE_GUID, request.sourceGuid)
            .putString(KEY_TARGET_GUID, request.targetGuid)
            // Not the options: their setters already stored them, and writing the request's copy back
            // would undo a toggle made while this start was in flight.
            .putBoolean(KEY_EXPECTED_RUNNING, true)
            .putLong(KEY_STARTED_AT, startedAt)
            .apply()
    }

    // An upstream install that had Home-as-Back on keeps Back; everything else gets the new default.
    private fun readHomeMode(): HomeButtonMode {
        prefs.getString(KEY_HOME_MODE, null)?.let { saved ->
            HomeButtonMode.entries.firstOrNull { it.name == saved }?.let { return it }
        }
        return if (prefs.getBoolean(KEY_HOME_AS_BACK, false)) HomeButtonMode.BACK else HomeButtonMode.HOME
    }

    override fun setExpectedRunning(expectedRunning: Boolean) {
        prefs.edit().putBoolean(KEY_EXPECTED_RUNNING, expectedRunning).apply()
    }

    override fun setHomeMode(mode: HomeButtonMode) = synchronized(generationLock) {
        prefs.edit()
            .putString(KEY_HOME_MODE, mode.name)
            .putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1)
            .apply()
    }

    override fun setComboHoldKillAppEnabled(enabled: Boolean) = setLiveOption(KEY_COMBO_HOLD_KILL_APP, enabled)


    override fun setRumbleStrength(percent: Int) = synchronized(generationLock) {
        prefs.edit()
            .putInt(KEY_RUMBLE_STRENGTH, percent.coerceIn(0, 100))
            .putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1)
            .apply()
    }

    // Options the running daemon can adopt without being restarted. The generation advances in the
    // same commit as the value: it is the number the daemon acknowledges once it has adopted the
    // change, so a value stored without advancing it would be a change nobody ever asks it to make.
    override fun bumpConfigGeneration() = synchronized(generationLock) {
        prefs.edit().putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1).apply()
    }

    private fun setLiveOption(key: String, enabled: Boolean) = synchronized(generationLock) {
        prefs.edit()
            .putBoolean(key, enabled)
            .putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1)
            .apply()
    }

    private companion object {
        /*
         * The generation is read-modify-written, and every writer runs on its own IO coroutine: two
         * toggles flipped in quick succession both read N and both write N+1, so the supervisor sees
         * the number it already pushed and the second change never reaches the daemon. It reads as a
         * setting that silently did nothing.
         *
         * Held on the companion rather than the instance because the settings are one file and
         * nothing guarantees one repository object per process — two instances synchronizing on
         * themselves would take two different locks and protect nothing.
         */
        val generationLock = Any()
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MIRROR_ENABLED, enabled).apply()
    }

    // Not a live option: the supervisor, not the daemon, acts on it.
    override fun setMirrorWhenHandheld(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRROR_WHEN_HANDHELD, enabled).apply()
    }

    // UI only: nothing outside the app reads it.
    override fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    override fun setManualInternalController(guid: String?) {
        prefs.edit().apply {
            if (guid.isNullOrBlank()) {
                remove(KEY_MANUAL_INTERNAL_GUID)
            } else {
                putString(KEY_MANUAL_INTERNAL_GUID, guid)
            }
        }.apply()
    }

    // A new source differs from the running one, so the supervisor restarts the mirror on its own.
    override fun setPlayer1(name: String?, path: String?) {
        prefs.edit().putString(KEY_PLAYER1_NAME, name).putString(KEY_PLAYER1_PATH, path).apply()
    }
}
