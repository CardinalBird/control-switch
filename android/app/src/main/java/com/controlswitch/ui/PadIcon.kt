package com.controlswitch.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.controlswitch.R
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import com.controlswitch.ui.theme.Palette

/** Which console's icons [PadIcon] draws. MirrorScreen provides it from the pad's saved preset. */
internal val LocalPadLayout = compositionLocalOf { PadLayout.NINTENDO }

/**
 * Every button prompt in the app: Kenney Input Prompts 1.5 (CC0), white vectors tinted by the theme.
 * Xbox Series art for the Xbox layout, Nintendo Switch 2 art for the Nintendo one. Kenney draws the
 * plain A/B/X/Y and sticks identically for both, so the Xbox faces use the pack's coloured versions.
 */
@Composable
internal fun PadIcon(icon: SlotIcon, size: Dp = 28.dp, tint: Color = Palette.textPrimary) {
    val layout = LocalPadLayout.current
    Image(
        painterResource(padIconRes(icon, layout)),
        contentDescription = null,
        // Xbox A/B/X/Y keep their green/red/blue/yellow; everything else takes the theme's ink.
        colorFilter = if (layout != PadLayout.NINTENDO && icon in XBOX_COLOURED) null else ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

private val XBOX_COLOURED = setOf(SlotIcon.A, SlotIcon.B, SlotIcon.X, SlotIcon.Y)

@DrawableRes
internal fun padIconRes(icon: SlotIcon, layout: PadLayout): Int = when (layout) {
    PadLayout.XBOX, PadLayout.CUSTOM -> when (icon) {
        SlotIcon.A -> R.drawable.kp_xbox_button_a
        SlotIcon.B -> R.drawable.kp_xbox_button_b
        SlotIcon.X -> R.drawable.kp_xbox_button_x
        SlotIcon.Y -> R.drawable.kp_xbox_button_y
        SlotIcon.LEFT_BUMPER -> R.drawable.kp_xbox_lb
        SlotIcon.RIGHT_BUMPER -> R.drawable.kp_xbox_rb
        SlotIcon.LEFT_TRIGGER -> R.drawable.kp_xbox_lt
        SlotIcon.RIGHT_TRIGGER -> R.drawable.kp_xbox_rt
        SlotIcon.DPAD_UP -> R.drawable.kp_xbox_dpad_up
        SlotIcon.DPAD_DOWN -> R.drawable.kp_xbox_dpad_down
        SlotIcon.DPAD_LEFT -> R.drawable.kp_xbox_dpad_left
        SlotIcon.DPAD_RIGHT -> R.drawable.kp_xbox_dpad_right
        SlotIcon.LEFT_STICK_UP -> R.drawable.kp_xbox_stick_l_up
        SlotIcon.LEFT_STICK_DOWN -> R.drawable.kp_xbox_stick_l_down
        SlotIcon.LEFT_STICK_LEFT -> R.drawable.kp_xbox_stick_l_left
        SlotIcon.LEFT_STICK_RIGHT -> R.drawable.kp_xbox_stick_l_right
        SlotIcon.LEFT_STICK_CLICK -> R.drawable.kp_xbox_stick_l_press
        SlotIcon.RIGHT_STICK_UP -> R.drawable.kp_xbox_stick_r_up
        SlotIcon.RIGHT_STICK_DOWN -> R.drawable.kp_xbox_stick_r_down
        SlotIcon.RIGHT_STICK_LEFT -> R.drawable.kp_xbox_stick_r_left
        SlotIcon.RIGHT_STICK_RIGHT -> R.drawable.kp_xbox_stick_r_right
        SlotIcon.RIGHT_STICK_CLICK -> R.drawable.kp_xbox_stick_r_press
        SlotIcon.VIEW -> R.drawable.kp_xbox_button_view
        SlotIcon.MENU -> R.drawable.kp_xbox_button_menu
        SlotIcon.HOME -> R.drawable.kp_xbox_guide
        SlotIcon.BACK -> R.drawable.kp_xbox_button_back
        // Never shown for an Xbox pad (it has no Capture); the Switch glyph rather than a crash.
        SlotIcon.CAPTURE -> R.drawable.kp_switch_button_capture
    }
    PadLayout.NINTENDO -> when (icon) {
        SlotIcon.A -> R.drawable.kp_switch_button_a
        SlotIcon.B -> R.drawable.kp_switch_button_b
        SlotIcon.X -> R.drawable.kp_switch_button_x
        SlotIcon.Y -> R.drawable.kp_switch_button_y
        SlotIcon.LEFT_BUMPER -> R.drawable.kp_switch_button_l
        SlotIcon.RIGHT_BUMPER -> R.drawable.kp_switch_button_r
        SlotIcon.LEFT_TRIGGER -> R.drawable.kp_switch_button_zl
        SlotIcon.RIGHT_TRIGGER -> R.drawable.kp_switch_button_zr
        SlotIcon.DPAD_UP -> R.drawable.kp_switch_dpad_up
        SlotIcon.DPAD_DOWN -> R.drawable.kp_switch_dpad_down
        SlotIcon.DPAD_LEFT -> R.drawable.kp_switch_dpad_left
        SlotIcon.DPAD_RIGHT -> R.drawable.kp_switch_dpad_right
        SlotIcon.LEFT_STICK_UP -> R.drawable.kp_switch_stick_l_up
        SlotIcon.LEFT_STICK_DOWN -> R.drawable.kp_switch_stick_l_down
        SlotIcon.LEFT_STICK_LEFT -> R.drawable.kp_switch_stick_l_left
        SlotIcon.LEFT_STICK_RIGHT -> R.drawable.kp_switch_stick_l_right
        SlotIcon.LEFT_STICK_CLICK -> R.drawable.kp_switch_stick_l_press
        SlotIcon.RIGHT_STICK_UP -> R.drawable.kp_switch_stick_r_up
        SlotIcon.RIGHT_STICK_DOWN -> R.drawable.kp_switch_stick_r_down
        SlotIcon.RIGHT_STICK_LEFT -> R.drawable.kp_switch_stick_r_left
        SlotIcon.RIGHT_STICK_RIGHT -> R.drawable.kp_switch_stick_r_right
        SlotIcon.RIGHT_STICK_CLICK -> R.drawable.kp_switch_stick_r_press
        SlotIcon.VIEW -> R.drawable.kp_switch_button_minus
        SlotIcon.MENU -> R.drawable.kp_switch_button_plus
        SlotIcon.HOME -> R.drawable.kp_switch_button_home
        // No Switch "back" icon in the pack; Minus is the brief's stand-in.
        SlotIcon.BACK -> R.drawable.kp_switch_button_minus
        SlotIcon.CAPTURE -> R.drawable.kp_switch_button_capture
    }
}
