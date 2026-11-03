package com.controlswitch.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingSlotTest {
    @Test
    fun everySlotHasADistinctTarget() {
        // A duplicate would mean two rows in the list quietly overwriting each other's binding.
        val targets = mappingSlots(odinFallbackTraits()).map { it.target }
        assertEquals(targets.size, targets.toSet().size)
    }

    @Test
    fun odinTraitsProduceTheMeasuredTargets() {
        // Regression pinned to what was measured on the device: dpad on the hat, right stick on
        // Z/RZ, triggers on BRAKE/GAS — the codes the internal pad declares, none of the ones it
        // does not. Every one of these was once wrong precisely because it was assumed.
        val byLabel = mappingSlots(odinFallbackTraits()).associateBy { it.label }

        assertEquals(ControlRef.half(0x11, -1), byLabel.getValue("D-pad up").target)
        assertEquals(ControlRef.half(0x10, 1), byLabel.getValue("D-pad right").target)
        assertEquals(ControlRef.half(0x05, -1), byLabel.getValue("Right stick up").target)
        assertEquals(ControlRef.half(0x02, 1), byLabel.getValue("Right stick right").target)
        assertEquals(ControlRef.half(0x0a, 1), byLabel.getValue("Left trigger").target)
        assertEquals(ControlRef.half(0x09, 1), byLabel.getValue("Right trigger").target)
    }

    @Test
    fun faceLettersFollowTheKeyLayoutFileNotTheHeaderNames() {
        // The firmware's .kl says 0x133 is X and 0x134 is Y — the reverse of what the kernel names
        // (BTN_NORTH/BTN_WEST) suggest for an Xbox pad. Trusting the convention over the file is
        // what once shipped X and Y swapped: the user bound X, the pad pressed Y.
        val byLabel = mappingSlots(odinFallbackTraits()).associateBy { it.label }

        assertEquals(ControlRef.button(0x133), byLabel.getValue("X").target)
        assertEquals(ControlRef.button(0x134), byLabel.getValue("Y").target)
    }

    @Test
    fun xpadStyleTargetGetsRxRyStickAndZRzTriggers() {
        // The other common family: no pedal axes, Z/RZ are the triggers (so says the layout file)
        // and the right stick lives on RX/RY. The trigger axes must not be offered as a stick.
        val xpad = TargetTraits(
            keys = setOf(0x130, 0x131, 0x133, 0x134, 0x220, 0x221, 0x222, 0x223),
            axes = setOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05),
            axisLabels = mapOf(0x02 to "LTRIGGER", 0x05 to "RTRIGGER"),
        )
        val byLabel = mappingSlots(xpad).associateBy { it.label }

        assertEquals(ControlRef.half(0x02, 1), byLabel.getValue("Left trigger").target)
        assertEquals(ControlRef.half(0x03, -1), byLabel.getValue("Right stick left").target)
        assertEquals(ControlRef.half(0x04, -1), byLabel.getValue("Right stick up").target)
    }

    @Test
    fun aTargetWithoutAHatFallsBackToDpadKeys() {
        val traits = TargetTraits(
            keys = setOf(0x130, 0x220, 0x221, 0x222, 0x223),
            axes = setOf(0x00, 0x01),
        )
        val byLabel = mappingSlots(traits).associateBy { it.label }

        assertEquals(ControlRef.button(0x220), byLabel.getValue("D-pad up").target)
    }

    @Test
    fun aControlTheTargetLacksProducesNoSlot() {
        // A slot that can only feed events the kernel drops is worse than its absence.
        val minimal = TargetTraits(keys = setOf(0x130, 0x131), axes = setOf(0x00, 0x01))
        val labels = mappingSlots(minimal).map { it.label }

        assertTrue("Right stick up" !in labels)
        assertTrue("D-pad up" !in labels)
        assertTrue("Left trigger" !in labels)
        assertTrue("Left stick up" in labels)
        assertTrue("A" in labels)
    }

    @Test
    fun eachStickDirectionTakesADistinctHalfOfItsAxis() {
        // The pair that has to be right for a stick to work at all: up and down are the two halves of
        // one axis, and giving them the same half leaves the stick able to move only one way.
        val left = mappingSlots(odinFallbackTraits()).filter { it.label.startsWith("Left stick") && it.icon != SlotIcon.LEFT_STICK_CLICK }

        assertEquals(4, left.size)
        assertTrue(left.all { it.target.kind == ControlKind.HALF_AXIS })
        val up = left.first { it.label.endsWith("up") }.target
        val down = left.first { it.label.endsWith("down") }.target
        assertEquals(up.code, down.code)
        assertEquals(-up.direction, down.direction)
        // evdev counts Y downward, so up is the negative half. Backwards here means an inverted stick.
        assertEquals(-1, up.direction)
    }

    @Test
    fun aBoundControlIsNamedByItsStandardMeaning() {
        assertEquals("A / South", describeSource(ControlRef.button(0x130)))
        assertEquals("B / East", describeSource(ControlRef.button(0x131)))
        assertEquals("Select", describeSource(ControlRef.button(0x13a)))
        assertEquals("D-pad right", describeSource(ControlRef.button(0x223)))
        assertEquals("Left stick up", describeSource(ControlRef.half(0x01, -1)))
        assertEquals("Right stick right", describeSource(ControlRef.half(0x02, 1)))
        assertEquals("D-pad up", describeSource(ControlRef.half(0x11, -1)))
        assertEquals("+L2", describeSource(ControlRef.half(0x0a, 1)))
        assertEquals("+R2", describeSource(ControlRef.half(0x09, 1)))
        // The pad's own layout wins: an xpad Xbox pad has its triggers on Z/RZ and its stick on RX/RY.
        assertEquals("+L2", describeSource(ControlRef.half(0x02, 1), mapOf(0x02 to "LTRIGGER")))
        assertEquals("Right stick right", describeSource(ControlRef.half(0x03, 1), mapOf(0x03 to "Z")))
        // No standard meaning: the number is all we honestly know.
        assertEquals("Button 400", describeSource(ControlRef.button(400)))
        assertEquals("+Axis 40", describeSource(ControlRef.half(40, 1)))
        assertEquals("Axis 0 (inverted)", describeSource(ControlRef.axis(0, -1)))
    }

    @Test
    fun presetsMakeThePrintedLetterProduceThatAndroidButtonOnTheThor() {
        // The Thor target's layout puts BUTTON_A..Y on 0x130/0x131/0x133/0x134, and a bound event
        // skips the daemon's Nintendo swap, so slot target == what Android reads.
        // The Thor also declares KEY_HOME and KEY_BACK, which is what makes the Home and Back slots appear.
        val thor = odinFallbackTraits().let { it.copy(keys = it.keys + setOf(KEY_HOME, KEY_BACK)) }
        val slots = mappingSlots(thor)
        val target = slots.associate { it.label to it.target }
        val xbox = presetBindings(PadLayout.XBOX, slots, null)
        val nintendo = presetBindings(PadLayout.NINTENDO, slots, null)

        fun source(map: Map<ControlRef, ControlRef>, label: String) = map[target.getValue(label)]

        assertEquals(ControlRef.button(0x130), source(xbox, "A"))
        assertEquals(ControlRef.button(0x131), source(xbox, "B"))
        assertEquals(ControlRef.button(0x133), source(xbox, "X"))
        assertEquals(ControlRef.button(0x134), source(xbox, "Y"))
        // hid-nintendo reports by position: printed A is East, printed X sits where Xbox's Y does.
        assertEquals(ControlRef.button(0x131), source(nintendo, "A"))
        assertEquals(ControlRef.button(0x130), source(nintendo, "B"))
        assertEquals(ControlRef.button(0x134), source(nintendo, "X"))
        assertEquals(ControlRef.button(0x133), source(nintendo, "Y"))

        for (preset in listOf(xbox, nintendo)) {
            assertEquals(ControlRef.button(0x136), source(preset, "Left bumper"))
            assertEquals(ControlRef.half(0x0a, 1), source(preset, "Left trigger"))
            assertEquals(ControlRef.half(0x09, 1), source(preset, "Right trigger"))
            assertEquals(ControlRef.half(0x11, -1), source(preset, "D-pad up"))
            assertEquals(ControlRef.half(0x05, -1), source(preset, "Right stick up"))
            assertEquals(ControlRef.button(0x13b), source(preset, "Start"))
            assertEquals(null, source(preset, "Home"))
            assertEquals(null, source(preset, "Back"))
            // Every slot but Home and Back, and no control driving two slots.
            assertEquals(slots.size - 2, preset.size)
            assertEquals(preset.size, preset.values.toSet().size)
        }
    }

    @Test
    fun presetsReadTheSourcePadsOwnCodes() {
        // A Switch Pro on the Thor: d-pad as keys, digital ZL/ZR, right stick on RX/RY.
        val switchPro = TargetTraits(
            keys = setOf(0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x138, 0x139, 0x13a, 0x13b, 0x13c,
                0x13d, 0x13e, 0x220, 0x221, 0x222, 0x223),
            axes = setOf(0x00, 0x01, 0x03, 0x04),
        )
        val slots = mappingSlots(odinFallbackTraits())
        val target = slots.associate { it.label to it.target }
        val preset = presetBindings(PadLayout.NINTENDO, slots, switchPro)

        assertEquals(ControlRef.button(0x131), preset[target.getValue("A")])
        assertEquals(ControlRef.button(0x220), preset[target.getValue("D-pad up")])
        assertEquals(ControlRef.button(0x138), preset[target.getValue("Left trigger")])
        assertEquals(ControlRef.half(0x04, -1), preset[target.getValue("Right stick up")])
    }
}
