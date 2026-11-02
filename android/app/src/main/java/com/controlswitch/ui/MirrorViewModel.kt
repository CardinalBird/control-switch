package com.controlswitch.ui

import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.AppTheme
import com.controlswitch.domain.model.HomeButtonMode
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.view.InputDevice
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.controlswitch.BuildConfig
import com.controlswitch.InputMirrorSupervisorService
import com.controlswitch.MirrorStateStore
import com.controlswitch.R
import com.controlswitch.data.InputMirrorGraph
import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.MappingKey
import com.controlswitch.domain.model.MirrorSettings
import com.controlswitch.domain.model.MirrorStatus
import com.controlswitch.domain.model.findSavedControllerDevice
import com.controlswitch.domain.model.lockedPlayer1
import com.controlswitch.domain.model.pickExternal
import kotlinx.coroutines.Dispatchers
import com.controlswitch.domain.model.PadBattery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MirrorUiState(
    val devices: List<ControllerDevice> = emptyList(),
    val localDevice: ControllerDevice? = null,
    val externalDevice: ControllerDevice? = null,
    val enabled: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String = "",
    val homeMode: HomeButtonMode = HomeButtonMode.HOME,
    val comboHoldKillApp: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    val restartWaiting: Boolean = false,
    val docked: Boolean = false,
    val manualInternalGuid: String? = null,
    val unsupported: Boolean = false,
    // How many controls the external controller has captured, for the card. Null when it has no
    // identity to key a mapping on.
    val mappedControlCount: Int? = null,
    // The external pad carries an untouched database default (as opposed to the user's own mapping).
    val hasSeededMapping: Boolean = false,
    // The service is published but never answers (Odin 2 Mini). Distinct from absent, because
    // telling that owner their device "doesn't have it" would be flatly wrong.
    val serviceUnresponsive: Boolean = false,
    // Percent, 0 = off: how hard the pad rumbles when a game vibrates the Thor.
    val rumbleStrength: Int = 80,
    // The pad locked in as player 1 with L + R, or null for "first connected".
    val player1Name: String? = null,
    val player1Path: String? = null,
    val pickingPlayer1: Boolean = false,
    // The pad that pressed L + R on the picker, waiting for A. Nothing is saved until then.
    val pendingPlayer1Name: String? = null,
    val pendingPlayer1Path: String? = null,
    // Mirror handheld too, not only when docked.
    val mirrorWhenHandheld: Boolean = false,
    val theme: AppTheme = AppTheme.DARK,
    // Which console's button icons to draw: the preset last applied to the external pad.
    val padLayout: PadLayout = PadLayout.XBOX,
    // Switch-protocol pads' charge by name, read from their raw reports (see refreshPadBatteries).
    val padLevels: Map<String, PadBattery> = emptyMap(),
) {
    // On a recognised handheld (e.g. Odin) the native layer flags the internal controller by
    // hardware signature and locks it. Otherwise the internal defaults to the first detected
    // controller and the user can re-pick it manually.
    val hasKnownInternalProfile: Boolean
        get() = devices.any { it.isKnownInternal }

    /**
     * The supervisor is about to start the mirror, or is starting it: everything it needs is here
     * and the daemon is not up yet. Until it is, the pad's presses reach the app as themselves, and
     * its B is Back, which on the main page closes the app. The screen blocks input while this holds.
     */
    val starting: Boolean
        get() = autoMirrorEnabled && !enabled && !loading && (docked || mirrorWhenHandheld) &&
            devices.any { it.isInternal } &&
            devices.filter { !it.isInternal }.let { externals ->
                if (player1Name == null) externals.isNotEmpty() else externals.lockedPlayer1(player1Name, player1Path) != null
            }
}


class MirrorViewModel(private val appContext: Context) : ViewModel() {
    /**
     * Re-read the Switch-protocol pads' charge. A root shell call per pad report, so the screen asks
     * only while it is visible, every 30 s; one in flight at a time.
     */
    fun refreshPadBatteries() {
        if (batteryJob?.isActive == true || _state.value.devices.none { !it.isInternal }) return
        batteryJob = viewModelScope.launch {
            val levels = withContext(Dispatchers.IO) { graph.readPadBatteries() }
            _state.update { it.copy(padLevels = levels) }
        }
    }
    private var batteryJob: kotlinx.coroutines.Job? = null

    // Literally the same graph the supervisor uses, not a second one configured to match. They have
    // to agree about whether a dock is present — when only one was told to force dock mode the screen
    // flipped to "undocked" the moment anything made it re-read, telling the user the dock had gone
    // while the supervisor happily went on mirroring — and sharing it also means one cached PServer
    // verdict and one seeding cache instead of two that can disagree.
    private val graph = InputMirrorGraph.of(appContext, forceDockMode = BuildConfig.FORCE_DOCK_MODE_FOR_DEV)

