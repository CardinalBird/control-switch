package com.controlswitch.domain.model

/**
 * The pad locked in with L + R, if it is here. The name is what identifies it: it survives the
 * firmware republishing the pad under its own vendor id, and a reconnect moving it to a new node.
 * Among pads with that name, the node it was picked on wins, which keeps two identical pads apart.
 * The node alone never does: event numbers are recycled, so a different pad plugged in later can
 * land on the picked one and would otherwise take the slot.
 */
fun List<ControllerDevice>.lockedPlayer1(name: String?, path: String?): ControllerDevice? {
    if (name == null) return null
    val named = filter { it.name.equals(name, ignoreCase = true) }
    return named.firstOrNull { path != null && it.path == path } ?: named.firstOrNull()
}

/**
 * The external pad to mirror. With a player 1 locked in, only that pad: an absent lock mirrors
 * nothing, so a stray pad can never take over a slot the user assigned. Without a lock, the first
 * external.
 */
fun List<ControllerDevice>.pickExternal(
    local: ControllerDevice?,
    player1Name: String?,
    player1Path: String? = null,
): ControllerDevice? {
    val externals = filter { !it.isInternal && (local == null || !it.isSamePhysicalControllerAs(local)) }
    if (player1Name != null) return externals.lockedPlayer1(player1Name, player1Path)
    return externals.firstOrNull()
}

/**
 * External pads in the order to show them, each with its player number: the mirrored pad first as
 * player 1, then the rest as 2, 3, 4 in Android's own order. With nothing mirrored the rest still
 * start at 2, since player 1 is then the handheld itself.
 *
 * Numbered here rather than taken from Android, whose numbers count the handheld's built-in pad too:
 * a second pad read as player 3 until the mirror hid one node and Android renumbered the rest. These
 * are the numbers a game sees once mirroring runs, and they do not jump when it starts.
 */
fun List<ControllerDevice>.playerOrder(mirroredPath: String?): List<Pair<ControllerDevice, Int>> {
    val externals = filter { !it.isInternal }
    val mirrored = externals.firstOrNull { mirroredPath != null && it.path == mirroredPath }
    val rest = externals.filter { it !== mirrored }.sortedBy { it.controllerNumber }
    return listOfNotNull(mirrored).map { it to 1 } + rest.mapIndexed { i, pad -> pad to i + 2 }
}

private fun ControllerDevice.isSamePhysicalControllerAs(other: ControllerDevice): Boolean =
    controllerNumber > 0 && controllerNumber == other.controllerNumber

data class ControllerDevice(
    val name: String,
    val path: String,
    val guid: String,
    val controllerNumber: Int,
    val handlers: List<String> = emptyList(),
    // Effective internal flag used to pick the mirror target (signature, manual, or default).
    val isInternal: Boolean = false,
    // True only when matched by a recognised handheld signature (e.g. Odin); such a pick is
    // locked and not user-editable. Default/manual picks set isInternal but not this.
    val isKnownInternal: Boolean = false,
    // The /dev/input node the hide-external feature unlinks to remove THIS external controller from
    // the framework's device list — the Odin quirk (vendor 0x2020) node bearing this controller's
    // name. It may be a separate re-exposed twin OR the very node the mirror reads as source (when
    // the firmware exposes only the re-exposed node in /dev); either way it is the framework-visible
    // one. Null for the internal controller and when no such node exists.
    val hideNodePath: String? = null,
    // Identity of the REAL controller behind this entry, used to key a saved mapping. [guid] cannot
    // do that job for an external pad: the firmware republishes every one of them under its own
    // vendor:product, so they all share a guid. Null when no distinct real device was found — an
    // internal controller, or a pad the firmware did not republish.
    val mappingKey: MappingKey? = null,
)
