// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The key of a `recipe` seed (docs/rules-format.md, "How seeds are made"): 1 to 64 characters of a-z, 0-9 and `-`. */
class AutoSeedRecipeKeyTest {
    private val server = "11111111111111111111111111111111"

    private fun derive(key: String) = AutoSeed.derive(server, AutoSeedKind.Recipe, key)

    @Test
    fun theWireNameIsRecipe() {
        assertEquals("recipe", AutoSeedKind.Recipe.wireName)
    }

    @Test
    fun aLowerCaseHyphenatedKeyIsAccepted() {
        derive("rubber-suit-theatre")
        derive("a")
        derive("0-9")
        derive("a".repeat(64))
    }

    @Test
    fun theSeedDependsOnTheKey() {
        assertEquals(derive("little-farm"), derive("little-farm"))
        assertNotEquals(derive("little-farm"), derive("odds-and-ends"))
    }

    @Test
    fun anInvalidKeyIsRejected() {
        for (key in listOf("", "Rubber", "a b", "é", "a_b", "a/b", "a\n", "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException>("key <$key>") { derive(key) }
        }
    }

    @Test
    fun theKeyCheckAgreesWithDerive() {
        for (key in listOf("a", "0-9", "a".repeat(64))) assertTrue(AutoSeed.isValidRecipeKey(key))
        for (key in listOf("", "Rubber", "a b", "é", "a_b", "a".repeat(65))) assertFalse(AutoSeed.isValidRecipeKey(key))
    }
}
