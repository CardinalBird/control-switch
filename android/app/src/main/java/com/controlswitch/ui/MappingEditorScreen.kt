package com.controlswitch.ui

import com.controlswitch.domain.model.SlotIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controlswitch.domain.model.MappingSlot
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * The mapping editor, sized to fit the Thor's screen with no scrolling: a sidebar with the layout,
 * the slot groups and Reset, and the chosen group's slots in one column beside it. Guided setup (Y)
 * walks every slot one press at a time.
 *
 * A screen rather than a dialog because it is the only thing the user is doing, and the controller is
 * busy answering it rather than driving the app behind. Back leaves.
 *
 * [controller] carries what the original callbacks do not: the walk, the presets, and listening for
 * a press in the app while the mirror is off. Without it those parts are simply not offered.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MappingEditorScreen(
    state: MappingEditorUiState,
    onBind: (MappingSlot) -> Unit,
    onClear: (MappingSlot) -> Unit,
    onCancelBind: () -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    controller: MappingEditorController? = null,
) {
    // Back means "close the overlay" while one is up, and "leave" otherwise — anything else would
    // make the overlay a trap, since the pad that would normally dismiss it is the one being captured.
    BackHandler(enabled = state.open) { if (state.capturing != null) onCancelBind() else onClose() }

    // One anchor per row, and a memory of which row opened the overlay.
    //
    // Both halves are load-bearing. The handheld opens windows in Touch input mode, where
    // requestFocus() is a silent no-op, so the mode has to be left first and the request retried
    // across a few frames — the focus node is not necessarily placed on the first pass. And when the
    // overlay closes, the node that held focus is destroyed with it: Compose then has nowhere to put
    // focus and the D-pad restarts from the top of the list, which after binding row twenty is a long
    // way from where the user was. Sending it back to the row they came from is the whole point.
    val rowFocus = remember(state.slots.size) { List(state.slots.size) { FocusRequester() } }
    var focusedRow by remember { mutableIntStateOf(0) }
    val inputModeManager = LocalInputModeManager.current
    val tabFocus = remember { SlotGroup.entries.associateWith { FocusRequester() } }
    var group by remember { mutableStateOf(state.slots.firstOrNull()?.let { groupOf(it.icon) } ?: SlotGroup.FACE) }

    // Which row wants the focus back, if any. Asked for explicitly rather than inferred from the
    // state, because only some changes should move the focus: leaving the overlay and clearing a row
    // both destroy the node that held it, but Clear All is pressed from the footer and has to leave
    // the focus right there — throwing the user to the top of a list they were done with is the
    // opposite of helpful.
    var restoreTo by remember { mutableStateOf<Int?>(null) }
    // Where focus really is, as the rows report it: requestFocus() says nothing about whether it took.
    var focusedSlot by remember { mutableStateOf(-1) }

    // A layout change remaps every button at once, so it says so: without a word back, the next B
    // was pressed to find out, and that B left the page (and, pressed again, the app).
    var banner by remember { mutableStateOf<BannerMessage?>(null) }
    // The layout waiting on Start in [LayoutConfirm]; null while no change is being asked about.
    var askLayout by remember { mutableStateOf<PadLayout?>(null) }
    // The banner waits for the switch to finish, so "layout on" is true when it is said.
    var switched by remember { mutableStateOf<PadLayout?>(null) }
    LaunchedEffect(state.switchingTo) {
        val target = state.switchingTo
        if (target != null) {
            switched = target
        } else {
            switched?.let {
                banner = BannerMessage(
                    if (it == PadLayout.CUSTOM) "Custom layout on: your own buttons are back."
                    else "${layoutName(it)} layout on. Every button now matches its printed letter.",
                )
            }
            switched = null
        }
    }
    LaunchedEffect(state.customJustSaved) {
        if (state.customJustSaved > 0) banner = BannerMessage("Custom layout saved. Pick it under Layout any time.")
    }
    // The layout shown on the row, moved with left and right; A asks to switch to it. Follows the
    // real one whenever that changes.
    var choice by remember(state.layout) { mutableStateOf(state.layout) }

    // Closing the overlay hands the row back. Also fires on the very first composition, with the
    // default of row zero, which is the initial anchor the D-pad needs.
    LaunchedEffect(state.capturing) {
        if (state.capturing == null) restoreTo = focusedRow
    }

    // With the mirror off the daemon cannot listen, so the app does.
    if (controller != null) {
        PadCaptureEffect(
            active = state.capturing != null && state.listenInApp,
            internalPadName = state.internalPadName,
            axisLabels = controller::axisLabels,
            onCaptured = controller::captured,
        )
    }

    LaunchedEffect(restoreTo, state.slots.size) {
        val target = restoreTo ?: return@LaunchedEffect
        // The row may sit in another tab (the walk crosses them all): show its group first.
        state.slots.getOrNull(target)?.let { group = groupOf(it.icon) }
        repeat(12) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            rowFocus.getOrNull(target)?.let { runCatching { it.requestFocus() } }
            delay(40)
            // Judged by where focus went, not by requestFocus(), which reports success even when the
            // request was refused (touch mode, a node not yet placed) and ended the loop on try one.
            if (focusedSlot == target) {
                restoreTo = null
                return@LaunchedEffect
            }
        }
        restoreTo = null
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Palette.screen).onPreviewKeyEvent {
            // Both halves: Android turns an unhandled Y press into Back.
            if (it.key != Key.ButtonY || state.capturing != null || controller == null) return@onPreviewKeyEvent false
            if (it.type == KeyEventType.KeyUp) controller.mapAll()
            true
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 10.dp)) {
                Text("Button Mapping", color = Palette.textPrimary, fontSize = 22.sp)
                Text(
                    if (state.seeded) {
                        // A known pad arrives already mapped from its profile; the editor's job then is
                        // adjustment, and saying so stops "why is everything filled in?" cold.
                        "${state.controllerName} · set up automatically. Pick a button to change it."
                    } else {
                        "${state.controllerName} · buttons you leave unbound work as normal."
                    },
                    color = Palette.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            Rule(Modifier.padding(horizontal = 28.dp))

            // Switch-settings shape: a sidebar to pick what to edit, the chosen group's buttons in
            // one column beside it. Up and down stay on one side; right goes in, left comes back.
            if (state.loading) {
                // The page is up the moment A is pressed; the pad's buttons follow in a moment. The
                // message waits a quarter second and fades in, so a quick load shows a calm blank page
                // rather than a message that blinks on and off.
                val showLoading = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(LOADING_DELAY_MS)
                    showLoading.animateTo(1f, tween(200))
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = showLoading.value },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Loading your controller's buttons…", color = Palette.textSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(modifier = Modifier.width(260.dp), color = Palette.cyan, trackColor = Palette.border)
                }
            } else Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp, vertical = 8.dp)) {
                Column(Modifier.width(SIDEBAR_W).fillMaxHeight()) {
                    if (controller != null) {
                        // One row for the choice. Left and right only move what it shows; A on it asks
                        // to switch, and Start confirms, since a switch remaps every button, A and B
                        // included. Asking for the one already in force just says so.
                        val layouts = PadLayout.entries
                        val step = { by: Int -> choice = layouts[(layouts.indexOf(choice) + by + layouts.size) % layouts.size] }
                        MenuRow(
                            // One line, no subtitle: the sidebar is exactly full, and a second line
                            // here squashed its last tab to nothing. The status line says "press A".
                            title = "Layout",
                            minHeight = 40.dp,
                            onClick = {
                                if (choice == state.layout) banner = BannerMessage("You're already on the ${layoutName(choice)} layout.", done = false)
                                else askLayout = choice
                            },
                            onLeft = { step(-1) },
                            onRight = { step(1) },
                            // The top of the page: up from here has nowhere to go, and without this
                            // it took the focus off every row, leaving nothing highlighted.
                            modifier = Modifier.focusProperties { up = FocusRequester.Cancel },
                        ) { RowValue("‹  ${layoutName(choice)}  ›", accent = true) }
                        Spacer(Modifier.height(8.dp))
                    }
                    val groups = SlotGroup.entries.filter { g -> state.slots.any { groupOf(it.icon) == g } }
                    groups.forEachIndexed { i, g ->
                        GroupTab(
                            g.label, selected = g == group, onSelect = { group = g },
                            Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                .focusProperties { if (i == 0 && controller == null) up = FocusRequester.Cancel }
                                .focusRequester(tabFocus.getValue(g)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    MenuRow(
                        title = "Reset to default",
                        minHeight = 40.dp,
                        onClick = {
                            onClearAll()
                            banner = BannerMessage("Reset: every button is back to its default.")
                        },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    )
                }
                Spacer(Modifier.width(20.dp))
                // The chosen group's slots, one per line; the largest group (a stick) has five.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = state.slots.withIndex().filter { groupOf(it.value.icon) == group }
                    shown.forEachIndexed { i, (index, slot) ->
                        SlotRow(
                            slot = slot,
                            label = slotLabel(slot),
                            // An unbound Back is still Select + Start while that setting is on.
                            unbound = if (slot.icon == SlotIcon.BACK && state.selectStartBack) "Select + Start" else null,
                            source = state.bindings[slot.target],
                            chord = state.chords[slot.target],
                            axisLabels = state.sourceAxisLabels,
                            onBind = {
                                focusedRow = index
                                onBind(slot)
                            },
                            onClear = {
                                focusedRow = index
                                onClear(slot)
                                restoreTo = index
                            },
                            modifier = Modifier
                                // Left goes back to this group's own entry, not whichever sidebar row
                                // happens to sit level with this one.
                                // and the list's ends are walls, like the sidebar's.
                                .focusProperties {
                                    left = tabFocus.getValue(group)
                                    if (i == 0) up = FocusRequester.Cancel
                                    if (i == shown.lastIndex) down = FocusRequester.Cancel
                                }
                                .focusRequester(rowFocus[index])
                                .onFocusChanged { if (it.isFocused) focusedSlot = index },
                        )
                    }
                }
            }

            // The hints take the width they need and the status wraps in what is left: a fixed share
            // for the hints cut the last one ("Done") off once there were four.
            Row(
                modifier = Modifier.fillMaxWidth().background(Palette.band),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.notice
                        ?: if (choice != state.layout) "Press A to switch to the ${layoutName(choice)} layout."
                        else if (state.boundCount == 0) "Nothing bound yet." else "${state.boundCount} bound, saved when you leave.",
                    color = Palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp).weight(1f),
                )
                HintBar(
                    hints = listOfNotNull(
                        HintAction(SlotIcon.A, "Rebind", null),
                        HintAction(SlotIcon.X, "Clear", null),
                        controller?.let { HintAction(SlotIcon.Y, "Guided", it::mapAll) },
                        HintAction(SlotIcon.B, "Done", onClose),
                    ),
                )
            }
        }

        Banner(banner, onDone = { banner = null }, Modifier.padding(top = 64.dp))

        askLayout?.let { target ->
            LayoutConfirm(
                target = target,
                hasCustom = state.hasCustom,
                onConfirm = {
                    askLayout = null
                    controller?.switchLayout(target)
                },
                onCancel = { askLayout = null },
            )
        }
        // Faded in and out, and kept on screen while it fades, so it never blinks.
        val lastSwitch = remember { arrayOf(PadLayout.XBOX) }
        state.switchingTo?.let { lastSwitch[0] = it }
        AnimatedVisibility(visible = state.switchingTo != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
            SwitchingLayout(layoutName(lastSwitch[0]))
        }

        if (state.capturing != null) {
            CaptureOverlay(
                slot = state.capturing,
                source = state.bindings[state.capturing.target],
                chord = state.chords[state.capturing.target],
                pending = state.pending,
                axisLabels = state.sourceAxisLabels,
                notice = state.notice,
                onCancel = onCancelBind,
                // "3 of 26": the walk's place, counted from one.
                // Back is left out of the walk, so out of the count.
                progress = state.walk?.let { "${it + 1} of ${state.slots.count { s -> s.icon != SlotIcon.BACK }}" },
                onStop = controller?.let { it::stopWalk }.takeIf { state.walk != null },
            )
        }
    }
}

