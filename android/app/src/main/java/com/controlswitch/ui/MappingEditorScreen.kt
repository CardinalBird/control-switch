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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
 * The mapping editor, sized to fit the Thor's screen with no scrolling: the presets in one row,
 * the slots split into group tabs, and the chosen group's slots in two columns. "Map all" (Y, or
 * Guided setup) still walks every slot one press at a time.
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
                Text("Button mapping", color = Palette.textPrimary, fontSize = 22.sp)
                Text(
                    if (state.seeded) {
                        // A known pad arrives already mapped from its profile; the editor's job then is
                        // adjustment, and saying so stops "why is everything filled in?" cold.
                        "${state.controllerName} · set from its profile. Pick a button to change it."
                    } else {
                        "${state.controllerName} · unbound buttons work as the controller reports them."
                    },
                    color = Palette.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            Rule(Modifier.padding(horizontal = 28.dp))

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (controller != null) {
                        EditorButton("Guided setup", Palette.surface, controller::mapAll)
                        EditorButton("Xbox layout", Palette.surface, { controller.autoMap(PadLayout.XBOX) })
                        EditorButton("Nintendo layout", Palette.surface, { controller.autoMap(PadLayout.NINTENDO) })
                    }
                    EditorButton("Reset to profile", Palette.surface, onClearAll)
                }
                val groups = SlotGroup.entries.filter { g -> state.slots.any { groupOf(it.icon) == g } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g ->
                        GroupTab(g.label, selected = g == group, onSelect = { group = g }, Modifier.focusRequester(tabFocus.getValue(g)))
                    }
                }
                // Two columns of the chosen group's slots; the largest group (a stick) has five.
                state.slots.withIndex().filter { groupOf(it.value.icon) == group }.chunked(2).forEachIndexed { rowIndex, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (index, slot) ->
                            SlotRow(
                                slot = slot,
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
                                    .weight(1f)
                                    // Up from the top row goes back to this group's own tab, not to
                                    // whichever tab happens to sit above it.
                                    .focusProperties { if (rowIndex == 0) up = tabFocus.getValue(group) }
                                    .focusRequester(rowFocus[index])
                                    .onFocusChanged { if (it.isFocused) focusedSlot = index },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Palette.band),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.notice
                        ?: if (state.boundCount == 0) "Nothing bound yet." else "${state.boundCount} bound — applied when you leave.",
                    color = Palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp).weight(1f),
                )
                Box(Modifier.weight(2f)) {
                    HintBar(
                        hints = listOfNotNull(
                            HintAction(SlotIcon.A, "Rebind", null),
                            HintAction(SlotIcon.X, "Clear", null),
                            controller?.let { HintAction(SlotIcon.Y, "Map all", it::mapAll) },
                            HintAction(SlotIcon.B, "Done", onClose),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
                progress = state.walk?.let { "${it + 1} of ${state.slots.size}" },
                onStop = controller?.let { it::stopWalk }.takeIf { state.walk != null },
            )
        }
    }
}

/** The tabs the slots are split into, so every group fits the screen without scrolling. */
private enum class SlotGroup(val label: String) {
    FACE("Face"), SHOULDERS("Shoulders"), DPAD("D-pad"), LEFT_STICK("Left stick"), RIGHT_STICK("Right stick"), OTHER("Other"),
}

private fun groupOf(icon: SlotIcon): SlotGroup = when (icon) {
    SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y -> SlotGroup.FACE
    SlotIcon.LEFT_BUMPER, SlotIcon.RIGHT_BUMPER, SlotIcon.LEFT_TRIGGER, SlotIcon.RIGHT_TRIGGER -> SlotGroup.SHOULDERS
    SlotIcon.DPAD_UP, SlotIcon.DPAD_DOWN, SlotIcon.DPAD_LEFT, SlotIcon.DPAD_RIGHT -> SlotGroup.DPAD
    SlotIcon.LEFT_STICK_UP, SlotIcon.LEFT_STICK_DOWN, SlotIcon.LEFT_STICK_LEFT, SlotIcon.LEFT_STICK_RIGHT,
    SlotIcon.LEFT_STICK_CLICK -> SlotGroup.LEFT_STICK
    SlotIcon.RIGHT_STICK_UP, SlotIcon.RIGHT_STICK_DOWN, SlotIcon.RIGHT_STICK_LEFT, SlotIcon.RIGHT_STICK_RIGHT,
    SlotIcon.RIGHT_STICK_CLICK -> SlotGroup.RIGHT_STICK
    SlotIcon.VIEW, SlotIcon.MENU, SlotIcon.HOME, SlotIcon.BACK -> SlotGroup.OTHER
}
