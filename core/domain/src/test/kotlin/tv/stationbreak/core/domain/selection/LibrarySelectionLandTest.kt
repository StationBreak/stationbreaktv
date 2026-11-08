// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The landing of an added library (spec 26, Part 4 §2 "passLanded"): saved first, then landed in the cache. */
internal class LibrarySelectionLandTest : SelectionTestBase() {
    // A TV with A chosen and B being added: its walk wrote B's rows and proved it ended.
    private fun adding(): Pair<Tv, Long> {
        val tv = Tv(lineup = lineupOf(laughs), chosen = setOf(libA)).ready(setOf(libA, libB))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        val stamp = assertNotNull(tv.stored()).adding.getValue(libB)
        tv.seedItems(comediesB)
        assertTrue(tv.fake.proveWalk(ACCOUNT, libB, stamp))
        return tv to stamp
    }

    private fun Tv.land(
        id: ItemId,
        stamp: Long,
        account: LibraryAccount = ACCOUNT,
    ): LandResult = runBlocking { selection.passLanded(account, id, stamp) }

    @Test
    fun aProvenAdd_isChosenWithANewSeq_thenLanded_andTheSeqIsPublishedAfterTheLanding() {
        val (tv, stamp) = adding()
        val before = assertNotNull(tv.stored()).seq
        val publishedAtLanding = mutableListOf<Long>()
        tv.library.onLand = { publishedAtLanding += tv.selection.selectionChanges.value }

        assertEquals(LandResult.Landed, tv.land(libB, stamp))

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libA, libB), stored.chosen)
        assertTrue(stored.adding.isEmpty())
        assertTrue(stored.seq > before)
        assertEquals(listOf(libB to stamp), tv.library.landed)
        val row = assertNotNull(tv.row(libB))
        assertTrue(row.fetched)
        assertNull(row.addStamp)
        assertEquals(listOf(before), publishedAtLanding, "the refresher hears of it only once the cache landed it")
        assertEquals(stored.seq, tv.selection.selectionChanges.value)
    }

    // Binding item 7: a landed add rebuilds the days kept for the selection before it. The kept days are keyed on the
    // playable libraries (and the repair record's digests), so a landing that adds its library to them builds anew.
    @Test
    fun aLandedAdd_rebuildsTheKeptDays_soTomorrowAirsTheAddedLibrary() {
        val (tv, stamp) = adding()
        val tomorrow = noonMs + 24 * hour
        val bIds = comediesB.map { it.item.id }.toSet()

        fun airedFromB(): Boolean =
            tv.rig.window(laughs.id, tomorrow, hours = 24).any { block ->
                val airing = block.airing
                airing is Airing.Program && airing.itemId in bIds
            }
        assertFalse(airedFromB(), "not chosen yet: no day reads B")

        tv.land(libB, stamp)

        assertTrue(airedFromB(), "the days kept before the landing are not handed out again")
    }

    @Test
    fun aStampThatIsNotTheOneBeingAdded_landsNothing() {
        val (tv, stamp) = adding()
        val stored = tv.stored()

        assertEquals(LandResult.NotCurrent, tv.land(libB, stamp + 1))
        assertEquals(LandResult.NotCurrent, tv.land(libC, stamp))
        assertEquals(LandResult.NotCurrent, tv.land(libB, stamp, account = other))

        assertEquals(stored, tv.stored())
        assertTrue(tv.library.landed.isEmpty())
    }

    @Test
    fun aSaveThatFails_isRetried_andLandsNothing() {
        val (tv, stamp) = adding()
        val stored = tv.stored()
        tv.settings.failSaves = 1

        assertEquals(LandResult.Retry, tv.land(libB, stamp))

        assertEquals(stored, tv.stored())
        assertTrue(tv.library.landed.isEmpty())
        assertEquals(LandResult.Landed, tv.land(libB, stamp), "the retry")
    }

    @Test
    fun whileGone_theLandedLibraryIsTheWholeChoice_andTheGoneOnesLeave() {
        val tv =
            Tv(
                lineup = lineupOf(laughs),
                chosen = setOf(libA),
                stored = StoredSelection(setOf(libA), seq = 3L, adding = mapOf(libB to 3L), gone = true),
            )
        tv.stampInRoom(libB, 3L, setOf(libA))
        tv.ready(setOf(libB))
        tv.seedItems(comediesB)
        tv.fake.proveWalk(ACCOUNT, libB, 3L)

        assertEquals(LandResult.Landed, tv.land(libB, 3L))

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libB), stored.chosen)
        assertFalse(stored.gone)
        assertNotNull(tv.row(libA)?.heldUntilMs, "its programme on air keeps it held")
    }
}