private val SIDEBAR_W = 230.dp
private const val LOADING_DELAY_MS = 250L

internal fun layoutName(layout: PadLayout) = when (layout) {
    PadLayout.XBOX -> "Xbox"
    PadLayout.NINTENDO -> "Nintendo"
    PadLayout.CUSTOM -> "Custom"
}

/**
 * A slot's name in the list. Select and Start come named so from the slots themselves; Home and Back
 * spell out that they are the handheld's own actions, not buttons on the pad.
 */
private fun slotLabel(slot: MappingSlot): String = when (slot.icon) {
    SlotIcon.HOME -> "Home (go to home screen)"
    SlotIcon.BACK -> "Back (Android back)"
    else -> slot.label
}

/** The tabs the slots are split into, so every group fits the screen without scrolling. */
private enum class SlotGroup(val label: String) {
    FACE("Face"), SHOULDERS("Shoulders"), DPAD("D-pad"), LEFT_STICK("Left stick"), RIGHT_STICK("Right stick"), OTHER("Select, Start, Home"),
}

private fun groupOf(icon: SlotIcon): SlotGroup = when (icon) {
    SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y -> SlotGroup.FACE
    SlotIcon.LEFT_BUMPER, SlotIcon.RIGHT_BUMPER, SlotIcon.LEFT_TRIGGER, SlotIcon.RIGHT_TRIGGER -> SlotGroup.SHOULDERS
    SlotIcon.DPAD_UP, SlotIcon.DPAD_DOWN, SlotIcon.DPAD_LEFT, SlotIcon.DPAD_RIGHT -> SlotGroup.DPAD
    SlotIcon.LEFT_STICK_UP, SlotIcon.LEFT_STICK_DOWN, SlotIcon.LEFT_STICK_LEFT, SlotIcon.LEFT_STICK_RIGHT,
    SlotIcon.LEFT_STICK_CLICK -> SlotGroup.LEFT_STICK
    SlotIcon.RIGHT_STICK_UP, SlotIcon.RIGHT_STICK_DOWN, SlotIcon.RIGHT_STICK_LEFT, SlotIcon.RIGHT_STICK_RIGHT,
    SlotIcon.RIGHT_STICK_CLICK -> SlotGroup.RIGHT_STICK
    SlotIcon.VIEW, SlotIcon.MENU, SlotIcon.HOME, SlotIcon.BACK, SlotIcon.CAPTURE -> SlotGroup.OTHER
}
