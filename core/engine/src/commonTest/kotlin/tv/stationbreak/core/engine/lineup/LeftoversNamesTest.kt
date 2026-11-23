// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlin.test.Test
import kotlin.test.assertEquals

// Catalog 15 (#1103): the three young leftovers channels take the band names; keys, numbers and ceilings stay.
class LeftoversNamesTest {
    @Test
    fun theYoungLeftoversTakeTheBandNamesAndKeepTheirKeysNumbersAndCeilings() {
        val shipped =
            StarterCatalog.leftovers.take(3).map { listOf(it.key, it.name, it.number.toString(), it.maxRating) }
        assertEquals(
            listOf(
                listOf("kids-odds-and-ends", "Tots' Odds & Ends", "33", "PG"),
                listOf("family-odds-and-ends", "Kids' Odds & Ends", "66", "PG"),
                listOf("teen-odds-and-ends", "Teens' Odds & Ends", "99", "TV-14"),
            ),
            shipped,
        )
    }
}
