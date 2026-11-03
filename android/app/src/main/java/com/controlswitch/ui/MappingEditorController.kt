package com.controlswitch.ui

import com.controlswitch.domain.model.Binding
import com.controlswitch.domain.model.Chord
import com.controlswitch.domain.model.CaptureKind
import com.controlswitch.domain.model.CaptureResult
import com.controlswitch.domain.model.ControlKind
import com.controlswitch.domain.model.ControlRef
import com.controlswitch.domain.model.ControllerMapping
import com.controlswitch.domain.model.MappingKey
import com.controlswitch.domain.model.MappingSlot
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.SlotIcon
import com.controlswitch.domain.model.TargetTraits
import com.controlswitch.domain.model.mappingSlots
import com.controlswitch.domain.model.odinFallbackTraits
import com.controlswitch.domain.model.presetBindings
import com.controlswitch.domain.model.sourceIcon
import com.controlswitch.domain.model.isSelectStartBack
import com.controlswitch.domain.repository.InputDeviceRepository
import com.controlswitch.domain.repository.MappingRepository
import com.controlswitch.domain.repository.MirrorProcessRepository
import com.controlswitch.domain.repository.MirrorSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class MappingEditorUiState(
    val open: Boolean = false,
    val controllerName: String = "",
    val slots: List<MappingSlot> = emptyList(),
    /** What each slot currently reads, keyed by the slot's target. Absent means "forwarded as is". */
    val bindings: Map<ControlRef, ControlRef> = emptyMap(),
    /** Slots driven by several controls held together, keyed by target. A target is in one map at most. */
    val chords: Map<ControlRef, List<ControlRef>> = emptyMap(),
    /** What the user is holding right now during a capture, shown live in the overlay. */
    val pending: List<ControlRef> = emptyList(),
    /** The slot waiting for a press, when the capture overlay is up. */
    val capturing: MappingSlot? = null,
    /** Why the last press was refused, shown in the overlay. */
    val notice: String? = null,
    /** True when what is on screen is an untouched database default rather than the user's work. */
    val seeded: Boolean = false,
    /** Index of [capturing] in [slots] while "Map all" walks the list; null outside a walk. */
    val walk: Int? = null,
    /**
     * True when the daemon is not running, so the app itself listens for the press: with the mirror
     * off the pad is not grabbed and reaches the Activity like any other.
     */
    val listenInApp: Boolean = false,
    /** The handheld's own pad, whose presses drive the editor and must never be captured. */
    val internalPadName: String? = null,
    /** evdev axis code -> Android axis name for the pad being mapped, to name its bound axes. */
    val sourceAxisLabels: Map<Int, String> = emptyMap(),
    /** Which console's button icons to draw: the preset last applied to this pad, Nintendo by default (the Thor's own layout). */
    val layout: PadLayout = PadLayout.NINTENDO,
    /** The layout being switched to, until the mirror is running it; null when no switch is under way. */
    val switchingTo: PadLayout? = null,
    /** Whether this pad has a saved custom layout to switch back to. */
    val hasCustom: Boolean = false,
    /** "Select + Start is Back": what an unbound Back row does, shown there. */
    val selectStartBack: Boolean = true,
    /** True from opening until the pad's mapping and slots are read, which can take a moment. */
    val loading: Boolean = false,
    /** Counts custom setups finished on this visit, so the screen can say when one is saved. */
    val customJustSaved: Int = 0,
) {
    val boundCount: Int get() = bindings.size + chords.size
}

/**
 * Drives the mapping editor.
 *
 * While the mirror runs the controller is grabbed and its node unlinked, so the app never sees its
 * input directly: binding a slot opens a capture on the daemon and polls the append-only log for a
 * result. With the mirror off nobody is listening there, so the screen captures the press in the app
 * instead and hands it to [captured].
 *
 * A list first, with the walk ("Map all") as an option rather than the way in. Most slots on most
 * pads are already right, and forcing the user through all of them to fix two made the common case
 * the expensive one.
 */
