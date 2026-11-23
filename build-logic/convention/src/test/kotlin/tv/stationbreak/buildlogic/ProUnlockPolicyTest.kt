// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProUnlockPolicyTest {
    @Test
    fun `the Amazon release is baked locked`() {
        assertEquals(false, ProUnlockPolicy.unlocked("release", "amazon"))
    }

    @Test
    fun `the GitHub release is unlocked`() {
        assertEquals(true, ProUnlockPolicy.unlocked("release", "github"))
    }

    @Test
    fun `debug and benchmark are unlocked in both flavors`() {
        for (buildType in listOf("debug", "benchmark")) {
            for (flavor in listOf("amazon", "github")) {
                assertEquals(true, ProUnlockPolicy.unlocked(buildType, flavor), "$flavor $buildType")
            }
        }
    }

    @Test
    fun `a pair that is not listed is locked`() {
        assertEquals(false, ProUnlockPolicy.unlocked("release", "somestore"))
        assertEquals(false, ProUnlockPolicy.unlocked("debug", "somestore"))
        assertEquals(false, ProUnlockPolicy.unlocked("debug", null))
        assertEquals(false, ProUnlockPolicy.unlocked("staging", "amazon"))
    }

    @Test
    fun `no build type means release`() {
        assertEquals(false, ProUnlockPolicy.unlocked(null, "amazon"))
        assertEquals(true, ProUnlockPolicy.unlocked(null, "github"))
    }

    @Test
    fun `the guard fails a store release baked unlocked`() {
        assertFailsWith<IllegalStateException> { ProUnlockPolicy.check("release", "amazon", true) }
        assertFailsWith<IllegalStateException> { ProUnlockPolicy.check("release", "somestore", true) }
        assertFailsWith<IllegalStateException> { ProUnlockPolicy.check("release", null, true) }
    }

    @Test
    fun `a matrix that bakes the Amazon release unlocked fails the build`() {
        val bad = ProUnlockPolicy.matrix + (("release" to "amazon") to true)
        assertFailsWith<IllegalStateException> { ProUnlockPolicy.unlocked("release", "amazon", bad) }
    }

    @Test
    fun `the guard lets the closed store release and the GitHub release through`() {
        ProUnlockPolicy.check("release", "amazon", false)
        ProUnlockPolicy.check("release", "github", true)
        ProUnlockPolicy.check("debug", "amazon", true)
        ProUnlockPolicy.check("benchmark", "amazon", true)
    }
}
