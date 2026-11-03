package com.controlswitch.ui

import androidx.compose.ui.unit.Dp
import android.view.KeyEvent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import com.controlswitch.domain.model.sourceIcon
import com.controlswitch.domain.model.Chord
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controlswitch.domain.model.ControlRef
import com.controlswitch.domain.model.MappingSlot
import com.controlswitch.domain.model.describeSource
import com.controlswitch.ui.theme.FocusBrush
import com.controlswitch.ui.theme.Palette
import kotlinx.coroutines.delay

// The editor's repeated pieces: one row per slot, the glyph that names it, the overlay that waits
// for a press, and the button style both of them use.

/**
 * One slot: what it is, and what it currently reads.
 *
 * Focusable on purpose. The external controller is busy answering the capture, but the handheld's own
 * pad is not — it is the mirror target, and while this screen is open nothing is being forwarded to
 * it, so its buttons reach the app normally. Without a visible focus ring there is no way to tell
 * where the D-pad is.
 */
@Composable
internal fun SlotRow(
    slot: MappingSlot,
    source: ControlRef?,
    onBind: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    axisLabels: Map<Int, String> = emptyMap(),
    /** The controls held together for this slot, when it is a chord rather than [source]. */
    chord: List<ControlRef>? = null,
    label: String = slot.label,
    /** What the slot does when nothing is bound to it, in place of "Not bound". */
    unbound: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The row itself is NOT focusable: it is a plain container holding two focus targets side by side.
    // Making the whole row clickable is what put Clear inside an already-focusable node, where the
    // D-pad could never reach it — focus stopped at the parent and the child was never a stop of its
    // own.
    // One focus target per row; X clears it (the hint bar says so), replacing a Clear button per row.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Palette.focusBg else Palette.surface)
            .focusOutline(focused, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent {
                // Both halves, even on an unbound row: Android turns an unhandled X press into a
                // select, which opened a capture on the row instead of doing nothing.
                if (it.key != Key.ButtonX) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyUp && (source != null || chord != null)) onClear()
                true
            }
            .clickable(interactionSource = interaction, indication = null) { onBind() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { PadIcon(slot.icon, 30.dp) }
        Spacer(Modifier.width(14.dp))
        Text(label, color = Palette.textPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (chord != null) {
            SourceChips(chord, axisLabels)
        } else {
            Text(
                source?.let { describeSource(it, axisLabels) } ?: unbound ?: "Not bound",
                color = if (source == null) Palette.textMuted else Palette.cyan,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * A group tab. Moving the D-pad onto it shows its group, Switch-style, so no press is needed; a tap
 * does the same.
 */
@Composable
internal fun GroupTab(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Palette.surfaceRaised else Palette.screen)
            .focusOutline(focused, RoundedCornerShape(8.dp))
            .onFocusChanged { if (it.isFocused) onSelect() }
            // Swallowed: Android turns an unhandled X into a select.
            .onPreviewKeyEvent { it.key == Key.ButtonX }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Palette.textPrimary else Palette.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Controls held together, as prompt icons joined by "+"; text for one the icon set has no picture of. */
@Composable
internal fun SourceChips(sources: List<ControlRef>, axisLabels: Map<Int, String>, size: Dp = 26.dp) {
    val layout = LocalPadLayout.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEachIndexed { i, source ->
            if (i > 0) Text("+", color = Palette.textMuted, fontSize = 14.sp)
            val icon = sourceIcon(source, axisLabels, layout)
            if (icon != null) {
                PadIcon(icon, size)
            } else {
                Text(describeSource(source, axisLabels), color = Palette.cyan, fontSize = 14.sp)
            }
        }
    }
}

/**
 * The capture prompt. Sits over the list so the slot it belongs to stays visible behind it.
 *
 * Cancel (Skip and Stop during a walk) is all there is, deliberately. Anything more is a button the
 * user cannot reach on the pad being mapped: the first control they press to navigate towards it is
 * the control that gets captured. Clearing lives on the row, where nothing is listening.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CaptureOverlay(
    slot: MappingSlot,
    source: ControlRef?,
    notice: String?,
    onCancel: () -> Unit,
    /** "3 of 26" while Map all walks the list; null for a single rebind. */
    progress: String? = null,
    /** Ends the walk (X). Present only during one, when Cancel becomes Skip (B). */
    onStop: (() -> Unit)? = null,
    axisLabels: Map<Int, String> = emptyMap(),
    /** The slot's current chord, when it has one instead of [source]. */
    chord: List<ControlRef>? = null,
    /** What the user is holding right now. */
    pending: List<ControlRef> = emptyList(),
) {
    // Focus has to come here, and not just for looks: the handheld's pad is still driving the list
    // behind, so without taking focus a press meant for the overlay would open a different slot.
    val cancelFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(slot) {
        repeat(10) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    // A trap on purpose: focus that wandered to a row behind and pressed A re-bound a different
    // slot, and so did a tap through the scrim.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.scrim)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                // The handheld's own X. The pad being mapped never gets here: the daemon has it
                // grabbed, or the app-side capture swallows it first.
                .onPreviewKeyEvent {
                    if (onStop == null || it.key != Key.ButtonX) return@onPreviewKeyEvent false
                    if (it.type == KeyEventType.KeyUp) onStop()
                    true
                }
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.surface)
                .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text(slot.label, color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Press the button for ${slot.label}, or hold up to ${Chord.MAX_MEMBERS} together, then let go." +
                    (progress?.let { "  ·  $it" } ?: ""),
                color = Palette.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            if (chord != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Currently: ", color = Palette.textMuted, fontSize = 13.sp)
                    SourceChips(chord, axisLabels, size = 22.dp)
                }
            } else {
                Text(
                    source?.let { "Currently: ${describeSource(it, axisLabels)}" } ?: "Currently: not bound",
                    color = Palette.textMuted,
                    fontSize = 13.sp,
                )
            }
            // Live, so the user sees the combination build up before letting go commits it.
            Spacer(Modifier.height(12.dp))
            Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                if (pending.isNotEmpty()) SourceChips(pending, axisLabels, size = 40.dp)
            }

            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Text(notice, color = Palette.danger, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                if (onStop != null) EditorButton("Stop", Palette.surface, onStop)
                EditorButton(if (onStop != null) "Skip" else "Cancel", Palette.surface, onCancel, Modifier.focusRequester(cancelFocus))
            }
        }
    }
}

