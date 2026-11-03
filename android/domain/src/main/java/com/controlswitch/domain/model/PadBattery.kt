package com.controlswitch.domain.model

/**
 * A pad's charge as read outside Android, which only sees the firmware's twin and so has no battery
 * for any external pad. [level] is 0 (empty) to 4 (full): all a Switch-protocol pad reports, read
 * from its raw reports. [percent] is set when the kernel has a real figure, as it does for pads with
 * a HID battery (an Xbox pad over Bluetooth) once that pad has sent one. An Xbox One S pad sends its
 * charge only when the level changes, so until then there is no figure anywhere on the device and
 * no entry at all.
 */
data class PadBattery(val level: Int, val charging: Boolean, val percent: Int? = null)
