// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HouseholdSourcesTest {
    private val library =
        snapshot(
            collections =
                listOf(
                    collection(idOf(1), name = "Box Set 1 Collection"),
                    collection(idOf(2), name = "Box Set 2"),
                    collection(idOf(3)),
                ),
        )

    @Test
    fun householdOffDropsNonCollectionNames() {
        assertTrue(HouseholdSources.isHouseholdOnly("Box Set 2"))
        assertFalse(HouseholdSources.isHouseholdOnly("Box Set 1 collection"))
        val all = rules(listOf(Source.Genre("A"), Source.Collection(idOf(1)), Source.Collection(idOf(2))))
        val kept = checkNotNull(HouseholdSources.without(all, library))
        assertEquals(listOf(Source.Genre("A"), Source.Collection(idOf(1))), kept.sources)
    }

    @Test
    fun aCollectionWithNoNameIsHousehold() {
        assertTrue(HouseholdSources.isHouseholdOnly(null))
        val unnamed = rules(listOf(Source.Genre("A"), Source.Collection(idOf(3)), Source.Collection(idOf(99))))
        assertEquals(listOf(Source.Genre("A")), checkNotNull(HouseholdSources.without(unnamed, library)).sources)
    }

    @Test
    fun nothingLeftGivesNull() {
        assertNull(HouseholdSources.without(rules(listOf(Source.Collection(idOf(2)))), library))
        val plain = rules(listOf(Source.Genre("A")))
        assertSame(plain, HouseholdSources.without(plain, library))
    }
}
