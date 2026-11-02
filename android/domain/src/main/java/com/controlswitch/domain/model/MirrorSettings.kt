package com.controlswitch.domain.model

/** The app's colour theme, picked in Additional Settings. */
enum class AppTheme { DARK, SUPER_DARK, LIGHT }

data class MirrorSettings(
    val source: String? = null,
    val target: String? = null,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    val homeMode: HomeButtonMode = HomeButtonMode.HOME,
    val comboHoldKillApp: Boolean = false,
    // Always false since 0.7: the Select + R3 pointer never worked reliably and its switch is gone
    // from Additional Settings. ponytail: the daemon still carries mouse.c and the --virtual-mouse
    // flag this feeds; delete both together with an on-device e2e run.
    val virtualMouse: Boolean = false,

    // How hard the pad rumbles when a game vibrates the Thor, 0 (off) to 100 percent.
    val rumbleStrength: Int = 80,
    val autoMirrorEnabled: Boolean = true,
    // Mirror whenever an external pad is connected, docked or not. Off means docked only.
    val mirrorWhenHandheld: Boolean = false,
    val theme: AppTheme = AppTheme.DARK,
    val expectedRunning: Boolean = false,
    val startedAt: Long = 0L,
    val manualInternalGuid: String? = null,
    // The pad locked in as player 1 with L + R; null means the first external pad.
    val player1Name: String? = null,
    // The node it was picked on; tells identical pads apart until a reconnect moves it.
    val player1Path: String? = null,
    // Advances every time a live option changes. The running daemon echoes the generation it has
    // actually adopted in its heartbeat, so comparing the two says whether a toggle has taken
    // effect — which is what lets a settings change reach the daemon without restarting it.
    val configGeneration: Long = 0L,
)
