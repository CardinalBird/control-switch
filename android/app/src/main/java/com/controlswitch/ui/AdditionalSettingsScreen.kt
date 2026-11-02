package com.controlswitch.ui

import com.controlswitch.domain.model.SlotIcon
import com.controlswitch.domain.model.AppTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.controlswitch.ui.theme.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The options that used to make the main menu scroll. Rows are one line each so the page fits
 * without scrolling; the focused row's explanation shows in the footer instead, Switch-style.
 */
@Composable
internal fun AdditionalSettingsScreen(
    state: MirrorUiState,
    viewModel: MirrorViewModel,
    active: Boolean,
    onOpenTests: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = active, onBack = onBack)
    val context = LocalContext.current
    // A binder call; asked once, not on every recomposition.
    val canPin = remember(context) { context.canPinShortcut() }
    var help by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }

    // Same anchoring as the main page, minus its window hook: leave touch mode, then retry until the
    // first row has really taken the focus (requestFocus() reports success before it does).
    val first = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        repeat(24) {
            if (hasFocus) return@LaunchedEffect
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
            delay(50)
        }
    }

    // Each row reports itself as the footer's text when it holds the focus.
    fun explains(text: String) = Modifier.onFocusChanged { if (it.hasFocus) help = text }

    Column(
        Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(title = "Additional Settings")
        Rule(Modifier.padding(horizontal = 28.dp))
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 120.dp, vertical = 2.dp)) {
            MenuRow(
                title = "Select + Start closes app",
                minHeight = 40.dp,
                onClick = { viewModel.toggleComboHoldKillApp(!state.comboHoldKillApp) },
                modifier = Modifier.focusRequester(first).then(explains("Hold both for 3 seconds to close the game you're in.")),
            ) { RowSwitch(state.comboHoldKillApp, !state.busy) }

            val always = state.mirrorWhenHandheld
            val flip = { viewModel.setMirrorWhenHandheld(!always) }
            MenuRow(
                title = "Allow mirroring",
                minHeight = 40.dp,
                onClick = flip,
                onLeft = flip,
                onRight = flip,
                modifier = explains(
                    if (always) "Always, when a controller is connected: handheld or docked."
                    else "Only when docked.",
                ),
            ) { RowValue("‹  " + (if (always) "Always" else "Only when docked") + "  ›", accent = true) }
            val strength = state.rumbleStrength
            // Steps land on tens: up to the next ten, down to the one below.
            val nudge = { by: Int ->
                viewModel.setRumbleStrength(if (by > 0) (strength / 10 + 1) * 10 else ((strength + 9) / 10 - 1) * 10)
            }
            MenuRow(
                title = "Rumble strength",
                minHeight = 40.dp,
                // A steps up and wraps to off after 100%, like the other ‹ › rows cycle.
                onClick = { if (strength >= 100) viewModel.setRumbleStrength(0) else nudge(10) },
                onLeft = { nudge(-10) },
                onRight = { nudge(10) },
                modifier = explains("How hard your controller rumbles when a game vibrates. 0% turns it off."),
            ) { RumbleValue(strength) }
            val themes = AppTheme.entries
            val step = { by: Int -> viewModel.setTheme(themes[(state.theme.ordinal + by + themes.size) % themes.size]) }
            MenuRow(
                title = "Theme",
                minHeight = 40.dp,
                onClick = { step(1) },
                onLeft = { step(-1) },
                onRight = { step(1) },
                modifier = explains("Left and right to change the colours."),
            ) {
                val name = when (state.theme) {
                    AppTheme.DARK -> "Dark"
                    AppTheme.SUPER_DARK -> "Super dark"
                    AppTheme.LIGHT -> "Light"
                }
                RowValue("‹  $name  ›", accent = true)
            }
            MenuRow(
                title = "Controller tests",
                minHeight = 40.dp,
                onClick = onOpenTests,
                modifier = explains("Check every button, stick and trigger on any connected controller."),
            )
            // Game frontends (Cocoon Shell on the Thor) often hide the Android app list, so the app
            // can ask to be pinned itself. Shown only when the home app takes pin requests.
            if (canPin) {
                MenuRow(
                    title = "Add to home screen",
                    minHeight = 40.dp,
                    onClick = { context.pinToHome() },
                    modifier = explains("Pin this app to your launcher."),
                )
            }
        }
        Footer(status = help, hints = listOf(HintAction(SlotIcon.B, "Back", onBack), HintAction(SlotIcon.A, "OK", null)))
    }
}

/** The strength as a small bar plus the number, inside the row's ‹ › arrows. */
@Composable
private fun RumbleValue(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RowValue("‹  ", accent = true)
        Box(Modifier.size(width = 80.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.border)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(Palette.cyan))
        }
        RowValue("  ${if (percent == 0) "Off" else "$percent%"}  ›", accent = true)
    }
}
