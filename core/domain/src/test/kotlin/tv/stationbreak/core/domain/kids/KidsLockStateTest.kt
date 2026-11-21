// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which channel numbers a Kids Mode lock lets through (#789, #993). */
class KidsLockStateTest {
    private fun on(cap: KidsCap) = KidsLockState(enabled = true, cap = cap, pinSet = true)

    @Test
    fun withTheLockOffEveryNumberIsAllowed() {
        val off = KidsLockState(enabled = false, cap = KidsCap.Kids, pinSet = true)
        for (number in listOf(1, 33, 34, 70, 99, 100, 500, 0, null)) assertTrue(off.allows(number), "$number")
    }

    @Test
    fun withTheFamilyCapForty_isAllowedAndSeventyIsNot() {
        val family = on(KidsCap.Family)
        assertTrue(family.allows(40))
        assertTrue(family.allows(66))
        assertFalse(family.allows(67))
        assertFalse(family.allows(70))
    }

    @Test
    fun eachCapStopsAtItsOwnNumber() {
        assertTrue(on(KidsCap.Kids).allows(33))
        assertFalse(on(KidsCap.Kids).allows(34))
        assertTrue(on(KidsCap.Teen).allows(99))
        assertFalse(on(KidsCap.Teen).allows(100))
    }

    @Test
    fun aNumberOutsideOneToTheCapIsHiddenWhileOn() {
        assertFalse(on(KidsCap.Teen).allows(0))
        assertFalse(on(KidsCap.Teen).allows(-1))
        assertFalse(on(KidsCap.Teen).allows(null))
    }
}
