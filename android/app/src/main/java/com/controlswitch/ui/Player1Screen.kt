package com.controlswitch.ui

import com.controlswitch.domain.model.SlotIcon
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.controlswitch.ui.theme.FocusBrush
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.controlswitch.R
import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.PadBattery
import com.controlswitch.domain.model.lockedPlayer1
import com.controlswitch.ui.theme.Palette
import com.controlswitch.ui.theme.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Change Controller: "Press L + R on the controller you'd like to use." Like the Switch's, a reset:
 * slot 1 always opens empty, the saved player 1 included, and stays empty until a pad presses L + R.
 * That pad pops in and is staged ([pendingName]); A on any pad saves it as player 1 — the one the
 * mirror replays as the built-in controller whenever it is connected. B leaves without saving.
 *
 * The supervisor holds the mirror off while this is open, so every pad reports as itself and its A
 * reaches this screen through normal key dispatch.
 */
@Composable
fun Player1Screen(
    externals: List<ControllerDevice>,
    pendingName: String?,
    pendingPath: String?,
    levels: Map<String, PadBattery>,
    /** False while this page slides out: Back then belongs to the page arriving. */
    active: Boolean,
    onPick: (InputDevice) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = active, onBack = onClose)

    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity) {
        activity?.onLPlusR = onPick
        onDispose { activity?.onLPlusR = null }
    }
    // Leaving the app mid-pick must not leave the mirror held off until someone comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onClose() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A plays the same thump as moving around the menus: Android's own navigation sound, so it
    // matches and follows the system's sound setting. Heard on A, when the pick is made player 1.
    // L + R stays silent: it only stages the pad.
    val view = LocalView.current

    // Only the pad picked on this visit: the page starts from nothing every time.
    val shownPad = remember(externals, pendingName, pendingPath) { externals.lockedPlayer1(pendingName, pendingPath) }
    val shownName = pendingName
    // In Android's order, so the row doesn't reshuffle as pads come and go.
    val others = externals.filter { it.path != shownPad?.path }.sortedBy { it.controllerNumber }

    // A brightens the slot for a moment and the page closes once that has played: saving should be
    // seen, not just vanish. A second A meanwhile is ignored.
    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    var confirming by remember { mutableStateOf(false) }
    val confirm = confirm@{
        if (pendingName == null || confirming) return@confirm
        confirming = true
        view.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN)
        scope.launch {
            flash.animateTo(1f, tween(FLASH_IN_MS))
            flash.animateTo(0f, tween(FLASH_OUT_MS))
            onConfirm()
            confirming = false
        }
    }

    // Nothing on this screen is focusable, and Compose routes keys to the focused node's ancestors
    // only, so A never arrived. The screen itself is the focus target. Both halves of A are taken
    // and the confirm waits for the release: the A that opened this page lifts after it appears.
    val anchor = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    // Retried: the node is not necessarily placed on the first frame, and without focus here A
    // (confirm) never arrives.
    LaunchedEffect(Unit) {
        repeat(10) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (runCatching { anchor.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(anchor)
            .onPreviewKeyEvent {
                if (it.key != Key.ButtonA) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyUp) confirm()
                true
            }
            .focusable(),
    ) {
        Header(title = "Change Controller")
        Rule(Modifier.padding(horizontal = 28.dp))

        Column(
            // Spacing kept tight: the Thor's screen is 468 dp tall, and with any more the row of other
            // controllers at the bottom was cut off mid-name.
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 56.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // What this page is for, here rather than on the main menu row, which stays one line.
            Text("Change the controller being mirrored for player 1.", color = Palette.textSecondary, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            // Once a pad is picked the instruction becomes the confirm; L + R still re-picks. The old
            // line fades out before the new one fades in, in a box of fixed height: cross-faded at
            // once, two sentences of different widths sat on top of each other mid-way.
            AnimatedContent(
                targetState = pendingName != null,
                modifier = Modifier.height(INSTRUCTION_H),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    fadeIn(tween(INSTRUCTION_IN_MS, delayMillis = INSTRUCTION_OUT_MS)) togetherWith
                        fadeOut(tween(INSTRUCTION_OUT_MS)) using null
                },
                label = "instruction",
            ) { picked ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (picked) {
                        Text("Press  ", color = Palette.textPrimary, fontSize = 19.sp)
                        PadIcon(SlotIcon.A, 34.dp)
                        Text("  when you're ready.", color = Palette.textPrimary, fontSize = 19.sp)
                    } else {
                        Text("Press  ", color = Palette.textPrimary, fontSize = 19.sp)
                        PadIcon(SlotIcon.LEFT_BUMPER, 34.dp)
                        Text("  +  ", color = Palette.textPrimary, fontSize = 19.sp)
                        PadIcon(SlotIcon.RIGHT_BUMPER, 34.dp)
                        Text("  on the controller you'd like to use.", color = Palette.textPrimary, fontSize = 19.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PlayerOneSlot(shownName, shownPad, levels, flash = flash.value)
            Spacer(Modifier.height(10.dp))
            // Always this tall, chips or not. The page is centred vertically, so a row that came and
            // went (the only other pad picked into the slot) jolted everything above it up or down.
            // Up to three beside the slot when four pads are connected: the chips share the row and
            // shorten their names to fit, rather than run off the edge of the screen.
            Row(
                Modifier.fillMaxWidth().height(OTHERS_H),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                others.take(MAX_OTHERS).forEach { OtherPad(it, levels, Modifier.weight(1f, fill = false)) }
            }
        }

        HintBar(
            hints = listOfNotNull(
                HintAction(SlotIcon.B, "Back", onClose),
                if (pendingName != null) HintAction(SlotIcon.A, "OK", confirm) else null,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

    }
}

/**
 * The Player 1 slot: empty ("Press L + R", breathing) until a pad presses L + R, which then pops
 * in from the centre. [flash] (0..1) lightens the slot while A saves.
 */
@Composable
private fun PlayerOneSlot(name: String?, pad: ControllerDevice?, levels: Map<String, PadBattery>, flash: Float) {
    val connected = pad != null
    val shape = RoundedCornerShape(14.dp)
    // The slot's fill and outline ease from empty to filled with the pop, rather than switching in
    // one frame under it: a grey frame becoming a gradient one in a single step was the jolt.
    val filled by animateFloatAsState(if (name != null) 1f else 0f, tween(SLOT_FILL_MS, easing = FastOutSlowInEasing), label = "fill")
    val base = lerp(Palette.screen, Palette.surfaceRaised, filled)
    Box(
        modifier = Modifier
            .size(width = SLOT_W, height = SLOT_H)
            .clip(shape)
            .background(lerp(base, Teal, flash * 0.35f))
            .border(1.dp, Palette.borderStrong.copy(alpha = 1f - filled), shape)
            .drawWithContent {
                drawContent()
                if (filled > 0f) {
                    val inset = 1.5.dp.toPx()
                    drawRoundRect(
                        FocusBrush, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(14.dp.toPx() - inset), alpha = filled, style = Stroke(3.dp.toPx()),
                    )
                }
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // No size transform: the slot is fixed and centres whichever state is showing, so the pad
        // pops straight out of the middle. Animating the content's size instead moved it from a
        // corner, on a diagonal. The spring overshoots a touch, which is the pop.
        AnimatedContent(
            targetState = name,
            contentAlignment = Alignment.Center,
            transitionSpec = {
                val pop = spring<Float>(dampingRatio = POP_DAMPING, stiffness = Spring.StiffnessMediumLow)
                (scaleIn(pop, initialScale = 0.6f) + fadeIn(tween(FADE_MS))) togetherWith
                    fadeOut(tween(EXIT_MS)) using null
            },
            label = "player1",
        ) { shown ->
            if (shown == null) {
                // The empty slot names itself, breathing so the page reads as waiting rather than
                // stuck. "Press L + R" is already the line above it; saying it twice was noise.
                val breath by rememberInfiniteTransition(label = "breath").animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "breath",
                )
                Column(Modifier.alpha(breath), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerLamps(player = 1)
                    Spacer(Modifier.height(8.dp))
                    Text("Player 1", color = Palette.textMuted, fontSize = 19.sp)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlayerLamps(player = 1)
                    Image(
                        painterResource(R.drawable.ic_controller),
                        contentDescription = null,
                        colorFilter = iconTint(),
                        modifier = Modifier.size(64.dp),
                    )
                    // Held open even before the charge is read, so the name below doesn't drop a line
                    // when it arrives a moment after the pop.
                    Box(Modifier.height(BATTERY_H), contentAlignment = Alignment.Center) {
                        if (connected) PadBatteryIndicator(pad, levels)
                    }
                    Text(shown, color = Palette.textPrimary, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val SLOT_W = 300.dp
private val SLOT_H = 165.dp
private const val FLASH_IN_MS = 100
private const val FLASH_OUT_MS = 200
// A touch of overshoot is the pop; more than this read as a wobble.
private const val POP_DAMPING = 0.72f
private const val FADE_MS = 160
private const val EXIT_MS = 120
private const val BREATH_MS = 1100
private const val SLOT_FILL_MS = 260
private val INSTRUCTION_H = 40.dp
private const val INSTRUCTION_OUT_MS = 110
private const val INSTRUCTION_IN_MS = 200
private val OTHERS_H = 48.dp
private val BATTERY_H = 16.dp

/** Another connected pad, small: just so you can see what is plugged in. */
@Composable
private fun OtherPad(device: ControllerDevice, levels: Map<String, PadBattery>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painterResource(R.drawable.ic_controller), contentDescription = null, colorFilter = iconTint(), modifier = Modifier.size(32.dp))
        // Weighted so it is the name that gives way when the row is tight, never the battery.
        Text(
            device.name, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp),
        )
        PadBatteryIndicator(device, levels)
    }
}

/** Four pads is the most a game takes: the slot's one plus three beside it. */
private const val MAX_OTHERS = 3

internal data class HintAction(val icon: SlotIcon, val label: String, val onClick: (() -> Unit)?)

/** The Switch-style hints along the bottom, on the page: which pad button does what. Tappable too. */
@Composable
internal fun HintBar(hints: List<HintAction>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 10.dp),

        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(
                // Touch only: a controller has the real buttons, and a focusable hint pulled the D-pad off the list.
                modifier = Modifier.focusProperties { canFocus = false }
                    .let { m -> hint.onClick?.let { m.clickable(onClick = it) } ?: m }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadIcon(hint.icon, 30.dp)
                Spacer(Modifier.size(8.dp))
                Text(hint.label, color = Palette.textPrimary, fontSize = 15.sp)
            }
        }
    }
}