    // The theme is read up front (one small prefs file) so a Light user never sees a dark first frame.
    private val _state = MutableStateFlow(MirrorUiState(theme = graph.settingsRepository.getSettings().theme))
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    // Owned rather than injected as a second ViewModel: it lives and dies with this screen, and
    // borrowing this scope is what keeps its polling tied to the same lifecycle.
    val mappingEditor = MappingEditorController(
        mappings = graph.mappingRepository,
        devices = graph.inputDeviceRepository,
        process = graph.processRepository,
        settings = graph.settingsRepository,
        seedDefaultMapping = graph::seedDefaultMapping,
        scope = viewModelScope,
        // Fired once the editor has finished writing, so the card's badge reflects what was just
        // saved instead of waiting for a supervisor snapshot that a mapping edit never triggers.
        onMappingChanged = ::refreshMappingBadges,
    )

    fun openMappingEditor() {
        val device = _state.value.externalDevice ?: return
        val key = device.mappingKey ?: return
        mappingEditor.open(device.name, key, _state.value.localDevice?.name)
    }

    init {
        viewModelScope.launch {
            // Off the main thread: establishing this costs a binder transact the first time, and on
            // a device that publishes PServerBinder without answering it that transact hangs until it
            // fails. It used to run right here, in the ViewModel's constructor, on the UI thread.
            val support = withContext(Dispatchers.IO) {
                graph.isSupportedDevice to graph.isServiceUnresponsive
            }
            if (!support.first) {
                _state.update {
                    it.copy(loading = false, unsupported = true, serviceUnresponsive = support.second)
                }
                return@launch
            }

            val saved = withContext(Dispatchers.IO) { graph.settingsRepository.getSettings() }
            _state.update { it.copy(player1Name = saved.player1Name, player1Path = saved.player1Path) }
            startSupervisor()
            // Cold start: the supervisor may not have published a snapshot yet, so read once
            // directly to fill the screen immediately. Then observe the shared snapshot the
            // supervisor keeps fresh — the UI no longer runs its own PServer poll loop in
            // parallel with the service.
            if (MirrorStateStore.snapshot.value == null) {
                initialLoad()
            }
            MirrorStateStore.snapshot.collect { snapshot ->
                if (snapshot != null) {
                    applyStatus(snapshot.status, snapshot.devices)
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    // Pull a fresh reading immediately on resume so opening the app doesn't wait for the next
    // supervisor tick; steady-state updates arrive through the observed snapshot.
    fun onResume() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun initialLoad() {
        try {
            val (devices, status) = withContext(Dispatchers.IO) {
                graph.getConnectedDevices() to graph.getMirrorStatus()
            }
            applyStatus(status, devices)
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: error.toString()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun refresh() {
        try {
            val (devices, status) = withContext(Dispatchers.IO) {
                graph.getConnectedDevices() to graph.getMirrorStatus()
            }
            applyStatus(status, devices)
        } catch (_: Exception) {
            // Transient root/device failures are expected during reconnects; keep last state.
        }
    }

    private fun applyStatus(status: MirrorStatus, available: List<ControllerDevice>) {
        val saved = graph.settingsRepository.getSettings()
        val autoLocal = available.firstOrNull { it.isInternal }
        val autoExternal = available.pickExternal(autoLocal, _state.value.player1Name, _state.value.player1Path)
        val savedExternalIsLocal =
            autoLocal != null && savedIdentityMatches(status.source, status.sourceGuid, autoLocal)

        val localDevice = autoLocal
            ?: deviceFromSavedIdentity(status.target, status.targetGuid, available, "Saved built-in controller")
        val externalDevice = autoExternal
            ?: if (savedExternalIsLocal) {
                null
            } else {
                deviceFromSavedIdentity(status.source, status.sourceGuid, available, "Waiting for external controller")
            }

        _state.update {
            it.copy(
                devices = available,
                localDevice = localDevice,
                externalDevice = externalDevice,
                enabled = status.running,
                // The options come straight from the stored settings (in memory, so cheap). The
                // snapshot was read on the supervisor's last tick and can predate a toggle the user
                // just made, which briefly flipped the switch back.
                homeMode = saved.homeMode,
                comboHoldKillApp = saved.comboHoldKillApp,
                rumbleStrength = saved.rumbleStrength,
                mirrorWhenHandheld = saved.mirrorWhenHandheld,
                autoMirrorEnabled = status.autoMirrorEnabled,
                restartWaiting = status.expectedRunning && !status.running,
                docked = status.docked,
                manualInternalGuid = status.manualInternalGuid,
                // Database defaults deliberately do not count: the green border and CUSTOM MAPPING
                // badge mean "the user made this", and a pad that merely matched its profile would
                // otherwise wear them out of the box.
                mappedControlCount = externalDevice?.mappingKey?.let(::mappedCountFor),
                hasSeededMapping = externalDevice?.mappingKey?.let(::isSeededFor) ?: false,
                padLayout = externalDevice?.mappingKey?.let(graph.mappingRepository::layout) ?: PadLayout.XBOX,
                message = buildStatusMessage(status, available, saved),
            )
        }
    }

    // Database defaults deliberately do not count as "mapped": the green border and CUSTOM MAPPING
    // badge mean "the user made this", and a pad that merely matched its profile would otherwise
    // wear them out of the box.
    private fun mappedCountFor(key: MappingKey): Int? =
        graph.mappingRepository.get(key).boundControlCount
            .takeIf { it > 0 && !graph.mappingRepository.isSeeded(key) }

    private fun isSeededFor(key: MappingKey): Boolean =
        graph.mappingRepository.isSeeded(key) && !graph.mappingRepository.get(key).isEmpty

    /**
     * Recompute just the badge fields after the editor writes.
     *
     * Needed because the rest of this screen's state arrives on the supervisor's snapshot, and that
     * only re-emits when the mirror or the device list changes — neither of which a mapping edit
     * does. Without this the card keeps whatever badge it had when the last snapshot landed, so a
     * freshly seeded or freshly cleared pad shows the previous answer until something unrelated
     * happens to move the supervisor.
     */
    fun refreshMappingBadges() {
        viewModelScope.launch {
            val key = _state.value.externalDevice?.mappingKey ?: return@launch
            val (count, seeded) = withContext(Dispatchers.IO) {
                mappedCountFor(key) to isSeededFor(key)
            }
            val layout = withContext(Dispatchers.IO) { graph.mappingRepository.layout(key) }
            _state.update { it.copy(mappedControlCount = count, hasSeededMapping = seeded, padLayout = layout) }
        }
    }

    // Option toggles only persist the setting; if the mirror is running with different flags the
    // supervisor notices (started* snapshot vs current settings) and restarts it automatically.
    fun toggleComboHoldKillApp(nextValue: Boolean) {
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(comboHoldKillApp = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setComboHoldKillAppEnabled(nextValue) }
                MirrorStateStore.wake()
            } catch (error: Exception) {
                _state.update { it.copy(comboHoldKillApp = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    fun toggleAutoMirrorEnabled(nextValue: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, autoMirrorEnabled = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    graph.setAutoMirrorEnabled(nextValue)
                    MirrorStateStore.wake()
                    if (!nextValue) {
                        // Best-effort: the setting is already persisted and the supervisor also
                        // stops the mirror, so a stop hiccup (e.g. nothing was running) must not
                        // revert the toggle or surface an error to the user.
                        runCatching { graph.stopMirror() }
                    }
                }
                if (nextValue) startSupervisor()
                _state.update {
                    it.copy(
                        enabled = if (nextValue) it.enabled else false,
                        message = if (nextValue) "Mirroring turned on." else "Mirroring turned off.",
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(autoMirrorEnabled = !nextValue, message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun openPlayer1Picker() {
        MirrorStateStore.setPickingPlayer1(true)
        _state.update { it.copy(pickingPlayer1 = true, pendingPlayer1Name = null, pendingPlayer1Path = null) }
    }

    fun closePlayer1Picker() {
        MirrorStateStore.setPickingPlayer1(false)
        _state.update { it.copy(pickingPlayer1 = false, pendingPlayer1Name = null, pendingPlayer1Path = null) }
    }

    /** A on the picker: the staged pad becomes player 1, and the picker closes. */
    fun confirmPlayer1() {
        val current = _state.value
        val name = current.pendingPlayer1Name ?: return
        val path = current.pendingPlayer1Path
        _state.update { it.copy(player1Name = name, player1Path = path) }
        viewModelScope.launch {
            // Saved BEFORE the picker closes: closing wakes the supervisor, and a tick that read the
            // settings first would start the previous player 1.
            withContext(Dispatchers.IO) { graph.settingsRepository.setPlayer1(name, path) }
            closePlayer1Picker()
            notifyControlsSwitched(name)
        }
    }

    // One-shot and self-dismissing, on its own channel so muting it leaves the supervisor's alone.
    private fun notifyControlsSwitched(name: String) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SWITCHED_CHANNEL_ID, "Controls switched", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(appContext, SWITCHED_CHANNEL_ID)
            .setContentTitle("Controls switched")
            .setContentText("$name is player 1.")
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setAutoCancel(true)
            .setTimeoutAfter(SWITCHED_TIMEOUT_MS)
            .build()
        manager.notify(SWITCHED_NOTIFICATION_ID, notification)
    }

    /**
     * A pad pressed L + R. Matched to its device by Android's player number, which is per pad, so two
     * identical pads stay apart; a pad without a number (0) falls back to its name alone. The built-in
     * pad can't be its own source, so its press is ignored. Only stages the pick: A confirms it.
     */
    fun choosePlayer1(pad: InputDevice) {
        val name = pad.name
        val number = pad.controllerNumber
        fun List<ControllerDevice>.byNumber() = firstOrNull { number > 0 && it.controllerNumber == number }
        viewModelScope.launch {
            // The list on screen can be from before the picker paused the mirror (up to a tick old),
            // when this pad had a different number or wasn't listed at all. Then the lock lost its
            // node and fell back to the name, so look again rather than settle for that.
            val device = _state.value.devices.byNumber()
                ?: runCatching { withContext(Dispatchers.IO) { graph.getConnectedDevices() } }.getOrNull()?.byNumber()
            if (device?.isInternal == true || name.equals(_state.value.localDevice?.name, ignoreCase = true)) return@launch
            // The lookup above can outlive the picker.
            if (!_state.value.pickingPlayer1) return@launch
            _state.update { it.copy(pendingPlayer1Name = device?.name ?: name, pendingPlayer1Path = device?.path) }
        }
    }

    // A ViewModel torn down mid-pick must not leave the supervisor holding the mirror off.
    override fun onCleared() {
        MirrorStateStore.setPickingPlayer1(false)
    }

    fun selectInternalController(guid: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setManualInternalController(guid) }
                MirrorStateStore.wake()
                _state.update { it.copy(manualInternalGuid = guid) }
                refresh()
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }


    fun setRumbleStrength(percent: Int) {
        val next = percent.coerceIn(0, 100)
        _state.update { it.copy(rumbleStrength = next) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.settingsRepository.setRumbleStrength(next) }
            // Reaches a running mirror at once, through the same config reload as the toggles.
            MirrorStateStore.wake()
        }
    }

    fun setTheme(theme: AppTheme) {
        _state.update { it.copy(theme = theme) }
        viewModelScope.launch(Dispatchers.IO) { graph.settingsRepository.setTheme(theme) }
    }

    fun setMirrorWhenHandheld(nextValue: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(mirrorWhenHandheld = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.settingsRepository.setMirrorWhenHandheld(nextValue) }
                MirrorStateStore.wake()
            } catch (error: Exception) {
                _state.update { it.copy(mirrorWhenHandheld = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    // Guarded like MainApplication's: this runs after an IO hop, and if the app went to the
    // background meanwhile the framework refuses a foreground start with an exception that would
    // otherwise take the whole process down. The next time the screen opens, it tries again.
    private fun startSupervisor() {
        runCatching {
            val intent = Intent(appContext, InputMirrorSupervisorService::class.java)
            appContext.startForegroundService(intent)
        }
    }

    private fun buildStatusMessage(status: MirrorStatus, devices: List<ControllerDevice>, saved: MirrorSettings): String = when {
        status.running -> "Controller mirroring enabled."
        !status.autoMirrorEnabled -> "Mirroring is off."
        devices.none { it.isInternal } -> "Waiting for the built-in controller."
        devices.none { !it.isInternal } -> "Waiting for a controller."
        // A locked player 1 that is not here mirrors nothing, whatever else is plugged in.
        saved.player1Name != null && devices.lockedPlayer1(saved.player1Name, saved.player1Path) == null ->
            "Waiting for ${saved.player1Name}."
        status.expectedRunning -> "Controller detected. Mirroring will start automatically."
        !status.docked && !saved.mirrorWhenHandheld -> "Mirroring starts when you dock."
        else -> "Ready to mirror."
    }

    private fun savedIdentityMatches(path: String?, guid: String?, device: ControllerDevice): Boolean =
        (guid != null && device.guid == guid) || (path != null && device.path == path)

    private fun deviceFromSavedIdentity(
        path: String?,
        guid: String?,
        devices: List<ControllerDevice>,
        fallbackName: String,
    ): ControllerDevice? {
        devices.findSavedControllerDevice(path = path, guid = guid)?.let { return it }
        if (path.isNullOrEmpty() && guid.isNullOrEmpty()) return null
        return ControllerDevice(
            name = fallbackName,
            path = path ?: "",
            guid = guid ?: "",
            controllerNumber = 0,
        )
    }

    companion object {
        private const val SWITCHED_CHANNEL_ID = "controls_switched"
        // Clear of the supervisor's 1001.
        private const val SWITCHED_NOTIFICATION_ID = 1002
        private const val SWITCHED_TIMEOUT_MS = 4_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MirrorViewModel(context.applicationContext) as T
        }
    }
}
