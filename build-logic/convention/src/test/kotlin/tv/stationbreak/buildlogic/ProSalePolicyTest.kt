// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Nothing here pins the Amazon release's value: flipping the owner's switch is a one-line change to the policy.
class ProSalePolicyTest {
    @Test
    fun `debug and benchmark Amazon builds are on sale`() {
        assertEquals(true, ProSalePolicy.onSale("debug", "amazon"))
        assertEquals(true, ProSalePolicy.onSale("benchmark", "amazon"))
    }

    @Test
    fun `the github flavor is never on sale`() {
        for (buildType in listOf("debug", "benchmark", "release")) {
            assertEquals(false, ProSalePolicy.onSale(buildType, "github"), buildType)
        }
    }

    @Test
    fun `a pair that is not listed is off sale and no build type means release`() {
        assertEquals(false, ProSalePolicy.onSale("staging", "amazon"))
        assertEquals(false, ProSalePolicy.onSale("debug", null))
        assertEquals(ProSalePolicy.onSale("release", "amazon"), ProSalePolicy.onSale(null, "amazon"))
    }

    @Test
    fun `a matrix that puts a flavor with no store on sale fails the build`() {
        val bad = ProSalePolicy.matrix + (("release" to "github") to true)
        assertFailsWith<IllegalStateException> { ProSalePolicy.onSale("release", "github", bad) }
        assertFailsWith<IllegalStateException> { ProSalePolicy.check("debug", null, true) }
    }

    @Test
    fun `the guard lets an Amazon build be on sale or off`() {
        ProSalePolicy.check("release", "amazon", true)
        ProSalePolicy.check("release", "amazon", false)
    }
}
