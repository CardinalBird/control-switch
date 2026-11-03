package com.controlswitch.ui

import com.controlswitch.domain.model.HomeButtonMode
import com.controlswitch.domain.model.KEY_HOME
import com.controlswitch.domain.model.KEY_BACK
import com.controlswitch.domain.model.SELECT_START_BACK
import com.controlswitch.domain.model.TargetTraits
import com.controlswitch.domain.model.Binding
import com.controlswitch.domain.model.CaptureKind
import com.controlswitch.domain.model.Chord
import com.controlswitch.domain.model.CaptureRead
import com.controlswitch.domain.model.CaptureResult
import com.controlswitch.domain.model.ControlRef
import com.controlswitch.domain.model.ControllerDevice
import com.controlswitch.domain.model.ControllerMapping
import com.controlswitch.domain.model.MappingKey
import com.controlswitch.domain.model.MirrorSettings
import com.controlswitch.domain.model.MirrorStartRequest
import com.controlswitch.domain.model.PadLayout
import com.controlswitch.domain.model.sourceIcon
import com.controlswitch.domain.model.SlotIcon
import com.controlswitch.domain.model.mappingSlots
import com.controlswitch.domain.model.odinFallbackTraits
import com.controlswitch.domain.repository.InputDeviceRepository
import com.controlswitch.domain.repository.MappingRepository
import com.controlswitch.domain.repository.MirrorProcessRepository
import com.controlswitch.domain.repository.MirrorSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MappingEditorControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun installMainDispatcher() {
        // The controller hops to Main to touch UI state. Unconfined rather than a scheduled test
        // dispatcher because the code under test runs on real threads here: what is being verified
        // is the ordering between coroutines, so nothing may be virtualised away.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * The capture used to be opened by one coroutine and polled by another, with nothing ordering
     * them. The poller could therefore read the log — and move its offset past the end of it —
     * before `clearCaptures` had emptied it, so the new slot could be filled by a press left over
     * from the previous one.
     */
    @Test
    fun `the capture is cleared and opened before anything is read`() {
        val process = FakeProcessRepository()
        val controller = controllerWith(process = process)

        controller.openAndAwait()
        controller.bind(slots.first())

        awaitUntil("no read was ever attempted") { "readCaptures" in process.calls }

        val firstRead = process.calls.indexOf("readCaptures")
        assertTrue(
            "read at $firstRead before the log was cleared (${process.calls})",
            process.calls.indexOf("clearCaptures").let { it in 0 until firstRead },
        )
        assertTrue(
            "read at $firstRead before the daemon was listening (${process.calls})",
            process.calls.indexOf("beginCapture").let { it in 0 until firstRead },
        )
    }

    /**
     * A control already spoken for is refused rather than stolen, and the daemon has to be reopened
     * because it latches on its first result. That reopen must happen on the polling coroutine: as a
     * separate launch it reset the offset underneath a read that was still in flight.
     */
    @Test
    fun `a refused press reopens the capture without a second reader`() {
        val process = FakeProcessRepository()
        val taken = ControlRef.button(BTN_SOUTH)
        val controller = controllerWith(
            process = process,
            mappings = FakeMappingRepository(stored = ControllerMapping(listOf(Binding(taken, slots[0].target)))),
        )

        controller.openAndAwait()
        // Bind a DIFFERENT slot with the control slot 0 already owns.
        controller.bind(slots[1])
        process.deliver(CaptureResult.Button(BTN_SOUTH), CaptureResult.Release(BTN_SOUTH))

        awaitUntil("the capture was never reopened") { process.calls.count { it == "beginCapture" } >= 2 }

        assertEquals(
            "the refusal was not surfaced",
            true,
            controller.state.value.notice?.contains(slots[0].label) == true,
        )
        // Still exactly one poll loop: a second reader would show up as interleaved reads.
        assertEquals("the slot was filled despite the refusal", null, controller.state.value.bindings[slots[1].target])
    }

    /**
     * `close()` runs on the screen's own scope, and closing the screen is what cancels it. A cancel
     * landing between the save and the generation bump stored the mapping and told nobody: the badge
     * showed the new mapping while the daemon went on running the old one.
     */
    @Test
    fun `a cancel during the save still advances the generation`() {
        val savingStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val settings = FakeSettingsRepository()
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(
                listOf(
                    Binding(ControlRef.button(BTN_SOUTH), slots[0].target),
                    Binding(ControlRef.button(BTN_EAST), slots[1].target),
                ),
            ),
            onSave = {
                savingStarted.countDown()
                releaseSave.await(5, TimeUnit.SECONDS)
            },
        )
        val controller = controllerWith(mappings = mappings, settings = settings)

        controller.openAndAwait()
        // Leaves one binding, so this is an edit and takes the save path rather than the clear path.
        controller.clear(slots[0])
        controller.close()

        assertTrue("the save never started", savingStarted.await(5, TimeUnit.SECONDS))
        // The screen goes away mid-write — the exact case that used to drop the bump.
        scope.cancel()
        releaseSave.countDown()

        awaitUntil("the mapping was saved but the daemon was never told") { settings.bumps > 0 }
        assertEquals("the mapping itself was lost", 1, mappings.saved?.bindings?.size)
    }

    @Test
    fun `a visit that changes nothing writes nothing`() {
        val settings = FakeSettingsRepository()
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(listOf(Binding(ControlRef.button(BTN_SOUTH), slots[0].target))),
        )
        val controller = controllerWith(mappings = mappings, settings = settings)

        controller.openAndAwait()
        controller.close()

        Thread.sleep(300L)
        assertEquals("a look around bumped the generation", 0, settings.bumps)
        assertEquals("a look around rewrote the mapping", null, mappings.saved)
    }

    @Test
    fun `with the mirror off the app listens instead`() {
        val process = FakeProcessRepository(daemonRunning = false)
        val controller = controllerWith(process = process)

        controller.openAndAwait()
        controller.bind(slots[0])
        awaitUntil("the app was never asked to listen") { controller.state.value.listenInApp }

        controller.tap(ControlRef.button(BTN_EAST))
        assertEquals(ControlRef.button(BTN_EAST), controller.state.value.bindings[slots[0].target])
        assertEquals(null, controller.state.value.capturing)
    }

    @Test
    fun `map all walks every slot, B skips and X stops`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.mapAll()
        assertEquals(slots[0], controller.state.value.capturing)
        controller.tap(ControlRef.button(BTN_SOUTH))
        assertEquals("a bind did not move the walk on", slots[1], controller.state.value.capturing)
        assertEquals(1, controller.state.value.walk)

        // Already bound to slot 0: refused, the walk waits on the same slot.
        controller.tap(ControlRef.button(BTN_SOUTH))
        assertEquals(slots[1], controller.state.value.capturing)

        controller.cancelBind()
        assertEquals("B did not skip", slots[2], controller.state.value.capturing)
        assertEquals("a skipped slot was bound", null, controller.state.value.bindings[slots[1].target])

        controller.stopWalk()
        assertEquals(null, controller.state.value.capturing)
        assertEquals(null, controller.state.value.walk)
        assertEquals(1, controller.state.value.bindings.size)
    }

    @Test
    fun `the last slot ends the walk`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.mapAll()
        repeat(slots.size) { controller.cancelBind() }
        assertEquals(null, controller.state.value.capturing)
        assertEquals(null, controller.state.value.walk)
    }

    @Test
    fun `the walk never asks for Back`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.mapAll()
        val asked = mutableListOf<SlotIcon>()
        repeat(slots.size) {
            controller.state.value.capturing?.let { asked += it.icon }
            controller.cancelBind()
        }
        assertEquals(null, controller.state.value.walk)
        assertEquals(slots.map { it.icon }.filter { it != SlotIcon.BACK }, asked)
    }

    @Test
    fun `a preset replaces the whole mapping and is saved as the user's`() {
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(listOf(Binding(ControlRef.button(400), slots[0].target))),
        )
        val controller = controllerWith(mappings = mappings)
        controller.openAndAwait()

        controller.autoMap(PadLayout.NINTENDO)
        assertEquals(ControlRef.button(BTN_EAST), controller.state.value.bindings[slots[0].target])
        assertEquals(false, controller.state.value.seeded)
        controller.close()

        awaitUntil("the preset was never saved") { mappings.saved != null }
        // Everything but Home and Back, which a preset leaves alone (when the target offers them at all).
        val bindable = slots.count { it.icon != SlotIcon.HOME && it.icon != SlotIcon.BACK }
        assertEquals(bindable, mappings.saved?.bindings?.size)
    }

    @Test
    fun `a trigger reported as both its axis and its button is one press`() {
        // The handheld's republished pad: one pull of RT arrives as BTN_TR2 and as the GAS axis.
        val tracker = ChordTracker { a, b -> sourceIcon(a) == sourceIcon(b) }
        val button = ControlRef.button(0x139)
        val axis = ControlRef.half(0x09, 1)
        tracker.edge(button, true)
        tracker.edge(axis, true)
        assertEquals("the pull was counted twice", listOf(axis), tracker.gathered)
        assertEquals(null, tracker.edge(button, false))
        assertEquals("letting go did not finish the capture", listOf(axis), tracker.edge(axis, false))
    }

    @Test
    fun `a layout switch is saved, pushed and finished at once`() {
        val mappings = FakeMappingRepository()
        val settings = FakeSettingsRepository()
        var woken = false
        val controller = MappingEditorController(
            mappings = mappings, devices = FakeDeviceRepository(), process = FakeProcessRepository(daemonRunning = false),
            settings = settings, seedDefaultMapping = {}, scope = scope, wakeSupervisor = { woken = true },
        )
        controller.openAndAwait()

        controller.switchLayout(PadLayout.NINTENDO)
        assertEquals(PadLayout.NINTENDO, controller.state.value.switchingTo)
        awaitUntil("the switch never finished") { controller.state.value.switchingTo == null }
        assertEquals(ControlRef.button(BTN_EAST), mappings.saved?.bindings?.first { it.target == slots[0].target }?.source)
        assertEquals(true, settings.bumps > 0 && woken)
    }

    @Test
    fun `the first Custom starts Guided setup and keeps what it binds`() {
        val mappings = FakeMappingRepository()
        val controller = controllerWith(mappings = mappings, process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.switchLayout(PadLayout.CUSTOM)
        assertEquals(PadLayout.CUSTOM, controller.state.value.layout)
        assertEquals("Guided setup did not start", slots[0], controller.state.value.capturing)
        controller.tap(ControlRef.button(BTN_SOUTH))
        controller.stopWalk()

        awaitUntil("the custom layout was never kept") { mappings.custom != null }
        assertEquals(ControlRef.button(BTN_SOUTH), mappings.custom?.bindings?.first { it.target == slots[0].target }?.source)
        assertEquals(1, controller.state.value.customJustSaved)
        assertEquals(true, controller.state.value.hasCustom)
    }

    @Test
    fun `Custom brings back the saved custom layout`() {
        val mine = Binding(ControlRef.button(400), slots[0].target)
        val mappings = FakeMappingRepository().apply { custom = ControllerMapping(listOf(mine)) }
        val controller = controllerWith(mappings = mappings, process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()

        controller.switchLayout(PadLayout.CUSTOM)
        assertEquals(ControlRef.button(400), controller.state.value.bindings[slots[0].target])
        assertEquals("a saved custom layout started Guided setup again", null, controller.state.value.walk)
        awaitUntil("the switch never finished") { controller.state.value.switchingTo == null && mappings.saved != null }
        assertEquals(listOf(mine), mappings.saved?.bindings)
    }

    @Test
    fun `switching to the layout already in force changes nothing`() {
        val mappings = FakeMappingRepository()
        val controller = controllerWith(mappings = mappings)
        controller.openAndAwait()

        controller.switchLayout(PadLayout.XBOX)
        assertEquals(null, controller.state.value.switchingTo)
        Thread.sleep(200)
        assertEquals(null, mappings.saved)
    }

    private val withBack = odinFallbackTraits().let { it.copy(keys = it.keys + KEY_BACK) }

    @Test
    fun `binding Back to another button turns Select + Start as Back off`() {
        val settings = FakeSettingsRepository()
        val controller = controllerWith(
            settings = settings, devices = FakeDeviceRepository(withBack), process = FakeProcessRepository(daemonRunning = false),
        )
        controller.openAndAwait()
        val back = controller.state.value.slots.first { it.icon == SlotIcon.BACK }

        controller.bind(back)
        controller.tap(ControlRef.button(0x13c))
        assertEquals(ControlRef.button(0x13c), controller.state.value.bindings[back.target])
        assertEquals(false, controller.state.value.selectStartBack)
        awaitUntil("the setting was never turned off") { settings.selectStartBack == false }
    }

    @Test
    fun `binding Back to Select + Start turns the setting on instead of saving a copy`() {
        val settings = FakeSettingsRepository()
        val controller = controllerWith(
            settings = settings, devices = FakeDeviceRepository(withBack), process = FakeProcessRepository(daemonRunning = false),
        )
        controller.openAndAwait()
        val back = controller.state.value.slots.first { it.icon == SlotIcon.BACK }
        val select = ControlRef.button(0x13a)
        val start = ControlRef.button(0x13b)
        // Turned off first, by binding Back elsewhere, so there is something to turn back on.
        controller.bind(back)
        controller.tap(ControlRef.button(0x13c))

        controller.bind(back)
        controller.captured(select, true)
        controller.captured(start, true)
        controller.captured(start, false)
        controller.captured(select, false)
        assertEquals(null, controller.state.value.chords[back.target])
        assertEquals(null, controller.state.value.bindings[back.target])
        assertEquals(true, controller.state.value.selectStartBack)
        awaitUntil("the setting was never turned on") { settings.selectStartBack == true }
    }

    @Test
    fun `a saved Select + Start Back is left to the setting when the editor opens`() {
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(chords = listOf(SELECT_START_BACK)),
        )
        val controller = controllerWith(mappings = mappings, devices = FakeDeviceRepository(withBack))
        controller.openAndAwait()
        assertEquals(emptyMap<ControlRef, List<ControlRef>>(), controller.state.value.chords)
    }

    @Test
    fun `a preset becomes player 1's layout`() {
        val mappings = FakeMappingRepository()
        val controller = controllerWith(mappings = mappings)
        controller.openAndAwait()

        controller.autoMap(PadLayout.NINTENDO)
        awaitUntil("player 1's layout was never saved") { mappings.slotLayout == PadLayout.NINTENDO }
    }

    @Test
    fun `a new player 1 takes player 1's layout, saved and pushed at once`() {
        val mappings = FakeMappingRepository().apply { slotLayout = PadLayout.NINTENDO }
        val settings = FakeSettingsRepository()
        val controller = controllerWith(mappings = mappings, settings = settings)

        controller.followPlayer1Layout(MappingKey("057e:2009"))
        awaitUntil("the layout was never applied") { mappings.saved != null }
        assertEquals(PadLayout.NINTENDO, mappings.layout)
        // The Nintendo preset: the A slot is driven by the pad's east button.
        assertEquals(ControlRef.button(BTN_EAST), mappings.saved?.bindings?.first { it.target == slots[0].target }?.source)
        awaitUntil("the daemon was never told") { settings.bumps > 0 }
    }

    @Test
    fun `a pad already in player 1's layout is left as it is`() {
        val mappings = FakeMappingRepository().apply {
            slotLayout = PadLayout.XBOX
            layout = PadLayout.XBOX
        }
        val controller = controllerWith(mappings = mappings)

        controller.followPlayer1Layout(MappingKey("045e:02fd"))
        Thread.sleep(200)
        assertEquals("a pad's own mapping was overwritten", null, mappings.saved)
    }

    @Test
    fun `a preset leaves the user's Home and Back alone`() {
        val withHome = odinFallbackTraits().let { it.copy(keys = it.keys + KEY_HOME) }
        val home = ControlRef.button(KEY_HOME)
        val mappings = FakeMappingRepository(
            stored = ControllerMapping(listOf(Binding(ControlRef.button(0x13c), home))),
        )
        val controller = controllerWith(mappings = mappings, devices = FakeDeviceRepository(withHome))
        controller.openAndAwait()

        controller.autoMap(PadLayout.XBOX)
        assertEquals("the preset threw the Home binding away", ControlRef.button(0x13c), controller.state.value.bindings[home])
    }

    @Test
    fun `a preset switches the icon set and remembers it for the pad`() {
        val mappings = FakeMappingRepository()
        val controller = controllerWith(mappings = mappings)
        controller.openAndAwait()
        assertEquals(PadLayout.XBOX, controller.state.value.layout)

        controller.autoMap(PadLayout.NINTENDO)
        assertEquals(PadLayout.NINTENDO, controller.state.value.layout)
        awaitUntil("the layout was never saved") { mappings.layout == PadLayout.NINTENDO }
    }

    @Test
    fun `holding several controls together binds a chord, shown live while held`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()
        controller.bind(slots[0])
        awaitUntil("the app was never asked to listen") { controller.state.value.listenInApp }

        val a = ControlRef.button(BTN_SOUTH)
        val b = ControlRef.button(BTN_EAST)
        controller.captured(a, true)
        controller.captured(b, true)
        assertEquals("the held controls were not shown", listOf(a, b), controller.state.value.pending)
        controller.captured(a, false)
        assertEquals("committed before everything was let go", slots[0], controller.state.value.capturing)
        controller.captured(b, false)

        assertEquals(listOf(a, b), controller.state.value.chords[slots[0].target])
        assertEquals(null, controller.state.value.bindings[slots[0].target])
        assertEquals(null, controller.state.value.capturing)
        assertEquals(emptyList<ControlRef>(), controller.state.value.pending)
    }

    @Test
    fun `the daemon capture builds a chord from presses and releases in one read`() {
        val process = FakeProcessRepository()
        val mappings = FakeMappingRepository()
        val controller = controllerWith(process = process, mappings = mappings)
        controller.openAndAwait()
        controller.bind(slots[2])
        awaitUntil("the capture never opened") { "beginCapture" in process.calls }
        assertEquals(CaptureKind.CHORD, process.kinds.first())

        process.deliver(
            CaptureResult.Button(BTN_SOUTH), CaptureResult.AxisButton(2, 1),
            CaptureResult.AxisRelease(2, 1), CaptureResult.Release(BTN_SOUTH),
        )
        awaitUntil("the chord was never bound") { controller.state.value.chords.isNotEmpty() }
        assertEquals(listOf(ControlRef.button(BTN_SOUTH), ControlRef.half(2, 1)), controller.state.value.chords[slots[2].target])

        controller.close()
        awaitUntil("the chord was never saved") { mappings.saved != null }
        assertEquals(listOf(Chord(listOf(ControlRef.button(BTN_SOUTH), ControlRef.half(2, 1)), slots[2].target)), mappings.saved?.chords)
    }

    @Test
    fun `a saved chord survives a visit and an edit elsewhere, and X clears it`() {
        val chord = Chord(listOf(ControlRef.button(BTN_SOUTH), ControlRef.button(BTN_EAST)), slots[1].target)
        val settings = FakeSettingsRepository()
        val single = Binding(ControlRef.button(400), slots[0].target)
        val mappings = FakeMappingRepository(stored = ControllerMapping(listOf(single), listOf(chord)))
        val controller = controllerWith(mappings = mappings, settings = settings)

        controller.openAndAwait()
        assertEquals(chord.sources, controller.state.value.chords[slots[1].target])
        controller.close()
        Thread.sleep(300L)
        assertEquals("a look around rewrote the mapping", null, mappings.saved)

        // An edit somewhere else saves the chord along with it.
        controller.openAndAwait()
        controller.clear(slots[0])
        controller.close()
        awaitUntil("never saved") { mappings.saved != null }
        assertEquals(listOf(chord), mappings.saved?.chords)

        // X on the row clears it.
        controller.openAndAwait()
        controller.clear(slots[1])
        assertTrue(controller.state.value.chords.isEmpty())
        controller.close()
        // Nothing left at all, so the record is cleared (and the profile re-seeded) rather than saved.
        awaitUntil("the clear was never written") { mappings.cleared }
    }

    @Test
    fun `a preset drops chords on the slots it binds`() {
        val chord = Chord(listOf(ControlRef.button(BTN_SOUTH), ControlRef.button(BTN_EAST)), slots[1].target)
        val controller = controllerWith(mappings = FakeMappingRepository(stored = ControllerMapping(chords = listOf(chord))))
        controller.openAndAwait()
        controller.autoMap(PadLayout.XBOX)
        assertTrue(controller.state.value.chords.isEmpty())
    }

    @Test
    fun `Home takes a chord, a preset keeps it, and a single press replaces it`() {
        val withHome = odinFallbackTraits().let { it.copy(keys = it.keys + KEY_HOME) }
        val home = ControlRef.button(KEY_HOME)
        val sources = listOf(ControlRef.button(0x13c), ControlRef.button(BTN_SOUTH))
        val controller = controllerWith(
            process = FakeProcessRepository(daemonRunning = false),
            devices = FakeDeviceRepository(withHome),
        )
        controller.openAndAwait()
        val homeSlot = controller.state.value.slots.first { it.target == home }

        controller.bind(homeSlot)
        awaitUntil("the app was never asked to listen") { controller.state.value.listenInApp }
        sources.forEach { controller.captured(it, true) }
        sources.forEach { controller.captured(it, false) }
        assertEquals(sources, controller.state.value.chords[home])

        controller.autoMap(PadLayout.NINTENDO)
        assertEquals("a preset threw the Home chord away", sources, controller.state.value.chords[home])

        controller.bind(homeSlot)
        controller.tap(ControlRef.button(0x13c))
        assertEquals(ControlRef.button(0x13c), controller.state.value.bindings[home])
        assertEquals(null, controller.state.value.chords[home])
    }

    @Test
    fun `the same combination is refused on a second slot`() {
        val controller = controllerWith(process = FakeProcessRepository(daemonRunning = false))
        controller.openAndAwait()
        val combo = listOf(ControlRef.button(BTN_SOUTH), ControlRef.button(BTN_EAST))
        for (slot in listOf(slots[0], slots[1])) {
            controller.bind(slot)
            awaitUntil("the app was never asked to listen") { controller.state.value.listenInApp }
            combo.forEach { controller.captured(it, true) }
            combo.reversed().forEach { controller.captured(it, false) }
        }
        assertEquals(combo, controller.state.value.chords[slots[0].target])
        assertEquals(null, controller.state.value.chords[slots[1].target])
        assertTrue(controller.state.value.notice?.contains(slots[0].label) == true)
    }

    @Test
    fun `the tracker caps a chord at four and ignores releases it never saw pressed`() {
        val t = ChordTracker()
        assertEquals(null, t.edge(ControlRef.button(1), false))
        (1..5).forEach { assertEquals(null, t.edge(ControlRef.button(it), true)) }
        assertEquals(4, t.gathered.size)
        (1..4).forEach { assertEquals(null, t.edge(ControlRef.button(it), false)) }
        assertEquals((1..4).map { ControlRef.button(it) }, t.edge(ControlRef.button(5), false))
        assertTrue(t.gathered.isEmpty())
    }

    // ---- harness ----------------------------------------------------------------------------

    private val slots = mappingSlots(odinFallbackTraits())

    private fun controllerWith(
        mappings: MappingRepository = FakeMappingRepository(),
        process: MirrorProcessRepository = FakeProcessRepository(),
        settings: MirrorSettingsRepository = FakeSettingsRepository(),
        devices: InputDeviceRepository = FakeDeviceRepository(),
    ) = MappingEditorController(
        mappings = mappings,
        devices = devices,
        process = process,
        settings = settings,
        seedDefaultMapping = {},
        scope = scope,
    )

    /** One press and release, the ordinary one-control binding. */
    private fun MappingEditorController.tap(source: ControlRef) {
        captured(source, true)
        captured(source, false)
    }

    private fun MappingEditorController.openAndAwait() {
        open("Test pad", MappingKey("045e:028e"))
        awaitUntil("the editor never opened") { state.value.open && !state.value.loading }
    }

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        throw AssertionError(message)
    }

    private class FakeMappingRepository(
        private val stored: ControllerMapping = ControllerMapping(),
        private val onSave: () -> Unit = {},
    ) : MappingRepository {
        @Volatile
        var saved: ControllerMapping? = null

        @Volatile
        var cleared = false

        override fun get(key: MappingKey): ControllerMapping = saved ?: stored

        override fun save(key: MappingKey, mapping: ControllerMapping) {
            onSave()
            saved = mapping
        }

        override fun clear(key: MappingKey) {
            cleared = true
        }

        @Volatile
        var layout = PadLayout.XBOX

        override fun layout(key: MappingKey) = layout

        override fun storedLayout(key: MappingKey) = layout

        override fun setLayout(key: MappingKey, layout: PadLayout) {
            this.layout = layout
        }

        @Volatile
        var slotLayout: PadLayout? = null

        override fun player1Layout() = slotLayout

        override fun setPlayer1Layout(layout: PadLayout) {
            slotLayout = layout
        }

        @Volatile
        var custom: ControllerMapping? = null

        override fun customMapping(key: MappingKey) = custom

        override fun saveCustomMapping(key: MappingKey, mapping: ControllerMapping) {
            custom = mapping
        }
    }

    private class FakeSettingsRepository : MirrorSettingsRepository {
        @Volatile
        var bumps = 0

        override fun getSettings() = MirrorSettings()
        override fun saveStarted(request: MirrorStartRequest, startedAt: Long) = Unit
        override fun setExpectedRunning(expectedRunning: Boolean) = Unit
        override fun setPlayer1(name: String?, path: String?) = Unit
        override fun setHomeMode(mode: HomeButtonMode) = Unit
        override fun setComboHoldKillAppEnabled(enabled: Boolean) = Unit

        override fun setRumbleStrength(percent: Int) = Unit
        override fun setAutoMirrorEnabled(enabled: Boolean) = Unit
        override fun setTheme(theme: com.controlswitch.domain.model.AppTheme) = Unit
        override fun setMirrorWhenHandheld(enabled: Boolean) = Unit
        override fun setManualInternalController(guid: String?) = Unit
        override fun bumpConfigGeneration() {
            bumps++
        }

        @Volatile
        var selectStartBack: Boolean? = null

        override fun setSelectStartBack(enabled: Boolean) {
            selectStartBack = enabled
        }
    }

    private class FakeDeviceRepository(private val target: TargetTraits? = null) : InputDeviceRepository {
        override fun getConnectedControllers(): List<ControllerDevice> = emptyList()
        override fun targetTraits(): TargetTraits? = target
    }

    /** Records the order of every call, and hands out a press only when a test says so. */
    private class FakeProcessRepository(private val daemonRunning: Boolean = true) : MirrorProcessRepository {
        val calls: List<String> = Collections.synchronizedList(mutableListOf())

        private val pending = java.util.concurrent.ConcurrentLinkedQueue<CaptureResult>()
        val kinds: List<CaptureKind> = Collections.synchronizedList(mutableListOf())

        /** Lands in the log together, so one poll reads them all. */
        fun deliver(vararg results: CaptureResult) {
            pending.addAll(results)
        }

        private fun record(name: String) {
            (calls as MutableList<String>).add(name)
        }

        override fun readCaptures(offset: Long): CaptureRead {
            record("readCaptures")
            val results = generateSequence { pending.poll() }.toList()
            return CaptureRead(results = results, offset = offset + results.size)
        }

        override fun clearCaptures() = record("clearCaptures")

        override fun beginCapture(kind: CaptureKind): Boolean {
            record("beginCapture")
            (kinds as MutableList<CaptureKind>).add(kind)
            return daemonRunning
        }

        override fun endCapture(): Boolean {
            record("endCapture")
            return true
        }

        override fun start(request: MirrorStartRequest) = Unit
        override fun stop() = Unit
        override fun isRunning() = true
        override fun clearProcessFiles() = Unit
        override fun healOrphanedHideNodes() = Unit
        override fun applyLiveSettings(settings: MirrorSettings, mapping: ControllerMapping) = true
        override fun appliedConfigGeneration(): Long? = 0L
    }

    private companion object {
        const val BTN_SOUTH = 304
        const val BTN_EAST = 305
    }
}