/**
 * "Switch to the Nintendo layout?" A switch can swap A with B and X with Y, so the confirm is Start
 * (Menu, or + on a Nintendo pad), a button that stays put either way: asking for A meant asking for
 * a button whose meaning was the very thing in question. B or Back cancels. For a first Custom it
 * says what will happen instead: Guided setup, then a layout kept to switch back to.
 *
 * Taken at the activity, ahead of Compose focus, and every other pad button is swallowed while it
 * is up, so an A pressed out of habit lands nowhere rather than on the row behind.
 */
@Composable
internal fun LayoutConfirm(target: PadLayout, hasCustom: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val activity = LocalContext.current.findMainActivity()
    val confirm by rememberUpdatedState(onConfirm)
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(activity) {
        val hook: (KeyEvent) -> Boolean = { event ->
            val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_START -> { if (up) confirm(); true }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { if (up) cancel(); true }
                else -> KeyEvent.isGamepadButton(event.keyCode) ||
                    event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
            }
        }
        activity?.onPadCapture = hook
        // Cleared, not handed back: nothing else holds the hook while this is up, and an overlay
        // that fades out after another has taken over would hand back one that is already gone.
        onDispose { if (activity?.onPadCapture === hook) activity.onPadCapture = null }
    }
    val name = layoutName(target)
    val (title, body) = when {
        target == PadLayout.CUSTOM && hasCustom ->
            "Switch to your custom layout?" to "Your own buttons come back, exactly as you set them up."
        target == PadLayout.CUSTOM ->
            "Set up a custom layout?" to
                "Guided setup asks for each button in turn. Your layout is saved to the app, ready to switch " +
                "back to whenever you like."
        else ->
            "Switch to the $name layout?" to
                "Every button is remapped to match the letters printed on a $name controller, so A and B, " +
                "and X and Y, change places."
    }
    // Fades in rather than snapping over the page. It closes at once: Start has been pressed, and
    // the switching overlay takes over from it.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }
            .background(Palette.scrim).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.surface)
                .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text(title, color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(body, color = Palette.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clickable(onClick = onCancel).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PadIcon(SlotIcon.B, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel", color = Palette.textPrimary, fontSize = 15.sp)
                }
                Row(Modifier.clickable(onClick = onConfirm).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PadIcon(SlotIcon.MENU, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Confirm with Start", color = Palette.textPrimary, fontSize = 15.sp)
                }
            }
        }
    }
}

