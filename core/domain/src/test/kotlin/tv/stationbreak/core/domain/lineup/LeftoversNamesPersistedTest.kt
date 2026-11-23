// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.StarterCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

// Catalog 15 (#1103): a stored leftovers channel holds its key, never its catalog name, so an existing lineup shows
// the catalog's new name for that key after the update.
class LeftoversNamesPersistedTest {
    @Test
    fun aStoredLineupShowsTheCatalogsNewLeftoversNames() {
        val stored =
            LineupCodec.encode(
                lineupOf(
                    leftovers("kids-odds-and-ends", 33, "PG"),
                    leftovers("family-odds-and-ends", 66, "PG"),
                    leftovers("teen-odds-and-ends", 99, "TV-14"),
                ),
            )
        assertFalse(stored.decodeToString().contains("Odds & Ends"), "no name is stored")
        val loaded = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(stored))
        assertEquals(
            listOf("Tots' Odds & Ends", "Kids' Odds & Ends", "Teens' Odds & Ends"),
            loaded.lineup.channels.map { it.displayName(StarterCatalog) },
        )
    }
}
