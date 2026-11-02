package com.controlswitch.ui

import com.controlswitch.domain.model.SlotIcon
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay

/**
 * Change Input Order: "Press L + R on the controller you'd like to use." Slot 1 stays empty until a
 * pad does; that pad is staged ([pendingName]), and A on any pad saves it as player 1 — the one the
 * mirror replays as the built-in controller whenever it is connected. B leaves without saving.
 *
 * The supervisor holds the mirror off while this is open, so every pad reports as itself and its A
 * reaches this screen through normal key dispatch.
 */
@Composable
fun Player1Screen(
    externals: List<ControllerDevice>,
    player1Name: String?,
    player1Path: String?,
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

    val pending = remember(externals, pendingName, pendingPath) { externals.lockedPlayer1(pendingName, pendingPath) }
    val locked = remember(externals, player1Name, player1Path) { externals.lockedPlayer1(player1Name, player1Path) }
    // The slot shows the pad just picked, else the saved player 1 (named even while it is away).
    val shownPad = if (pendingName != null) pending else locked
    val shownName = pendingName ?: player1Name
    val others = externals.filter { it.path != shownPad?.path }

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
                if (it.type == KeyEventType.KeyUp && pendingName != null) onConfirm()
                true
            }
            .focusable(),
    ) {
        Header(title = "Change Input Order")
        Rule(Modifier.padding(horizontal = 28.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 56.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Once a pad is picked the instruction becomes the confirm; L + R still re-picks.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pendingName != null) {
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
            Spacer(Modifier.height(16.dp))
            PlayerOneSlot(shownName, shownPad, levels)
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    others.forEach { OtherPad(it, levels) }
                }
            }
        }

        HintBar(
            hints = listOfNotNull(
                HintAction(SlotIcon.B, "Back", onClose),
                if (pendingName != null) HintAction(SlotIcon.A, "OK", onConfirm) else null,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

    }
}

/**
 * The Player 1 slot: empty ("Press L + R") until a pad is picked or saved. A newly picked pad
 * scales and fades in. A saved player 1 that is away keeps its name, marked not connected.
 */
@Composable
private fun PlayerOneSlot(name: String?, pad: ControllerDevice?, levels: Map<String, PadBattery>) {
    val connected = pad != null
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(width = 300.dp, height = 165.dp)
            .clip(shape)
            .background(if (name != null) Palette.surfaceRaised else Palette.screen)
            .let { if (name != null) it.focusOutline(true, shape) else it.border(1.dp, Palette.borderStrong, shape) }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = name,
            transitionSpec = {
                (scaleIn(tween(300), initialScale = 0.6f) + fadeIn(tween(300))) togetherWith fadeOut(tween(150))
            },
            label = "player1",
        ) { shown ->
            if (shown == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Press  ", color = Palette.textMuted, fontSize = 17.sp)
                    PadIcon(SlotIcon.LEFT_BUMPER, 28.dp)
                    Text("  +  ", color = Palette.textMuted, fontSize = 17.sp)
                    PadIcon(SlotIcon.RIGHT_BUMPER, 28.dp)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayerLamps(player = if (connected) 1 else 0)
                    Image(
                        painterResource(R.drawable.ic_controller),
                        contentDescription = null,
                        colorFilter = iconTint(),
                        modifier = Modifier.size(width = 64.dp, height = 49.dp).alpha(if (connected) 1f else 0.4f),
                    )
                    if (connected) PadBatteryIndicator(pad, levels)
                    Text(shown, color = Palette.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!connected) Text("Not connected", color = Palette.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Another connected pad, small: just so you can see what is plugged in. */
@Composable
private fun OtherPad(device: ControllerDevice, levels: Map<String, PadBattery>) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painterResource(R.drawable.ic_controller), contentDescription = null, colorFilter = iconTint(), modifier = Modifier.size(width = 30.dp, height = 23.dp))
        Text(device.name, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        PadBatteryIndicator(device, levels)
    }
}

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
