// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.domain.lineup.CuratedLibrary.todayStartMs
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The rebuild (#812) after the app's start refresh has already recorded the plan's changes for the next boundary: a
 * refresh at a time in the middle of today leaves new rules, a starter coming on, a starter leaving and a new curation
 * basis waiting for tomorrow, and the rebuild brings them to today. What the viewer did stays as it was.
 */
class LineupRebuildPendingTest {
    private val now = Instant.fromEpochMilliseconds(NOON.toEpochMilliseconds() + 2 * 3_600_000L)
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    // Catalog 3 changes Laughs' rules and adds a channel; it selects by identity.
    private val catalog =
        programmedCatalog(
            3,
            selects = true,
            change = {
                if (it.key == "laughs") it.copy(sources = listOf(Source.Genre("Comedy"), Source.Genre("Crime"))) else it
            },
            extra =
                listOf(
                    Recipe(
                        "crime-too",
                        "Crime Too",
                        "New in catalog 3.",
                        Band.Genre,
                        330,
                        optIn = false,
                        sources = listOf(Source.Genre("Crime"), Source.Genre("Comedy")),
                        exclusions = emptyList(),
                        maxRating = null,
                        ordering = OrderingMode.Curated,
                        collectionHints = emptyList(),
                    ),
                ),
        )

    // No westerns in the library, so the plan leaves Westerns out; enough horror that it picks Frights.
    private val library =
        CountingLibrary().apply {
            seed(
                CuratedLibrary.comedy + CuratedLibrary.crime + CuratedLibrary.comedyCrime + CuratedLibrary.horror +
                    CuratedLibrary.documentary + (0 until 8).map { movie(1600 + it, "Animation", rating = "G") } +
                    (0 until 8).map { movie(1700 + it, "Horror", rating = "R") },
            )
        }
    private val store = RecordingStore()
    private val service = LineupService(store, library, clock, { 0 }, {}, catalog)

    // The viewer hid Whodunit: hidden, and leaving at tomorrow's boundary.
    private val hidden =
        CuratedLibrary.lineup().let { base ->
            base.copy(
                catalogVersion = 2,
                channels =
                    base.channels.map {
                        if (it.id == CuratedLibrary.whodunitId) {
                            it.copy(
                                state = ChannelState.UserHidden,
                                viewerChoice = ViewerChoice.Touched,
                                leavesAtMs = boundaryMs,
                            )
                        } else {
                            it
                        }
                    },
            )
        }

    private fun refreshed(): Lineup =
        runBlocking {
            library.delegate.useAccount(ACCOUNT)
            store.inner.save(ACCOUNT, LineupCodec.encode(hidden))
            service.refresh(ACCOUNT, LIBRARIES)
            service.loaded(ACCOUNT)!!.lineup
        }

    private fun rebuilt(): Lineup =
        runBlocking {
            assertTrue(service.rebuildToday(ACCOUNT, LIBRARIES))
            service.loaded(ACCOUNT)!!.lineup
        }

    private fun Lineup.starter(key: String): LineupChannel = channels.first { it.id.value == "starter:$key" }

    private fun LineupChannel.rulesFrom(): Long? = (source as ChannelSource.Starter).rulesFromMs

    @Test
    fun aRefreshLeavesEveryChangeWaitingForTomorrow() {
        val waiting = refreshed()

        assertEquals(boundaryMs, waiting.starter("laughs").rulesFrom(), "new rules")
        assertEquals(boundaryMs, waiting.starter("frights").onSinceMs, "a starter coming on")
        assertEquals(boundaryMs, waiting.starter("westerns").leavesAtMs, "a starter leaving")
        assertEquals(boundaryMs, waiting.curationBasis?.fromMs, "the basis")
    }

    @Test
    fun theRebuildAppliesTheNewRulesToday() {
        refreshed()

        val today = rebuilt()

        val laughs = today.starter("laughs").source as ChannelSource.Starter
        assertNull(laughs.previousRules, "no old rules left to air today")
        assertNull(laughs.rulesFromMs)
        assertTrue(laughs.rules.sources.contains(Source.Genre("Crime")), "the catalog's rules are the ones in force")
    }

    @Test
    fun aNewlyPickedStarterIsOnToday() {
        refreshed()

        val frights = rebuilt().starter("frights")

        assertEquals(ChannelState.On, frights.state)
        assertTrue(frights.onSinceMs <= todayStartMs, "on since the start of today, not tomorrow: ${frights.onSinceMs}")
    }

    @Test
    fun aStarterThePlanShelvesLeavesToday() {
        refreshed()

        val today = rebuilt()

        val westerns = today.starter("westerns")
        assertEquals(ChannelState.Shelf, westerns.state, "off the air now")
        assertNull(westerns.leavesAtMs)
        assertNull(today.curationBasis, "and nothing is held back for tomorrow")
    }

    @Test
    fun aViewerSwitchOffIsStillInPlaceAndUnchanged() {
        val before = refreshed().starter("whodunit")
        assertEquals(ChannelState.UserHidden, before.state)

        val after = rebuilt().starter("whodunit")

        // The viewer's part: the switch-off, its mark, its leaving time and its number. (The rules are the plan's.)
        assertEquals(ChannelState.UserHidden, after.state)
        assertEquals(before.viewerChoice, after.viewerChoice)
        assertEquals(before.leavesAtMs, after.leavesAtMs)
        assertEquals(before.number, after.number)
        assertNotNull(after.leavesAtMs)
        assertEquals(boundaryMs, after.leavesAtMs, "it still leaves at tomorrow's boundary, as the viewer left it")
    }
}
