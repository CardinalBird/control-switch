package com.controlswitch

import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.MirrorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the mirror snapshot, shared process-wide between the background
 * supervisor and the UI.
 *
 * The supervisor already polls the (privileged) PServer service every tick to drive the mirror;
 * it publishes what it finds here so the UI can simply observe it instead of running its own
 * parallel poll loop. That halves the redundant PServer traffic and keeps both views consistent.
 */
object MirrorStateStore {
    data class Snapshot(
        val devices: List<ControllerDevice>,
        val status: MirrorStatus,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    fun publish(devices: List<ControllerDevice>, status: MirrorStatus) {
        _snapshot.value = Snapshot(devices, status)
    }

    // Whether the UI is currently on-screen. The supervisor skips its one privileged PServer call
    // per tick (the controller enumeration) when undocked AND the UI is hidden, since nobody reads
    // the device list then and the undocked auto-mirror decision ignores it — so a pocketed handheld
    // stops spawning a pserver subprocess every idle tick.
    @Volatile
    var uiVisible: Boolean = false
        private set

    private val tickMonitor = Object()

    /**
     * A wake that arrived while the supervisor was mid-tick, waiting to be consumed.
     *
     * Without it the wake is simply lost: `notifyAll` reaches nobody unless someone is already
     * inside `wait`, and the flip usually lands while the supervisor is off doing its PServer call.
     * The UI then sat on an empty device list for the whole 8s idle interval — the exact delay this
     * monitor exists to avoid. Guarded by [tickMonitor], like the wait itself.
     */
    private var pendingWake = false

    /** Called from the Activity lifecycle. A hidden→visible flip wakes the supervisor so the device
     * list refreshes immediately instead of waiting out the current idle interval. */
    fun setUiVisible(visible: Boolean) {
        synchronized(tickMonitor) {
            val wasVisible = uiVisible
            uiVisible = visible
            if (visible && !wasVisible) {
                pendingWake = true
                tickMonitor.notifyAll()
            }
        }
    }

    /** A controller was added or removed: wake the supervisor to look at the new list. */
    fun controllersChanged() = wake()

    /** Cut the supervisor's sleep short: a setting changed, or a display came or went. */
    fun wake() {
        synchronized(tickMonitor) {
            pendingWake = true
            tickMonitor.notifyAll()
        }
    }

    // True while the Player 1 picker is open. The mirror grabs the external pad and replays it as the
    // built-in one, so while it runs nobody can tell pads apart; the supervisor stands it down until
    // the pick is made.
    @Volatile
    var pickingPlayer1: Boolean = false
        private set

    fun setPickingPlayer1(picking: Boolean) {
        synchronized(tickMonitor) {
            pickingPlayer1 = picking
            pendingWake = true
            tickMonitor.notifyAll()
        }
    }

    // True while Controller Tests is open: the same stand-down, so the pad under test reports as itself.
    @Volatile
    var testingPad: Boolean = false
        private set

    fun setTestingPad(testing: Boolean) {
        synchronized(tickMonitor) {
            testingPad = testing
            pendingWake = true
            tickMonitor.notifyAll()
        }
    }

    /** The supervisor sleeps here between ticks so [setUiVisible] can cut the wait short. Spurious
     * wakeups only cost one early tick, which is harmless. Propagates interrupts for clean shutdown. */
    fun awaitNextTick(maxSleepMs: Long) {
        synchronized(tickMonitor) {
            // Skipped outright when a flip already happened: that wake is owed to us whether or not
            // we were here to hear it.
            if (!pendingWake) {
                tickMonitor.wait(maxSleepMs)
            }
            pendingWake = false
        }
    }
}
