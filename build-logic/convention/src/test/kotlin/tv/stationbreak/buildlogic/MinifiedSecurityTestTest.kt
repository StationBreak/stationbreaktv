// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinifiedSecurityTestTest {
    @Test
    fun onlyTrueTurnsTheRunOn() = assertTrue(minifiedSecurityRequested("true"))

    @Test
    fun falseLeavesTheBuildAsItIs() = assertFalse(minifiedSecurityRequested("false"))

    @Test
    fun anAbsentPropertyLeavesTheBuildAsItIs() = assertFalse(minifiedSecurityRequested(null))

    @Test
    fun anythingElseLeavesTheBuildAsItIs() {
        assertFalse(minifiedSecurityRequested(""))
        assertFalse(minifiedSecurityRequested("yes"))
        assertFalse(minifiedSecurityRequested("1"))
    }
}
