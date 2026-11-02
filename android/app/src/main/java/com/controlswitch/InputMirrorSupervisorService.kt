package com.controlswitch

import com.controlswitch.domain.model.HomeButtonMode
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.hardware.display.DisplayManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.controlswitch.data.InputMirrorGraph
import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.ControllerMapping
import com.controlswitch.domain.model.MirrorSettings
import com.controlswitch.domain.model.MirrorStartRequest
import com.controlswitch.domain.model.MirrorStatus
import com.controlswitch.domain.model.PadBattery
import com.controlswitch.domain.usecase.AutoMirrorDecision

class InputMirrorSupervisorService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private lateinit var graph: InputMirrorGraph
    private lateinit var motion: ThorMotionServer
    private lateinit var notificationManager: NotificationManager
    private var supervisorState = SupervisorState.WaitingForDock
    // Pads warned about running low, by name, until they charge or leave (see newlyLow).
    private val lowWarned = mutableSetOf<String>()
    private val displayWake = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = MirrorStateStore.wake()
        override fun onDisplayRemoved(displayId: Int) = MirrorStateStore.wake()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        graph = InputMirrorGraph.of(this, forceDockMode = BuildConfig.FORCE_DOCK_MODE_FOR_DEV)
        notificationManager = getSystemService(NotificationManager::class.java)
        motion = ThorMotionServer(this)
        ensureNotificationChannel()
        // Guarded because this is a hard crash otherwise: a foreground start the framework refuses
        // (ForegroundServiceStartNotAllowedException) throws right here, and the runCatching around
        // startForegroundService in MainApplication is on the caller's side of the binder — it never
        // sees this. Taking the process down over a notification we could not post is the worse
        // outcome of the two, since everything the supervisor does still works without it.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(supervisorState)) }
            .onFailure { Log.w(TAG, "Could not enter the foreground", it) }
        // Docking adds a display: tick now rather than at the end of an 8s idle sleep.
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayWake, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = Thread({ superviseMirror() }, "input-mirror-supervisor").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayWake)
        running = false
        motion.serve(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun superviseMirror() {
        try {
            superviseUntilStopped()
        } finally {
            // However the worker leaves — the unsupported return below, an interrupt, an unexpected
            // throw — the flag must stop claiming there is a supervisor behind it. Left true, the
            // service would sit in the foreground forever with onStartCommand refusing to start a
            // replacement because it believed one was already running.
            running = false
        }
    }

    // Asked a few times before giving up: at boot the firmware's service can come up after us, and
    // a single failed probe used to stand the supervisor down until the app was next opened. A
    // device that truly lacks it (the Odin 2 Mini) pays a minute of notification for this.
    private fun awaitSupportedDevice(): Boolean {
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (graph.isSupportedDevice) return true
            if (attempt < PROBE_ATTEMPTS - 1) {
                try {
                    Thread.sleep(PROBE_RETRY_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    private fun superviseUntilStopped() {
        // Without the PServerBinder service there is nothing to drive. Established HERE, on the
        // worker, because the probe blocks on a binder transact — the startup paths used to ask on
        // the main thread before starting us at all.
        if (!awaitSupportedDevice()) {
            // Stand down completely rather than idling. A stopped worker behind a live foreground
            // service would pin an ongoing notification the user cannot swipe away, saying only that
            // their device will never work. The screen surfaces that verdict on its own.
            Log.i(TAG, "PServerBinder is unavailable; the supervisor has nothing to drive")
            stopSelf()
            return
        }

        // A root daemon outlives the app, so after an update the previous binary keeps running and
        // a matching daemon is never restarted. Extracting the shipped one says whether it changed;
        // if it did, whatever is running is stale. Here rather than on MY_PACKAGE_REPLACED, which a
        // force-stopped app never receives.
        if (runCatching { graph.processRepository.refreshBinary() }.getOrDefault(false) &&
            runCatching { graph.processRepository.isRunning() }.getOrDefault(false)
        ) {
            runCatching { graph.stopMirror() }
        }

        var nextRestartAllowedAt = 0L
        var nextStartRetryAllowedAt = 0L
        var nextBatteryCheckAt = 0L
        var sleepMs = SUPERVISOR_INTERVAL_MS

        while (running) {
            try {
                val now = System.currentTimeMillis()
                val settings = graph.settingsRepository.getSettings()
                val dockActive = graph.dockStateRepository.isDockActive()
                val mirrorAllowed = dockActive || settings.mirrorWhenHandheld
                val mirrorRunning = graph.processRepository.isRunning()
                // The Thor's gyro over DSU while the mirror is off; docked, the daemon serves the
                // controller's on the same port instead.
                motion.serve(!mirrorRunning)
                // Enumerate controllers only when the result is actually consumed: docked (the
                // auto-mirror decision below reads the list) or the UI is on-screen (it renders the
                // list for setup). Undocked with the UI hidden, this is the one privileged PServer
                // call per tick that nobody reads — skip it so a pocketed handheld isn't spawning a
                // subprocess every idle tick. "Allow mirroring: Always" needs the list undocked
                // too, but only once the framework sees a pad pair (or a mirror is already up, which
                // hides its source from the framework); a pocketed handheld with no pad never pays.
                val allDevices = if (
                    dockActive || MirrorStateStore.uiVisible ||
                    (settings.mirrorWhenHandheld && (mirrorRunning || graph.inputDeviceRepository.controllerPairPresent()))
                ) {
                    graph.inputDeviceRepository.getConnectedControllers()
                } else {
                    emptyList()
                }
                // While nothing is running, heal any node a crashed daemon left hidden so a stuck,
                // invisible external controller can recover on its own (no-op unless there's a
                // hidden-state record to restore).
                if (!mirrorRunning) {
                    runCatching { graph.processRepository.healOrphanedHideNodes() }
                }
                publishSnapshot(settings, dockActive, allDevices, mirrorRunning)

                // A pad about to die mid-game gets one heads-up per low spell. Only while mirroring:
                // the screen shows the charge itself whenever it is open. A root shell read, so
                // sparingly, and never allowed to fail the tick.
                if (mirrorRunning && now >= nextBatteryCheckAt) {
                    nextBatteryCheckAt = now + BATTERY_CHECK_MS
                    val levels = runCatching { graph.readPadBatteries() }.getOrDefault(emptyMap())
                    for (name in newlyLow(levels, lowWarned)) notifyLowBattery(name)
                }

                if (!settings.autoMirrorEnabled) {
                    if (mirrorRunning || settings.expectedRunning) {
                        runCatching { graph.stopMirror() }
                    }
                    updateSupervisorState(SupervisorState.Disabled)
                    sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    MirrorStateStore.awaitNextTick(sleepMs)
                    continue
                }
                if (MirrorStateStore.pickingPlayer1 || MirrorStateStore.testingPad) {
                    if (mirrorRunning) {
                        runCatching { graph.stopMirror() }
                    }
                    updateSupervisorState(
                        if (MirrorStateStore.pickingPlayer1) SupervisorState.PickingPlayer1 else SupervisorState.TestingPad,
                    )
                    MirrorStateStore.awaitNextTick(SUPERVISOR_INTERVAL_MS)
                    continue
                }
                val devices = if (mirrorAllowed) allDevices else emptyList()
                val decision = graph.resolveAutoMirrorDecision(
                    dockActive = dockActive,
                    devices = devices,
                    settings = settings,
                    mirrorRunning = mirrorRunning,
                    restartAllowed = now >= nextRestartAllowedAt && now >= nextStartRetryAllowedAt,
                    appliedGeneration = if (mirrorRunning) {
                        graph.processRepository.appliedConfigGeneration()
                    } else {
                        null
                    },
                )

                when (decision) {
                    AutoMirrorDecision.StopForDock -> {
                        if (mirrorRunning || settings.expectedRunning) {
                            runCatching { graph.stopMirror() }
                        }
                        updateSupervisorState(SupervisorState.WaitingForDock)
                        sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForInternalController -> {
                        updateSupervisorState(SupervisorState.WaitingForInternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForExternalController -> {
                        updateSupervisorState(SupervisorState.WaitingForExternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForRestartThrottle -> {
                        if (supervisorState != SupervisorState.MirrorStartFailed) {
                            updateSupervisorState(SupervisorState.Restarting)
                        }
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    AutoMirrorDecision.Running -> {
                        updateSupervisorState(SupervisorState.Active)
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    AutoMirrorDecision.ApplyLiveSettings -> {
                        val delivered = runCatching {
                            graph.processRepository.applyLiveSettings(
                                settings,
                                mappingFor(devices, settings.source),
                            )
                        }.getOrDefault(false)
                        if (!delivered) {
                            // The daemon is alive but deaf on its control channel. Stopping it hands
                            // the next tick a clean start carrying the new options — the restart
                            // path is still here, it is just the fallback now rather than the
                            // mechanism.
                            Log.w(TAG, "Could not deliver settings to the daemon; restarting it")
                            runCatching { graph.stopMirror() }
                        }
                        updateSupervisorState(SupervisorState.Active)
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    is AutoMirrorDecision.Start -> {
                        val request = decision.request.copy(mapping = mappingFor(devices, decision.request.source))
                        updateSupervisorState(SupervisorState.Starting)
                        if (startMirror(request)) {
                            updateSupervisorState(SupervisorState.Active)
                        } else {
                            nextStartRetryAllowedAt = now + START_RETRY_BACKOFF_MS
                        }
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    is AutoMirrorDecision.Restart -> {
                        val request = decision.request.copy(mapping = mappingFor(devices, decision.request.source))
                        updateSupervisorState(SupervisorState.Restarting)
                        runCatching { graph.stopMirror() }
                        if (startMirror(request)) {
                            updateSupervisorState(SupervisorState.Active)
                        } else {
                            nextStartRetryAllowedAt = now + START_RETRY_BACKOFF_MS
                        }
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                }
            } catch (interrupted: InterruptedException) {
                // onDestroy interrupted us mid-tick (e.g. during shell IO); exit without publishing
                // a stale Error notification for a service that is already gone.
                break
            } catch (failure: Exception) {
                // Keep the supervisor alive; transient root/device failures are expected during reconnects.
                Log.w(TAG, "Supervisor tick failed", failure)
                updateSupervisorState(SupervisorState.Error)
                sleepMs = SUPERVISOR_ERROR_INTERVAL_MS
            }

            try {
                MirrorStateStore.awaitNextTick(sleepMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun publishSnapshot(
        settings: MirrorSettings,
        dockActive: Boolean,
        devices: List<ControllerDevice>,
        mirrorRunning: Boolean,
    ) {
        MirrorStateStore.publish(
            devices = devices,
            status = MirrorStatus(
                running = mirrorRunning,
                expectedRunning = settings.expectedRunning,
                source = settings.source,
                target = settings.target,
                sourceGuid = settings.sourceGuid,
                targetGuid = settings.targetGuid,
                homeMode = settings.homeMode,
                comboHoldKillApp = settings.comboHoldKillApp,
                virtualMouse = settings.virtualMouse,
                autoMirrorEnabled = settings.autoMirrorEnabled,
                docked = dockActive,
                manualInternalGuid = settings.manualInternalGuid,
            ),
        )
    }

    /**
     * The mapping saved for whichever controller is about to be mirrored.
     *
     * Resolved here, from the device list, because the mapping belongs to the REAL controller and
     * only the device list knows which real device is behind the source path — the source itself is
     * the firmware's twin, whose identity every external pad shares.
     */
    private fun mappingFor(devices: List<ControllerDevice>, sourcePath: String?): ControllerMapping {
        val key = devices.firstOrNull { it.path == sourcePath }?.mappingKey
            ?: return ControllerMapping()
        // A pad the bundled database knows starts life already mapped; a pad the user has touched is
        // left exactly as they saved it.
        graph.seedDefaultMapping(key)
        return graph.mappingRepository.get(key)
    }

    private fun startMirror(request: MirrorStartRequest): Boolean {
        // Hand the DSU port to the daemon, which serves the controller's gyro once it is up.
        motion.serve(false)
        return runCatching {
            graph.startMirror(request)
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Mirror start failed for ${request.source} -> ${request.target}", failure)
            updateSupervisorState(SupervisorState.MirrorStartFailed)
            false
        }
    }

    private fun updateSupervisorState(state: SupervisorState) {
        // An interrupt does not abort a binder transact already in flight, so a tick that was mid-call
        // when onDestroy ran finishes and arrives here afterwards. Posting then re-creates an ongoing
        // notification for a service the framework has already torn down — and ongoing means the user
        // cannot swipe it away.
        if (!running || supervisorState == state) {
            return
        }
        supervisorState = state
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun ensureNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Control Switch",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        // Its own channel, high so it pops over a game; muting it leaves the status one alone.
        notificationManager.createNotificationChannel(
            NotificationChannel(BATTERY_CHANNEL_ID, "Controller battery", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun notifyLowBattery(padName: String) {
        val notification = Notification.Builder(this, BATTERY_CHANNEL_ID)
            .setContentTitle("Controller battery low")
            .setContentText("$padName is almost out of charge.")
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(BATTERY_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: SupervisorState): Notification {
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)

        return builder
            .setContentTitle("Control Switch")
            .setContentText(state.notificationText)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private enum class SupervisorState(val notificationText: String) {
        WaitingForDock("Waiting for external display"),
        Disabled("Automatic mirroring disabled"),
        // Device-neutral wording: support is gated on the PServerBinder service existing, not on a
        // model list, so naming one handheld here would be wrong on any other that qualifies.
        WaitingForInternalController("Waiting for built-in controller"),
        WaitingForExternalController("Waiting for external controller"),
        PickingPlayer1("Press L + R on the controller you want to use"),
        TestingPad("Paused for controller tests"),
        Starting("Starting mirroring"),
        Active("Controller mirroring enabled"),
        Restarting("Restarting mirroring"),
        MirrorStartFailed("Could not start mirroring"),
        Error("Mirroring error"),
    }

    companion object {
        private const val TAG = "InputMirrorSupervisor"
        private const val PROBE_ATTEMPTS = 6
        private const val PROBE_RETRY_MS = 10_000L
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "input_mirror_supervisor"
        // Clear of the status notification (1001) and the UI's "controls switched" (1002).
        private const val BATTERY_NOTIFICATION_ID = 1003
        private const val BATTERY_CHANNEL_ID = "controller_battery"
        private const val BATTERY_CHECK_MS = 60_000L

        private const val SUPERVISOR_INTERVAL_MS = 2500L
        private const val SUPERVISOR_IDLE_INTERVAL_MS = 8000L
        private const val SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS = 6000L
        private const val SUPERVISOR_ERROR_INTERVAL_MS = 15000L
        private const val RESTART_THROTTLE_MS = 10000L
        private const val START_RETRY_BACKOFF_MS = 60000L
    }
}

/**
 * The pads to warn about now: one bar or less and not charging, minus those already warned.
 * [warned] is kept in step: a pad that charged up or left is forgotten, so running low again is a
 * new warning. Switch-protocol pads report four bars; one is where the console itself warns.
 */
internal fun newlyLow(levels: Map<String, PadBattery>, warned: MutableSet<String>): Set<String> {
    val low = levels.filterValues { it.level <= 1 && !it.charging }.keys
    warned.retainAll(low)
    val fresh = low - warned
    warned.addAll(fresh)
    return fresh
}
