// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A reset keeps the viewer's own channels and re-plans the starters around them (spec 22, rulings 18, 23 and 24). */
class LineupServiceResetKeepsCustomTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    private fun custom(
        name: String,
        number: Int?,
        state: ChannelState = ChannelState.On,
        id: String = "custom:$name",
        onSinceMs: Long = 4_321L,
    ) = channel(id, null, state, ChannelSource.Custom(rules(name, Source.Genre("Comedy"))), onSinceMs)
        .copy(numberOverride = number)

    private fun starters() =
        arrayOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
        )

    private suspend fun seedStored(lineup: Lineup) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
        store.calls.clear()
    }

    private fun stored(): Lineup? {
        val bytes = store.inner.peek(ACCOUNT) ?: return null
        return (LineupCodec.decode(bytes) as? LineupCodec.Decoded.Loaded)?.lineup
    }

    private suspend fun reset(): Lineup = assertIs<Saved.Ok<Lineup>>(service.reset(ACCOUNT, LIBRARIES)).value

    private fun Lineup.byId(id: String): LineupChannel = channels.first { it.id.value == id }

    @Test
    fun resetKeepsTheViewersOwnChannels() =
        runBlocking<Unit> {
            val mine = custom("Mine", 600, onSinceMs = 111L)
            val hidden = custom("Hidden", 601, ChannelState.UserHidden, onSinceMs = 222L)
            seedStored(lineupOf(*starters(), mine, hidden))

            val fresh = reset()

            assertEquals(mine, fresh.byId("custom:Mine"), "rules, state, number and onSinceMs as they were")
            assertEquals(hidden, fresh.byId("custom:Hidden"))
            assertEquals(fresh, stored(), "and saved")
            assertTrue(fresh.channels.any { it.id == LineupIds.starter("laughs") }, "the starters are planned afresh")
        }

    @Test
    fun aStarterWhoseDefaultCollidesWithAKeptChannelTakesTheFirstFreeNumberInItsBand() =
        runBlocking<Unit> {
            // The viewer moved Laughs away and put a channel of their own on its number.
            val moved = starters().first().copy(numberOverride = 150)
            seedStored(lineupOf(moved, starters()[1], custom("Mine", 110)))

            val fresh = reset()

            assertEquals(110, fresh.byId("custom:Mine").number)
            val laughs = fresh.channels.first { it.id == LineupIds.starter("laughs") }
            assertEquals(100, laughs.number)
            assertEquals(ChannelState.On, laughs.state)
            assertEquals(
                fresh.channels.size,
                fresh.channels
                    .mapNotNull { it.number }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun resetWithKeptChannelsStaysWithinTheCap() =
        runBlocking<Unit> {
            val mine = (0 until 48).map { custom("M$it", 600 + it) }
            seedStored(lineupOf(*starters(), *mine.toTypedArray()))

            val fresh = reset()

            val on = fresh.channels.filter { it.state == ChannelState.On }
            assertEquals(LineupDefaults.CAP, on.size, "48 kept and the 2 leftovers fill the cap")
            assertTrue(mine.all { fresh.byId(it.id.value).state == ChannelState.On }, "kept all On")
            assertEquals(0, on.count { it.source is ChannelSource.Starter })
        }

    @Test
    fun resetKeepsTheBestRankedStartersThatFitUnderTheCap() =
        runBlocking<Unit> {
            val mine = (0 until 46).map { custom("M$it", 600 + it) }
            seedStored(lineupOf(*starters(), *mine.toTypedArray()))

            val fresh = reset()

            val on = fresh.channels.filter { it.state == ChannelState.On }
            assertEquals(LineupDefaults.CAP, on.size)
            // Every recipe fills equally, so the cut is by key: the first two of the six.
            val starters = on.filter { it.source is ChannelSource.Starter }.map { it.id }.toSet()
            assertEquals(setOf(LineupIds.starter("frights"), LineupIds.starter("laughs")), starters)
        }

    @Test
    fun anUnfinalisedLineupKeepsTheViewersChannelsWhenItIsPlannedAgain() =
        runBlocking<Unit> {
            val bare = CountingLibrary()
            val early = LineupService(store, bare, clock, { 0 }, {}, TestCatalog)
            seedStored(lineupOf(custom("Mine", 600)))
            ChannelManager(early) { LIBRARIES }.reset(ACCOUNT)
            bare.seed(fillingLibrary())

            early.refresh(ACCOUNT, LIBRARIES)

            val after = checkNotNull(early.lineup(ACCOUNT).value).lineup
            assertTrue(after.finalised)
            assertEquals(600, after.byId("custom:Mine").number)
        }

    @Test
    fun resetKeepsARetiredStarterThatBecameCustom() =
        runBlocking<Unit> {
            val retired = custom("Retired", null, id = "starter:oldie").copy(defaultNumber = 130, numberOverride = null)
            seedStored(lineupOf(*starters(), retired))

            val fresh = reset()

            assertEquals(retired, fresh.byId("starter:oldie"))
        }

    @Test
    fun aStarterWhoseBandIsFullIsShelved() =
        runBlocking<Unit> {
            val band = Band.General.numbers.map { custom("G$it", it, ChannelState.UserHidden) }
            val whodunit = starter("whodunit", 210, ChannelState.On, rules("W", Source.Genre("Crime")))
            seedStored(lineupOf(whodunit, *band.toTypedArray()))

            val fresh = reset()

            val laughs = fresh.channels.first { it.id == LineupIds.starter("laughs") }
            assertEquals(ChannelState.Shelf, laughs.state)
            assertNull(laughs.number)
            assertTrue(band.all { fresh.byId(it.id.value) == it })
        }

    @Test
    fun aKidsCustomChannelStaysInTheKidsBandThroughAReset() =
        runBlocking<Unit> {
            val mine = custom("Kids", 10)
            seedStored(lineupOf(*starters(), mine))

            val fresh = reset()

            val kept = fresh.byId("custom:Kids")
            assertEquals(10, kept.number)
            assertTrue(KidStatus.isKidChannel(kept))
            val toons = fresh.channels.first { it.id == LineupIds.starter("toons") }
            assertTrue(toons.number != null && toons.number in Band.Kids.numbers && toons.number != 10)
        }

    @Test
    fun resetAfterAnUnreadableFileSetAsideKeepsNothing() =
        runBlocking<Unit> {
            store.inner.save(ACCOUNT, "not a lineup".encodeToByteArray())

            val fresh = reset()

            assertTrue(fresh.channels.none { it.source is ChannelSource.Custom })
            assertNotNull(store.inner.setAsideCopy(ACCOUNT), "the unreadable file was set aside, not lost")
        }

    @Test
    fun resetAfterTheStoredFileBecameUnreadableKeepsTheSessionsCustomChannels() =
        runBlocking<Unit> {
            seedStored(lineupOf(*starters(), custom("Mine", 600)))
            service.loaded(ACCOUNT)
            // Another writer, or a fault, leaves the file unreadable after this session has loaded it.
            store.inner.save(ACCOUNT, "not a lineup".encodeToByteArray())

            val fresh = reset()

            val mine = fresh.byId("custom:Mine")
            assertEquals(custom("Mine", 600), mine, "the session's channel is kept, as an edit would")
            assertNotNull(store.inner.setAsideCopy(ACCOUNT), "and the unreadable file was set aside, not lost")
        }

    @Test
    fun resetKeepsAnUnusableCustomChannelByteForByte() =
        runBlocking<Unit> {
            val newer = """{"formatVersion":4,"seed":"1","sources":[{"type":"genre","name":"Comedy"}]}"""
            val rawCustom = """{"kind":"custom","rules":${jsonString(newer)}}"""
            val rawStarter = """{"kind":"starter","recipeKey":"frights","edited":false,"rules":${jsonString(newer)}}"""
            val file =
                lineupOf(
                    *starters(),
                    channel("custom:Newer", 600, ChannelState.On, ChannelSource.Unusable(rawCustom)),
                    channel("starter:frights", 310, ChannelState.On, ChannelSource.Unusable(rawStarter)),
                )
            seedStored(file)
            val before = checkNotNull(stored())
            val unusable = assertIs<ChannelSource.Unusable>(before.byId("custom:Newer").source)

            val fresh = reset()

            assertEquals(before.byId("custom:Newer"), fresh.byId("custom:Newer"))
            assertEquals(unusable, fresh.byId("custom:Newer").source)
            val replanned = fresh.byId("starter:frights").source
            assertIs<ChannelSource.Starter>(replanned, "an unusable starter is planned afresh")
            assertTrue(
                store.inner
                    .peek(ACCOUNT)!!
                    .decodeToString()
                    .contains(unusable.rawJson),
                "written as it was",
            )
        }

    @Test
    fun resetUsesTheStoredLineupNotAnUnwritableMemoryOne() =
        runBlocking<Unit> {
            seedStored(lineupOf(*starters(), custom("Old", 600)))
            service.loaded(ACCOUNT)
            // The file cannot be read for one refresh: the session plans in memory and may not write.
            store.inner.failReads = true
            service.refresh(ACCOUNT, LIBRARIES)
            store.inner.failReads = false
            assertFalse(checkNotNull(service.lineup(ACCOUNT).value).writable)
            // Meanwhile the file holds another lineup (another TV wrote it).
            store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*starters(), custom("Stored", 610))))

            val fresh = reset()

            assertNotNull(fresh.channels.firstOrNull { it.id == ChannelId("custom:Stored") })
            assertNull(fresh.channels.firstOrNull { it.id == ChannelId("custom:Old") })
        }

    @Test
    fun resetStillNeverPublishesNoLineup() =
        runBlocking<Unit> {
            seedStored(lineupOf(*starters(), custom("Mine", 600)))
            service.loaded(ACCOUNT)
            val seen = mutableListOf<Revisioned?>()
            val watcher = launch { service.lineup(ACCOUNT).collect { seen += it } }
            delay(50)

            val fresh = service.reset(ACCOUNT, LIBRARIES)
            delay(50)
            watcher.cancel()

            assertIs<Saved.Ok<Lineup>>(fresh)
            assertTrue(seen.isNotEmpty() && seen.none { it == null }, "saw $seen")
            assertEquals(fresh.value, service.lineup(ACCOUNT).value?.lineup)
        }

    @Test
    fun resetWithOnlyCustomChannelsReportsChosen() =
        runBlocking<Unit> {
            // A library that fills no recipe: the planner has nothing to switch on but the viewer's channel does air.
            val bare = LineupService(store, CountingLibrary(), clock, { 0 }, {}, TestCatalog)
            seedStored(lineupOf(custom("Mine", 600)))

            val result = ChannelManager(bare) { LIBRARIES }.reset(ACCOUNT)

            assertEquals(ResetResult.Chosen, result)
            val after = checkNotNull(bare.lineup(ACCOUNT).first { it != null }).lineup
            assertFalse(after.finalised)
            assertEquals(listOf("custom:Mine"), after.channels.map { it.id.value })
        }

    private fun jsonString(text: String) =
        kotlinx.serialization.json
            .JsonPrimitive(text)
            .toString()
}
