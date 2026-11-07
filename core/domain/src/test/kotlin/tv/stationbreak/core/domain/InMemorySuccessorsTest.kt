// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The fake answers like the Room repository: only for a tombstone, and only into a library asked for (spec 26). */
class InMemorySuccessorsTest {
    private val account =
        LibraryAccount(ServerId("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"), UserId("b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1"))
    private val library = ItemId("c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1")
    private val repo = InMemoryLibraryRepository()

    private fun id(n: Int) = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    private fun film(n: Int) = MediaItem(ItemId(id(n).value), "Invented film $n", ItemKind.Movie, 90.minutes, library)

    @Test
    fun answersOnlyForATombstone_withALiveSuccessor() =
        runBlocking {
            repo.seed(account, library, film(1), film(2), film(4))
            repo.seed(account, library, film(3), tombstoned = true)
            for (gone in 1..6) repo.seedSuccessor(account, id(gone), id(4))

            val found = repo.successors(account, setOf(library), (1..6).mapTo(HashSet()) { id(it) })

            // 1, 2 and 4 are live and 5 and 6 have no row: only 3 is gone.
            assertEquals(mapOf(id(3) to id(4)), found)
        }
}
