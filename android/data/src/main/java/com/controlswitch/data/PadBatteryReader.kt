package com.controlswitch.data

import com.controlswitch.domain.model.PadBattery
import java.io.File

/**
 * Charge of every connected external pad, keyed by name.
 *
 * Android has no number for these on the Thor: the app sees the firmware's twin, which has no
 * battery, and the real pad's node is hidden while mirroring. Two sources instead:
 * - Pads with a HID battery (an Xbox pad over Bluetooth) get a kernel power supply,
 *   `hid-<address>-battery`, named after the pad, with a real percentage once the pad has reported.
 * - The kernel's Nintendo driver registers no power supply, but a Switch-protocol pad (Pro
 *   Controller, 8BitDo in Switch mode, Joy-Cons) sends its charge in byte 2 of every input report,
 *   so the first bytes of one report are read from each /dev/hidraw node.
 * Both are system-only, hence the root shell.
 */
internal fun readPadBatteries(shell: MirrorShell, script: File): Map<String, PadBattery> {
    if (!shell.isAvailable) return emptyMap()
    // Run from a file, as the daemon launch is: PServer garbles a one-liner this involved and the
    // command never runs. One line per hidraw node: "<input name>|<first three bytes in hex>", and
    // one per HID power supply: "PSU|<model name>|<capacity>|<status>". `timeout` because a pad that
    // only reports on change would otherwise block the read.
    script.writeText(
        """
        for p in /sys/class/power_supply/hid-*; do
            [ -e "${'$'}p/model_name" ] || continue
            echo "PSU|${'$'}(cat ${'$'}p/model_name 2>/dev/null)|${'$'}(cat ${'$'}p/capacity 2>/dev/null)|${'$'}(cat ${'$'}p/status 2>/dev/null)"
        done
        for h in /sys/class/hidraw/hidraw*; do
            n=${'$'}(cat ${'$'}h/device/input/input*/name 2>/dev/null | head -n 1)
            [ -n "${'$'}n" ] || continue
            echo "${'$'}n|${'$'}(timeout 1 head -c 3 /dev/${'$'}{h##*/} 2>/dev/null | od -An -tx1)"
        done
        """.trimIndent() + "\n",
    )
    script.setReadable(true, false)
    val out = shell.read("sh ${script.absolutePath}")
    return parsePadBatteries(out)
}

/**
 * Parses [readPadBatteries]' output. A power supply line counts once its capacity is a number (the
 * kernel answers nothing until the pad's first battery report). Of the hidraw lines, only Switch
 * input reports (0x21, 0x30, 0x31) carry the charge: the high nibble of byte 2 is level × 2, plus 1
 * while charging. Anything else is skipped.
 */
internal fun parsePadBatteries(output: String): Map<String, PadBattery> =
    output.lineSequence().mapNotNull { line ->
        if (line.startsWith("PSU|")) return@mapNotNull parsePowerSupply(line)
        val name = line.substringBeforeLast('|', "").trim()
        val bytes = line.substringAfterLast('|').trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }
        if (name.isEmpty() || bytes.size < 3 || bytes[0] !in SWITCH_REPORTS) return@mapNotNull null
        val nibble = bytes[2] shr 4
        name to PadBattery(level = (nibble shr 1).coerceAtMost(4), charging = nibble and 1 == 1)
    }.toMap()

private fun parsePowerSupply(line: String): Pair<String, PadBattery>? {
    val parts = line.split('|')
    val name = parts.getOrNull(1)?.trim().orEmpty()
    val percent = parts.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: return null
    if (name.isEmpty()) return null
    val charging = parts.getOrNull(3)?.trim().equals("Charging", ignoreCase = true)
    return name to PadBattery(level = (percent + 12) / 25, charging = charging, percent = percent)
}

private val SWITCH_REPORTS = setOf(0x21, 0x30, 0x31)