class MappingEditorController(
    private val mappings: MappingRepository,
    private val devices: InputDeviceRepository,
    private val process: MirrorProcessRepository,
    private val settings: MirrorSettingsRepository,
    /** Hands a pad the user has never mapped its bundled-database default. Safe to repeat. */
    private val seedDefaultMapping: (MappingKey) -> Unit,
    private val scope: CoroutineScope,
    /** Called once the mapping has been written, so whatever displays it can catch up. */
    private val onMappingChanged: () -> Unit = {},
    /**
     * Has the supervisor act now rather than on its next pass. A bumped generation alone waited for
     * that pass, so a saved mapping reached the pad up to half a minute later.
     */
    private val wakeSupervisor: () -> Unit = {},
) {
    private val _state = MutableStateFlow(MappingEditorUiState())
    val state: StateFlow<MappingEditorUiState> = _state.asStateFlow()

    private var poller: Job? = null

    // One lane for everything that writes the control FIFO. cancelBind/clear used to fire
    // "capture off" on a free IO thread, unordered with the next bind's "capture button": B then A
    // quickly could land the off AFTER the new capture opened, and the overlay waited forever. The
    // Select + Start is Back setting writes here too, so off-then-on can never be saved as off.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fifo = Dispatchers.IO.limitedParallelism(1)
    private var mappingKey: MappingKey? = null

    /** What was saved when the editor opened, to tell a real edit from a look around. */
    private var openedWith: Map<ControlRef, ControlRef> = emptyMap()

    private var openedChords: Map<ControlRef, List<ControlRef>> = emptyMap()

    /** Gathers a capture's presses until everything is let go. Main thread only. */
    private val tracker = ChordTracker(::sameControl)

    /** Two codes the pad reports for one control: the same prompt icon, by the source's own axis names. */
    private fun sameControl(a: ControlRef, b: ControlRef): Boolean {
        if (a == b) return true
        val labels = _state.value.sourceAxisLabels
        val icon = sourceIcon(a, labels) ?: return false
        return icon == sourceIcon(b, labels)
    }

    /** Whether opening threw away bindings aimed at slots that no longer exist. */
    private var purgedOnOpen = false

    /** Whether the mapping on screen came from the database untouched. */
    private var seededOnOpen = false

    /** Whether the mirror was off when the editor opened, so captures listen in the app. */
    private var listenInAppOnOpen = false

    private var layoutOnOpen = PadLayout.NINTENDO

    private var selectStartBackOnOpen = true

    /** What the real external pad declares, for the auto-map presets. Null when unreadable. */
    private var sourceTraits: TargetTraits? = null

    fun open(controllerName: String, key: MappingKey, internalPadName: String? = null) {
        if (_state.value.open) return
        mappingKey = key
        settingUpCustom = false
        // The page opens now, on a loading view: reading the pad and the controller database below
        // takes a moment, and opening only once it was done made A feel ignored.
        openedWith = emptyMap()
        openedChords = emptyMap()
        purgedOnOpen = false
        _state.value = MappingEditorUiState(open = true, loading = true, controllerName = controllerName)
        scope.launch {
            // Opened on what is already saved, not on a blank slate: the list has to show the user
            // what their pad does today before it can be somewhere they change it.
            // Slots come from what the target actually declares, read from the live device; the
            // measured Odin defaults only stand in when there is nothing to inspect. This is what
            // keeps the editor from ever offering a target the kernel would silently drop.
            val (saved, slots) = withContext(Dispatchers.IO) {
                // The supervisor normally seeds before the mirror ever starts; repeating it here
                // covers an editor opened before any start. A no-op for a pad already mapped.
                seedDefaultMapping(key)
                val mapping = mappings.get(key)
                seededOnOpen = runCatching { mappings.isSeeded(key) }.getOrDefault(false)
                layoutOnOpen = runCatching { mappings.layout(key) }.getOrDefault(PadLayout.NINTENDO)
                customSaved = runCatching { mappings.customMapping(key) }.getOrNull()
                selectStartBackOnOpen = runCatching { settings.getSettings().selectStartBack }.getOrDefault(true)
                // Known now rather than after the first capture asks the daemon: the overlay is up
                // before that answer comes back, and a press in the gap would reach the screen.
                listenInAppOnOpen = runCatching { !process.isRunning() }.getOrDefault(false)
                val traits = runCatching { devices.targetTraits() }.getOrNull()
                sourceTraits = runCatching { devices.sourceTraits(key) }.getOrNull()
                mapping to mappingSlots(traits ?: odinFallbackTraits())
            }
            val sourceAxisLabels = withContext(Dispatchers.IO) {
                runCatching {
                    val (vendor, product) = key.value.split(":")
                    axisLabels(vendor.toInt(16), product.toInt(16))
                }.getOrDefault(emptyMap())
            }
            // Left while it loaded: nothing to show, and nothing here may reopen the page.
            if (!_state.value.open || mappingKey != key) return@launch

            // Drop anything aimed at a target no slot claims any more. A stored binding outlives the
            // slot list, so a corrected target leaves the old one behind: invisible in the list, still
            // in the file, and still pushed to a daemon that can do nothing with it.
            val targets = slots.map { it.target }.toSet()
            openedWith = saved.bindings
                .filter { it.target in targets }
                .associate { it.target to it.source }
            // Worth writing out even if the user changes nothing else: a stale binding still MATCHES
            // its source, so it swallows that event on the way past and hands it to a target the
            // kernel discards. Leaving it in the file would keep costing the user the control it
            // names long after the slot it aimed at stopped existing.
            // A saved Select + Start → Back is the "Select + Start is Back" setting's to give now, not
            // the pad's: dropped here, so the list shows the setting and the file stops carrying it.
            openedChords = saved.chords
                .filter { it.target in targets && it.target !in openedWith && !it.isSelectStartBack() }
                .associate { it.target to it.sources }
            purgedOnOpen = openedWith.size != saved.bindings.size || openedChords.size != saved.chords.size

            _state.value = MappingEditorUiState(
                open = true,
                controllerName = controllerName,
                slots = slots,
                bindings = openedWith,
                chords = openedChords,
                seeded = seededOnOpen,
                internalPadName = internalPadName,
                listenInApp = listenInAppOnOpen,
                sourceAxisLabels = sourceAxisLabels,
                layout = layoutOnOpen,
                hasCustom = customSaved != null,
                selectStartBack = selectStartBackOnOpen,
            )
        }
    }

    /** Start listening for the control that should drive this slot. */
    fun bind(slot: MappingSlot) {
        if (!_state.value.open) return
        _state.update {
            val blind = it.listenInApp && it.internalPadName == null
            it.copy(capturing = slot, notice = "Choose the built-in controller first.".takeIf { blind })
        }
        startCapture()
    }

    /** Walk every slot in order, opening the capture for each. */
    fun mapAll() {
        val first = _state.value.slots.firstOrNull() ?: return
        if (!_state.value.open) return
        _state.update { it.copy(walk = 0) }
        bind(first)
    }

    /** End a walk, leaving what it has bound so far. */
    fun stopWalk() {
        _state.update { it.copy(walk = null) }
        cancelBind()
        finishCustomSetup()
    }

    /** Replace the whole mapping with a layout preset. Held until [close], like any other edit. */
    fun autoMap(layout: PadLayout) {
        if (!_state.value.open) return
        stopPolling()
        _state.update {
            // No preset binds Home or Back, so whatever the user put there stays.
            val kept = it.slots.filter { s -> s.icon == SlotIcon.HOME || s.icon == SlotIcon.BACK }.map { s -> s.target }
            it.copy(
                bindings = it.bindings.filterKeys { k -> k in kept } + presetBindings(layout, it.slots, sourceTraits),
                chords = it.chords.filterKeys { k -> k in kept },
                capturing = null, walk = null, notice = null, seeded = false, layout = layout,
            )
        }
        rememberLayout(layout)
        scope.launch(fifo) { process.endCapture() }
    }

    /**
     * The layout is saved at once rather than with the mapping, for the icons. Player 1's layout too:
     * the next pad to take the slot follows it ([followPlayer1Layout]).
     */
    private fun rememberLayout(layout: PadLayout) {
        val key = mappingKey ?: return
        scope.launch(Dispatchers.IO) {
            mappings.setLayout(key, layout)
            mappings.setPlayer1Layout(layout)
        }
    }

    /**
     * Switch layouts and put the result to work at once, rather than on leaving the page as other
     * edits are: a layout is one deliberate change the user expects to feel in their hands straight
     * away. [MappingEditorUiState.switchingTo] stays set until the running mirror reports the new
     * config, or for a few seconds at most, so the screen can say "Switching…" and mean it.
     *
     * Custom brings back this pad's saved custom layout. The first time there is none, so Guided
     * setup starts instead and what it binds becomes the custom layout when the walk ends.
     */
    fun switchLayout(layout: PadLayout) {
        val current = _state.value
        if (!current.open || current.switchingTo != null || current.layout == layout) return
        // Leaving Custom keeps it: edits made on it come back the next time it is picked.
        if (current.layout == PadLayout.CUSTOM) keepCustom(current.bindings, current.chords)
        if (layout != PadLayout.CUSTOM) {
            autoMap(layout)
            commit(switching = layout)
            return
        }
        val saved = customSaved
        stopPolling()
        if (saved == null) {
            _state.update { it.copy(layout = PadLayout.CUSTOM, seeded = false, notice = null, hasCustom = false) }
            rememberLayout(PadLayout.CUSTOM)
            settingUpCustom = true
            mapAll()
            return
        }
        val targets = current.slots.map { it.target }.toSet()
        _state.update {
            it.copy(
                bindings = saved.bindings.filter { b -> b.target in targets }.associate { b -> b.target to b.source },
                chords = saved.chords.filter { c -> c.target in targets }.associate { c -> c.target to c.sources },
                capturing = null, walk = null, notice = null, seeded = false, layout = PadLayout.CUSTOM,
            )
        }
        rememberLayout(PadLayout.CUSTOM)
        scope.launch(fifo) { process.endCapture() }
        commit(switching = PadLayout.CUSTOM)
    }

    /** True from picking Custom for the first time until its Guided setup ends. */
    private var settingUpCustom = false

    /** This pad's saved custom layout, read on open; null until one has been set up. */
    private var customSaved: ControllerMapping? = null

    /** The editor's target-keyed maps as the stored, source-first mapping. */
    private fun toMapping(bindings: Map<ControlRef, ControlRef>, chords: Map<ControlRef, List<ControlRef>>) =
        ControllerMapping(bindings.map { Binding(it.value, it.key) }, chords.map { Chord(it.value, it.key) })

    private fun keepCustom(bindings: Map<ControlRef, ControlRef>, chords: Map<ControlRef, List<ControlRef>>) {
        val key = mappingKey ?: return
        val mapping = toMapping(bindings, chords)
        if (mapping.isEmpty) return
        customSaved = mapping
        _state.update { it.copy(hasCustom = true) }
        scope.launch(Dispatchers.IO) { mappings.saveCustomMapping(key, mapping) }
    }

    /**
     * Save what is on screen and push it to the mirror now. With [switching], the screen shows the
     * switch under way until the running daemon reports the new config (a few seconds at most).
     */
    private fun commit(switching: PadLayout?) {
        val key = mappingKey ?: return
        val now = _state.value
        val bindings = now.bindings
        val chords = now.chords
        // Committed, so leaving the page has nothing left to write for it.
        openedWith = bindings
        openedChords = chords
        purgedOnOpen = false
        if (now.layout == PadLayout.CUSTOM) keepCustom(bindings, chords)
        if (switching != null) _state.update { it.copy(switchingTo = switching) }
        val startedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val generation = withContext(NonCancellable) {
                mappings.save(key, toMapping(bindings, chords))
                mappings.setSeeded(key, false)
                settings.bumpConfigGeneration()
                settings.getSettings().configGeneration
            }
            wakeSupervisor()
            // A mirror that isn't running reads the new mapping when it starts: nothing to wait for.
            if (switching != null && runCatching { process.isRunning() }.getOrDefault(false)) {
                withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                    while ((runCatching { process.appliedConfigGeneration() }.getOrNull() ?: -1) < generation) {
                        delay(SWITCH_POLL_MS)
                    }
                }
            }
            // Up long enough to read: a switch that lands at once otherwise flashed the overlay for
            // a frame or two, which looks like a glitch rather than a confirmation.
            if (switching != null) delay((SWITCH_MIN_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0))
            withContext(Dispatchers.Main) {
                if (switching != null) _state.update { it.copy(switchingTo = null) }
                onMappingChanged()
            }
        }
    }

    /** A first custom setup's walk has ended, finished or stopped: what it bound is the custom layout. */
    private fun finishCustomSetup() {
        if (!settingUpCustom) return
        settingUpCustom = false
        commit(switching = null)
        // keepCustom stores nothing for an empty mapping: no "saved" banner for that.
        if (customSaved != null) _state.update { it.copy(customJustSaved = it.customJustSaved + 1) }
    }

    /**
     * Give the pad that has just become player 1 the layout the user picked for player 1, the same
     * preset [autoMap] applies, saved and pushed at once since no editor is open to do it on close.
     *
     * Without this a layout lived with the controller it was picked on: after Change Controller the
     * new pad came up in whatever it had before, Xbox for a pad never set, while the screen and the
     * user both expected the Nintendo layout they had chosen. Skipped when the pad already has that
     * layout, so a pad's own edits made under it survive; Home and Back are kept, as in [autoMap].
     * Custom is the pad's own custom layout, and a pad without one is left as it is.
     */
    fun followPlayer1Layout(key: MappingKey) {
        if (_state.value.open) return
        scope.launch(Dispatchers.IO) {
            val stored = runCatching { mappings.storedLayout(key) }.getOrNull()
            // Nothing picked for player 1 yet: Nintendo, the Thor's own, but only for a pad still on
            // its profile. One the user set up by hand (Guided setup, or a preset before layouts
            // followed the slot) keeps what it has.
            val untouched = runCatching { mappings.isSeeded(key) || mappings.get(key).isEmpty }.getOrDefault(false)
            val wanted = runCatching { mappings.player1Layout() }.getOrNull()
                ?: PadLayout.NINTENDO.takeIf { stored == null && untouched }
                ?: return@launch
            // Against what was set, not the Nintendo shown for a pad never set: that pad still has its
            // profile's Xbox-style mapping and needs the preset applied.
            if (stored == wanted) return@launch
            // A pad with no mapping yet starts from its profile, like an editor opened on it would.
            seedDefaultMapping(key)
            val mapping = if (wanted == PadLayout.CUSTOM) {
                runCatching { mappings.customMapping(key) }.getOrNull() ?: return@launch
            } else {
                val slots = mappingSlots(runCatching { devices.targetTraits() }.getOrNull() ?: odinFallbackTraits())
                val source = runCatching { devices.sourceTraits(key) }.getOrNull()
                val saved = mappings.get(key)
                val kept = slots.filter { it.icon == SlotIcon.HOME || it.icon == SlotIcon.BACK }.map { it.target }.toSet()
                ControllerMapping(
                    saved.bindings.filter { it.target in kept } +
                        presetBindings(wanted, slots, source).map { (target, from) -> Binding(from, target) },
                    saved.chords.filter { it.target in kept },
                )
            }
            // One unit, as in [close]: a mapping saved but never announced is one the daemon never runs.
            withContext(NonCancellable) {
                mappings.save(key, mapping)
                mappings.setSeeded(key, false)
                mappings.setLayout(key, wanted)
                settings.bumpConfigGeneration()
            }
            wakeSupervisor()
            withContext(Dispatchers.Main) { onMappingChanged() }
        }
    }

    /** A press or release the app heard itself (mirror off). Main thread only. */
    fun captured(source: ControlRef, pressed: Boolean) {
        if (edge(source, pressed) == CaptureVerdict.Next) startCapture()
    }

    /** The key layout of a pad the app hears directly, to name its axes by evdev code. */
    fun axisLabels(vendorId: Int, productId: Int): Map<Int, String> =
        runCatching { devices.axisLabels(vendorId, productId) }.getOrDefault(emptyMap())

    /** Close the overlay without changing anything. During a walk, skip to the next slot instead. */
    fun cancelBind() {
        if (advanceWalk()) {
            startCapture()
            return
        }
        stopPolling()
        scope.launch(fifo) { process.endCapture() }
        _state.update { it.copy(capturing = null, notice = null) }
    }

    /** Back to forwarding whatever the pad reports for this slot. */
    fun clear(slot: MappingSlot) {
        if (!_state.value.open) return
        _state.update {
            it.copy(
                bindings = it.bindings - slot.target, chords = it.chords - slot.target,
                capturing = null, walk = null, notice = null, seeded = false,
            )
        }
        stopPolling()
        scope.launch(fifo) { process.endCapture() }
    }

    /** Throw the whole mapping away. Stays open, so the user can see it emptied and start over. */
    fun clearAll() {
        if (!_state.value.open) return
        stopPolling()
        // No longer "the profile as it came": what is on screen is the user's doing from here on,
        // even though clearing it all is what will hand the profile back on the way out.
        _state.update {
            it.copy(bindings = emptyMap(), chords = emptyMap(), capturing = null, walk = null, notice = null, seeded = false)
        }
        scope.launch(fifo) { process.endCapture() }
    }

    /**
     * Leave, writing the mapping on the way out.
     *
     * Held until here rather than pushed on every binding: a walk through the list is one edit as far
     * as the user is concerned, and pushing each row separately meant a config write and a daemon
     * reload per press — twenty of them to remap a pad, each one a chance to be caught half-applied.
     */
    fun close() {
        // The page lingers for its exit animation, so a second B can land here: the state is then
        // already empty and a second write would wipe what the first one saved.
        if (!_state.value.open) return
        // Still loading: nothing has been shown, so nothing is written. Saving the empty state here
        // would wipe the pad's mapping.
        if (_state.value.loading) {
            _state.value = MappingEditorUiState()
            return
        }
        stopPolling()
        val key = mappingKey
        val bindings = _state.value.bindings
        val chords = _state.value.chords
        // On Custom, what is on screen is the custom layout: kept for switching back to, and a first
        // setup left mid-walk keeps what it bound.
        settingUpCustom = false
        if (_state.value.layout == PadLayout.CUSTOM) keepCustom(bindings, chords)
        // A visit that changed nothing writes nothing: bumping the generation would make the daemon
        // re-read a document identical to the one it is already running.
        val edited = bindings != openedWith || chords != openedChords || purgedOnOpen

        scope.launch(Dispatchers.IO) {
            process.endCapture()
            if (key != null && edited) {
                // Written as ONE indivisible unit. This scope belongs to the screen, and the screen
                // closing is precisely what runs this — so a cancel landing between the save and the
                // bump stored the mapping and told nobody: the badge showed the new mapping while the
                // pad went on using the old one until something unrelated restarted the daemon.
                withContext(NonCancellable) {
                    val mapping = toMapping(bindings, chords)
                    if (mapping.isEmpty) {
                        mappings.clear(key)
                        // Clearing everything means "back to how this should be", and for a pad the
                        // database knows, that is its profile. Restored HERE rather than at the next
                        // mirror start: otherwise the card sits badge-less claiming the pad has no
                        // mapping while it is about to be handed one, and the pad stays unmapped until
                        // something unrelated happens to restart the daemon. A pad the database does not
                        // know seeds nothing and stays genuinely cleared.
                        seedDefaultMapping(key)
                    } else {
                        mappings.save(key, mapping)
                        // Whatever this was before, it is the user's now.
                        mappings.setSeeded(key, false)
                    }
                    // Advancing the generation is what makes the supervisor push the new config on its
                    // next tick — the same path a toggle takes, so the daemon adopts it without a restart.
                    settings.bumpConfigGeneration()
                }
                wakeSupervisor()
            }
            // After the write, not before: the badge is read straight back out of storage.
            withContext(Dispatchers.Main) { onMappingChanged() }
        }
        _state.value = MappingEditorUiState()
    }

    /**
     * Open a capture on the daemon and poll it until something fills the slot.
     *
     * Opening and polling are ONE coroutine deliberately. As two they were unordered, so the poller
     * could read the log — and advance its offset past the end of it — before `clearCaptures` had
     * emptied it, handing the new slot a press left over from the previous one. Sequencing them also
     * makes the offset a local, which is the strongest form of "not shared".
     *
     * Polling rather than a callback because the daemon speaks through a file. 80ms is well under
     * what reads as instant for a button press, and nothing is being forwarded meanwhile anyway.
     */
    private fun startCapture() {
        poller?.cancel()
        tracker.reset()
        _state.update { it.copy(pending = emptyList()) }
        poller = scope.launch(fifo) {
            process.clearCaptures()
            var offset = 0L
            // Chord mode for every slot: it reports presses AND releases, and the slot is filled once
            // everything is let go. One control pressed and released is an ordinary binding; several
            // held together are a chord. A stick direction is half an axis, like a trigger.
            // The daemon listens when it runs. With the mirror off it cannot, but then the pad is not
            // grabbed either and reaches the app, so the screen listens instead.
            val daemon = process.beginCapture(CaptureKind.CHORD)
            withContext(Dispatchers.Main) { _state.update { it.copy(listenInApp = !daemon) } }
            if (!daemon) return@launch
            var lastBegin = System.currentTimeMillis()

            while (isActive && _state.value.capturing != null) {
                // The daemon closes an idle capture after 30s so a dead wizard can't mute the pad
                // for good. Renew it while the overlay is genuinely still waiting; renewing a chord
                // capture keeps what it knows is held.
                if (System.currentTimeMillis() - lastBegin > RENEW_CAPTURE_MS) {
                    process.beginCapture(CaptureKind.CHORD)
                    lastBegin = System.currentTimeMillis()
                }
                val read = process.readCaptures(offset)
                offset = read.offset
                for (result in read.results) {
                    val (source, pressed) = when (result) {
                        is CaptureResult.Button -> ControlRef.button(result.code) to true
                        is CaptureResult.Release -> ControlRef.button(result.code) to false
                        is CaptureResult.AxisButton -> ControlRef.half(result.code, result.sign) to true
                        is CaptureResult.AxisRelease -> ControlRef.half(result.code, result.sign) to false
                        is CaptureResult.Stick -> continue
                    }
                    when (withContext(Dispatchers.Main) { edge(source, pressed) }) {
                        CaptureVerdict.Accepted -> {
                            process.endCapture()
                            return@launch
                        }
                        // Start over on a clean log: what is left in this read belonged to the press
                        // that was just refused, or to the slot the walk has moved on from.
                        CaptureVerdict.Refused, CaptureVerdict.Next -> {
                            withContext(Dispatchers.Main) {
                                tracker.reset()
                                _state.update { it.copy(pending = emptyList()) }
                            }
                            process.clearCaptures()
                            offset = 0L
                            process.beginCapture(CaptureKind.CHORD)
                            break
                        }
                        CaptureVerdict.Ignored -> Unit
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    /** What the capture loop should do next with the press it just handed over. */
    private enum class CaptureVerdict {
        /** It filled the slot; the capture is finished. */
        Accepted,

        /** The control belongs to another slot; reopen and wait for a different press. */
        Refused,

        /** It filled the slot and the walk moved on; reopen for the next one. */
        Next,

        /** Nothing this mode can use; keep waiting. */
        Ignored,
    }

    /**
     * Fold one press into the slot being bound, and say what the loop should do next.
     *
     * Returns a verdict rather than acting: it runs ON the capture coroutine (via the main
     * dispatcher), so cancelling the poller or reopening the capture from in here would be the loop
     * reaching around itself — in the reopen case, resetting the offset of a read still in flight.
     */
    private fun edge(source: ControlRef, pressed: Boolean): CaptureVerdict {
        if (_state.value.capturing == null) return CaptureVerdict.Ignored
        val done = tracker.edge(source, pressed)
        _state.update { it.copy(pending = tracker.gathered) }
        return when {
            done == null -> CaptureVerdict.Ignored
            done.size == 1 -> accept(done.single())
            else -> acceptChord(done)
        }
    }

    private fun acceptChord(sources: List<ControlRef>): CaptureVerdict {
        val slot = _state.value.capturing ?: return CaptureVerdict.Ignored
        val set = sources.toSet()
        // Back on Select + Start is exactly what the setting does: turned on rather than stored twice.
        if (slot.icon == SlotIcon.BACK && Chord(sources, slot.target).isSelectStartBack()) {
            setSelectStartBack(true)
            _state.update {
                it.copy(chords = it.chords - slot.target, bindings = it.bindings - slot.target, capturing = null, notice = null)
            }
            return if (advanceWalk()) CaptureVerdict.Next else CaptureVerdict.Accepted
        }
        // The same combination on two slots would fire both; refused like a single control is.
        val taken = _state.value.chords.entries.firstOrNull { it.value.toSet() == set && it.key != slot.target }
        val walk = _state.value.walk
        val ownerIndex = taken?.let { t -> _state.value.slots.indexOfFirst { it.target == t.key } } ?: -1
        if (taken != null && walk != null && ownerIndex > walk) {
            _state.update { it.copy(chords = it.chords - taken.key) }
        } else if (taken != null) {
            val owner = _state.value.slots.firstOrNull { it.target == taken.key }?.label ?: "another slot"
            _state.update { it.copy(notice = "That combination is already bound to $owner") }
            return CaptureVerdict.Refused
        }
        if (slot.target !in _state.value.chords && _state.value.chords.size >= Chord.MAX_PER_MAPPING) {
            _state.update { it.copy(notice = "Up to ${Chord.MAX_PER_MAPPING} combinations per controller") }
            return CaptureVerdict.Refused
        }
        // Accepted, so the user's own Back replaces Select + Start as Back. Not before: a refused
        // chord binds nothing and must not cost the setting.
        if (slot.icon == SlotIcon.BACK) setSelectStartBack(false)
        _state.update {
            it.copy(
                chords = it.chords + (slot.target to sources), bindings = it.bindings - slot.target,
                capturing = null, notice = null, seeded = false,
            )
        }
        return if (advanceWalk()) CaptureVerdict.Next else CaptureVerdict.Accepted
    }

    private fun accept(source: ControlRef): CaptureVerdict {
        val slot = _state.value.capturing ?: return CaptureVerdict.Ignored

        // Refusing beats stealing. Letting the newer binding win silently is how a user ends up with
        // a control torn off a slot they set earlier with nothing on screen to connect the two — and
        // the daemon would have both bindings fighting over the same event anyway.
        val taken = _state.value.bindings.entries.firstOrNull {
            it.value == source && it.key != slot.target
        }
        // Except mid-walk, from a slot still ahead: the walk is going to ask for that one anyway, and
        // refusing would make swapping two buttons impossible without clearing first.
        val walk = _state.value.walk
        val ownerIndex = taken?.let { t -> _state.value.slots.indexOfFirst { it.target == t.key } } ?: -1
        if (taken != null && walk != null && ownerIndex > walk) {
            _state.update { it.copy(bindings = it.bindings - taken.key) }
        } else if (taken != null) {
            val owner = _state.value.slots.firstOrNull { it.target == taken.key }?.label ?: "another slot"
            _state.update { it.copy(notice = "That control is already bound to $owner") }
            return CaptureVerdict.Refused
        }

        // The user's own Back replaces Select + Start as Back.
        if (slot.icon == SlotIcon.BACK) setSelectStartBack(false)
        _state.update {
            it.copy(
                bindings = it.bindings + (slot.target to source), chords = it.chords - slot.target,
                capturing = null, notice = null, seeded = false,
            )
        }
        return if (advanceWalk()) CaptureVerdict.Next else CaptureVerdict.Accepted
    }

    /**
     * "Select + Start is Back", flipped by what the user binds to Back. Saved at once: it is an app
     * setting, not part of this pad's mapping. On turns off "Select + Start closes app", as the
     * Additional Settings switch does.
     */
    private fun setSelectStartBack(enabled: Boolean) {
        if (_state.value.selectStartBack == enabled) return
        _state.update { it.copy(selectStartBack = enabled) }
        scope.launch(fifo) {
            settings.setSelectStartBack(enabled)
            if (enabled) settings.setComboHoldKillAppEnabled(false)
            wakeSupervisor()
        }
    }

    /**
     * Move a walk on to the next slot, or end it after the last. True while there is a next slot.
     *
     * Back is never walked: it starts unbound and stays so until the user picks it from the list.
     * Walked, it took whatever was pressed next (often Select + Start, held to see what happens) and
     * the handheld's Back then fired from the pad without anyone having chosen that.
     */
    private fun advanceWalk(): Boolean {
        val walk = _state.value.walk ?: return false
        val next = walk + 1
        val slot = _state.value.slots.getOrNull(next)?.takeIf { it.icon != SlotIcon.BACK }
        _state.update { it.copy(walk = slot?.let { next }, capturing = slot, notice = null) }
        if (slot == null) finishCustomSetup()
        return slot != null
    }

    private companion object {
        /** A woken supervisor pushes within a tick; past this the switch is reported done regardless. */
        const val SWITCH_TIMEOUT_MS = 8_000L
        const val SWITCH_POLL_MS = 150L
        const val SWITCH_MIN_MS = 700L
        const val POLL_INTERVAL_MS = 80L
        const val RENEW_CAPTURE_MS = 20_000L
    }
}

/**
 * Gathers one capture's presses: everything held at once, up to [Chord.MAX_MEMBERS], becomes the
 * answer when the last of it is let go. A single press and release is a plain one-control binding.
 * A release of something pressed before the capture began is ignored.
 */
internal class ChordTracker(
    /**
     * Whether two codes are one physical control. The handheld's republished pad reports a trigger
     * pull twice, as its analog axis and as the digital trigger button, and some pads send a D-pad
     * press as both a hat and a key; counted apart, one pull read as "ZR + ZR" and bound a chord.
     */
    private val sameControl: (ControlRef, ControlRef) -> Boolean = { a, b -> a == b },
) {
    private val held = mutableSetOf<ControlRef>()
    private val members = mutableListOf<ControlRef>()

    val gathered: List<ControlRef> get() = members.toList()

    /** The finished set once everything is let go, else null. */
    fun edge(source: ControlRef, pressed: Boolean): List<ControlRef>? {
        if (pressed) {
            // Held either way, so letting go of both halves of a doubled control is what ends it.
            if (!held.add(source)) return null
            val twin = members.indexOfFirst { sameControl(it, source) }
            when {
                // The analog half wins: a trigger bound by its axis stays analog, as presets bind it.
                twin >= 0 -> if (source.kind == ControlKind.HALF_AXIS && members[twin].kind != ControlKind.HALF_AXIS) members[twin] = source
                members.size < Chord.MAX_MEMBERS -> members += source
            }
            return null
        }
        if (!held.remove(source) || held.isNotEmpty() || members.isEmpty()) return null
        return members.toList().also { members.clear() }
    }

    fun reset() {
        held.clear()
        members.clear()
    }
}
