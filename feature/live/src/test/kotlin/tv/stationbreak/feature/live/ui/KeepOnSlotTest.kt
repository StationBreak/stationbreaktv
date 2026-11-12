// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepOnSlotTest {
    private class FakeFlag(
        override var value: Boolean,
    ) : KeepOnSlot.Flag

    private val old = Any()
    private val new = Any()

    @Test
    fun anOldOwnersLateDisposeAfterTheNewOwnersWritesLeavesTheFlagOn() {
        val flag = FakeFlag(false)
        val slot = KeepOnSlot(flag)
        slot.claim(old)
        slot.write(old, true)
        slot.claim(new)
        slot.write(new, true)
        assertFalse(slot.release(old))
        assertTrue(flag.value)
    }

    @Test
    fun anOldOwnersDisposeBeforeTheNewOwnersWritesLeavesTheFlagOn() {
        val flag = FakeFlag(false)
        val slot = KeepOnSlot(flag)
        slot.claim(old)
        slot.write(old, true)
        assertTrue(slot.release(old))
        slot.claim(new)
        slot.write(new, true)
        assertTrue(flag.value)
    }

    @Test
    fun theOnlyOwnersDisposeRestoresTheValueFoundBefore() {
        val flag = FakeFlag(true)
        val slot = KeepOnSlot(flag)
        slot.claim(old)
        slot.write(old, false)
        assertTrue(slot.release(old))
        assertTrue(flag.value)
    }

    @Test
    fun aNewOwnerKeepsTheValueFoundBeforeTheFirstOne() {
        val flag = FakeFlag(false)
        val slot = KeepOnSlot(flag)
        slot.claim(old)
        slot.write(old, true)
        slot.claim(new)
        slot.write(new, true)
        slot.release(new)
        assertFalse(flag.value)
    }

    @Test
    fun aNonOwnersWriteIsIgnored() {
        val flag = FakeFlag(false)
        val slot = KeepOnSlot(flag)
        slot.claim(old)
        slot.claim(new)
        slot.write(new, true)
        assertFalse(slot.write(old, false))
        assertTrue(flag.value)
    }
}