/**
 * "Switching to the Nintendo layout…", over the page while the new mapping reaches the mirror. A
 * switch used to look done the moment Start was pressed while the pad kept its old buttons for a
 * while; this stays up until the daemon reports the new config. Every pad press is taken meanwhile,
 * so nothing lands on the page behind, B included.
 */
@Composable
internal fun SwitchingLayout(name: String) {
    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity) {
        val swallow: (KeyEvent) -> Boolean = { event ->
            KeyEvent.isGamepadButton(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
        }
        activity?.onPadCapture = swallow
        onDispose { if (activity?.onPadCapture === swallow) activity.onPadCapture = null }
    }
    Box(
        Modifier.fillMaxSize().background(Palette.scrim).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.surface)
                .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Switching to the $name layout…", color = Palette.textPrimary, fontSize = 17.sp)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.width(260.dp), color = Palette.cyan, trackColor = Palette.border)
            Spacer(Modifier.height(14.dp))
            Text("Your controller's buttons will update in a moment.", color = Palette.textSecondary, fontSize = 13.sp)
        }
    }
}

/**
 * A message for [Banner]. A plain class on purpose: equality is identity, so posting the same text
 * twice shows it twice and restarts its timer.
 */
internal class BannerMessage(
    val text: String,
    /**
     * A confirmation (something took effect) wears the green tick and border. A prompt or a note
     * ("Press B again…", "You're already on…") does not: a tick there read as if something had
     * happened when nothing had.
     */
    val done: Boolean = true,
)

/**
 * A short confirmation that drops in at the top of the page and leaves by itself, Switch-style:
 * enough to know an action took without a dialog to dismiss (whose B would be one more B to count).
 * [onDone] clears the caller's message once it has been up for [BANNER_MS].
 */
@Composable
internal fun Banner(message: BannerMessage?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(BANNER_MS)
        onDone()
    }
    // Kept while it fades out, so the text doesn't vanish before the box does. A plain holder, not
    // state: it only changes when [message] does, which recomposes this anyway.
    val last = remember { arrayOf(BannerMessage("")) }
    if (message != null) last[0] = message
    val shown = last[0]
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(tween(180)) { -it } + fadeIn(tween(180)),
            exit = fadeOut(tween(250)),
        ) {
            // A new message over one still showing cross-fades, the pill easing to its new width,
            // rather than the text swapping in one frame.
            AnimatedContent(
                targetState = shown,
                transitionSpec = { fadeIn(tween(160, delayMillis = 60)) togetherWith fadeOut(tween(100)) },
                contentAlignment = Alignment.Center,
                label = "banner",
            ) { msg ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Palette.surfaceRaised)
                        .border(1.dp, if (msg.done) Palette.custom else Palette.borderStrong, RoundedCornerShape(22.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (msg.done) {
                        Text("✓", color = Palette.custom, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(msg.text, color = Palette.textPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}

private const val BANNER_MS = 2600L

@Composable
internal fun EditorButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            // Still at the accessible minimum for a touch target, just no longer oversized.
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Palette.focusBg else background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                brush = if (focused) FocusBrush else SolidColor(Palette.border),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
