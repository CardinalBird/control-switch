package com.controlswitch.ui

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.view.InputDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controlswitch.R
import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.PadBattery
import com.controlswitch.ui.theme.Palette
import com.controlswitch.ui.theme.FocusBrush
import kotlin.math.roundToInt

// The pieces the pages lay out, Switch 2 "Controllers" style: a title with a rule under it, the
// handheld and its pads on the left, plain menu rows on the right, button hints on the page.

/** The page title, and a spinner while the first scan runs. */
@Composable
internal fun Header(title: String = stringResource(R.string.app_name), loading: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Palette.textPrimary, fontSize = 22.sp)
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Palette.cyan, strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.border))
}

/** The focus outline every selectable item wears: thick, rounded, blue to green in every theme. */
internal fun Modifier.focusOutline(focused: Boolean, shape: RoundedCornerShape): Modifier =
    if (focused) border(3.dp, FocusBrush, shape) else this

/**
 * Tint for the white one-colour icons (the Kenney pad, the handheld drawings): the theme's secondary
 * text colour, so they stay visible on Light as well as the dark themes.
 */
@Composable
internal fun iconTint() = ColorFilter.tint(Palette.textSecondary)

/** A battery outline filled to [percent], and the number. */
@Composable
internal fun Battery(percent: Int) = BatteryIcon(percent.coerceIn(0, 100) / 100f, percent <= 15, "$percent%")

/**
 * A battery outline filled to [fill] (0–1), red when [low], with an optional [label] beside it.
 * [charging] adds a bolt, for the pads that only report a level.
 */
@Composable
internal fun BatteryIcon(fill: Float, low: Boolean, label: String?, charging: Boolean = false) {
    val tint = if (low) Palette.danger else Palette.textSecondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 24.dp, height = 12.dp).border(1.5.dp, tint, RoundedCornerShape(3.dp)).padding(2.5.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(tint, RoundedCornerShape(1.dp)))
        }
        Box(Modifier.size(width = 2.dp, height = 5.dp).background(tint))
        if (charging) Text(" ⚡", color = Palette.textSecondary, fontSize = 12.sp)
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = Palette.textSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * A pad's charge: Android's percentage when it has one, else the level a Switch-protocol pad
 * reports in [levels] as a bar with no number (the pad gives four steps, so a % would be made up),
 * else nothing. Never 0%.
 */
@Composable
internal fun PadBatteryIndicator(device: ControllerDevice?, levels: Map<String, PadBattery>) {
    val percent = remember(device) { padBattery(device) }
    val level = device?.let { levels[it.name] }
    when {
        percent != null && percent > 0 -> Battery(percent)
        level != null -> BatteryIcon(level.level / 4f, level.level <= 1, "Low".takeIf { level.level <= 1 && !level.charging }, level.charging)

    }
}

/** The handheld's own charge, or null if the framework won't say. */
internal fun handheldBattery(context: Context): Int? =
    context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }

/**
 * A pad's charge as the framework reports it, matched by player number, else by name. Null below
 * Android 12 and for pads that report no battery, which many Bluetooth pads don't.
 */
internal fun padBattery(device: ControllerDevice?): Int? {
    if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val pads = InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice)
        .filter { it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD }
    val pad = pads.firstOrNull { device.controllerNumber > 0 && it.controllerNumber == device.controllerNumber }
        ?: pads.firstOrNull { it.name.equals(device.name, ignoreCase = true) }
        ?: return null
    val battery = pad.batteryState
    return if (battery.isPresent && !battery.capacity.isNaN()) (battery.capacity * 100).roundToInt() else null
}

/**
 * The handheld, drawn: the Thor, or the Odin when this isn't a Thor. Focusable and tappable only
 * when [onClick] is given (mirror off), which opens the built-in controller picker.
 */
@Composable
internal fun HandheldTile(battery: Int?, hint: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val thor = remember { Build.MODEL.contains("Thor", ignoreCase = true) }
    Column(
        modifier = modifier
            .clip(shape)
            .focusOutline(focused, shape)
            .let { if (onClick != null) it.clickable(interactionSource = interaction, indication = null, onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(if (thor) R.drawable.ic_handheld_thor else R.drawable.ic_handheld_odin),
            contentDescription = null,
            colorFilter = iconTint(),
            modifier = Modifier.height(if (thor) 84.dp else 56.dp),
        )
        Spacer(Modifier.height(6.dp))
        battery?.let { Battery(it) }
        if (hint != null) {
            Text(hint, color = Palette.textMuted, fontSize = 11.sp)
        }
    }
}

/**
 * A connected pad: its player lamp, the pad, its battery and name. The mirrored pad is player 1;
 * any other pad lights the lamp for the player number Android gave it, since games see it as that
 * extra player.
 */
@Composable
internal fun PadTile(device: ControllerDevice, player1: Boolean, levels: Map<String, PadBattery>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerLamps(player = if (player1) 1 else device.controllerNumber)
        Spacer(Modifier.height(6.dp))
        Image(painterResource(R.drawable.ic_controller), contentDescription = null, colorFilter = iconTint(), modifier = Modifier.size(width = 52.dp, height = 40.dp))
        Spacer(Modifier.height(4.dp))
        PadBatteryIndicator(device, levels)
        Text(
            device.name,
            color = Palette.textPrimary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The four player lamps, the [player]th (1–4) green; none lit outside that range. */
@Composable
internal fun PlayerLamps(player: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(4) { lamp ->
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.dp)).background(if (lamp == player - 1) Palette.custom else Palette.borderStrong))
        }
    }
}

/**
 * One menu row: label left, its state right, a rule below. Focus is the gradient outline. [onLeft]
 * and [onRight] let a row take the D-pad sideways (the ‹ value › rows).
 */
@Composable
internal fun MenuRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Palette.textPrimary,
    minHeight: Dp = 64.dp,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(shape)
                .background(if (focused) Palette.focusBg else Palette.screen)
                .focusOutline(focused, shape)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // One step per press: a held D-pad auto-repeats, and spun through every mode.
                    if (it.nativeKeyEvent.repeatCount > 0 && (onLeft != null || onRight != null)) {
                        return@onPreviewKeyEvent it.key == Key.DirectionLeft || it.key == Key.DirectionRight
                    }
                    when {
                        it.key == Key.DirectionLeft && onLeft != null -> { onLeft(); true }
                        it.key == Key.DirectionRight && onRight != null -> { onRight(); true }
                        else -> false
                    }
                }
                .clickable(interactionSource = interaction, indication = null, enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (onClick != null) titleColor else Palette.textMuted, fontSize = 17.sp)
                if (subtitle != null) {
                    Text(subtitle, color = Palette.textMuted, fontSize = 12.sp)
                }
            }
            trailing()
        }
        Rule(Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
internal fun RowValue(text: String, accent: Boolean = false) {
    Text(text, color = if (accent) Palette.cyan else Palette.textSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun RowSwitch(checked: Boolean, enabled: Boolean) {
    // Display only: a Switch with its own handler is a second D-pad focus stop inside the row,
    // with a focus ring that is nearly invisible. The row's click toggles the setting.
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.onAccent,
            checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textMuted,
            uncheckedTrackColor = Palette.surfaceRaised,
        ),
    )
}
