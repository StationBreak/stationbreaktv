// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Whether a channel has started is decided by the day, never by the moment a day is asked for (Task 21 fix round 3):
 * every lookup of one day, at any moment of it, gives the same day, so a viewer's switch-on in the middle of a day is
 * never kept as an empty day under that day's key, in memory or on disk.
 */
class StartingDayTest {
    private val lib = CuratedLibrary
    private val noon = NOON.toEpochMilliseconds()

    // The half-hour origin the guide asks at, and the moment the viewer switched on (between the two).
    private val origin = noon
    private val now = noon + 20 * 60_000L
    private val customId = ChannelId("custom-2")
    private val switched = setOf(lib.westernsId, customId)

    private fun before(): Lineup {
        val source = ChannelSource.Custom(rules("Mine", Source.Genre("Western")))
        val custom = channel("custom-2", 610, ChannelState.Shelf, source)
        val lineup = lib.lineup(westernsState = ChannelState.Shelf)
        return lineup.copy(channels = lineup.channels + custom)
    }

    // The viewer switches the curated starter and the custom channel on at [now].
    private fun switchedOn(): Lineup =
        switched.fold(before()) { lineup, id ->
            val outcome = LineupEdits.apply(lineup, LineupEdit.SwitchOn(id), now, boundaryMs, TestCatalog)
            assertIs<EditOutcome.Applied>(outcome).lineup
        }

    @Test
    fun aDayAskedForBeforeAMidDaySwitchOnIsTheDayAskedForAfterIt() {
        val lineup = switchedOn()
        val days = LineupDays(TestCatalog)

        val atOrigin = days.curatedDays(lineup, origin)
        val atNow = days.curatedDays(lineup, now)

        assertEquals(atNow.map { it.channel.id }, atOrigin.map { it.channel.id })
        for (day in atNow) assertEquals(day.aired(), atOrigin.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        for (id in switched) assertTrue(atOrigin.dayOf(id.value).pool.isNotEmpty(), "$id airs from its switch-on")
        // Both lookups have one key, so either is what the other reuses.
        assertEquals(days.scheduled(lineup, origin, null).map { it.n }, days.scheduled(lineup, now, null).map { it.n })
    }

    @Test
    fun theDaysFileOfALookupAtTheOriginIsTheDayAtNow() {
        val lineup = switchedOn()
        val days = LineupDays(TestCatalog)
        val store = MemoryDayStore()
        val persisted = PersistedDays(store, "app-1", {}, PersistedDays.HOLDS)
        val atOrigin = days.scheduled(lineup, origin, null)
        val atNow = days.scheduled(lineup, now, null)
        val inputs = days.inputsOf(lineup, null)
        val key = persisted.keyOf(ACCOUNT, LIBRARIES, 1L, 0, inputs, 1, atOrigin)
        assertEquals(key, persisted.keyOf(ACCOUNT, LIBRARIES, 1L, 0, inputs, 1, atNow))

        assertTrue(runBlocking { persisted.save(ACCOUNT, key, days.curatedDays(lineup, origin)) })
        val loaded = assertNotNull(runBlocking { persisted.load(ACCOUNT, key, atNow, days::restore) })

        assertEquals(describe(days.curatedDays(lineup, now)), describe(loaded))
        for (id in switched) assertTrue(loaded.dayOf(id.value).pool.isNotEmpty())
    }

    @Test
    fun theScheduleSourceGivesTheSameDayAtTheOriginAndAtNow() =
        runBlocking<Unit> {
            val library = CountingLibrary().apply { seed(lib.items) }
            val store = RecordingStore()
            store.inner.save(ACCOUNT, LineupCodec.encode(before()))
            var nowMs = origin
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
                }
            val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
            nowMs = now
            val manager = ChannelManager(service) { null }
            for (id in switched) assertEquals(ChangeResult.Done, manager.change(ACCOUNT, ChannelChange.SwitchOn(id)))
            val source = BuiltInScheduleSource(service, library) { selectionOf(LIBRARIES) }

            // The guide's lookup at the half-hour origin first, so its day is the one kept for the key.
            val first = source.channelDays(ACCOUNT, LIBRARIES, Instant.fromEpochMilliseconds(origin))
            val second = source.channelDays(ACCOUNT, LIBRARIES, Instant.fromEpochMilliseconds(now))

            for (id in switched) {
                assertTrue(first.dayOf(id.value).pool.isNotEmpty(), "$id at the origin")
                assertEquals(first.dayOf(id.value).aired(), second.dayOf(id.value).aired())
            }
        }

    // The leftovers claim from the curated starters' own day: the shipped catalog's starters and leftovers share one
    // timing, so they share the cut and the curation (the build reports, and does not fail, when they do not).
    @Test
    fun theShippedTimingsGiveTheLeftoversTheStartersDay() {
        val days = LineupDays(StarterCatalog)
        val lineup =
            tv.stationbreak.core.engine.lineup.LineupPlanner.plan(
                null,
                SERVER,
                USER,
                0,
                tv.stationbreak.core.model.RuleSnapshot(
                    noon,
                    setOf(LIBRARY_ID),
                    fillingLibrary().sortedBy { it.item.id.value },
                    emptyMap(),
                    emptyMap(),
                    collectionsSynced = true,
                ),
                tv.stationbreak.core.model
                    .CollectionNames(emptyMap()),
                noon,
                boundaryMs,
                object : tv.stationbreak.core.engine.lineup.StarterCatalogView by StarterCatalog {
                    override val recipes =
                        StarterCatalog.recipes.map { it.copy(ordering = OrderingMode.Curated) }
                },
            )
        val scheduled = days.scheduled(lineup, noon, null)
        val starters = scheduled.filter { it.curation != null && it.channel.source is ChannelSource.Starter }
        val leftovers = scheduled.filter { it.channel.source is ChannelSource.Leftovers }
        assertTrue(starters.isNotEmpty() && leftovers.isNotEmpty())
        for (l in leftovers) {
            assertEquals(starters.map { it.cut }.toSet(), setOf(l.cut))
            assertEquals(starters.map { it.curation }.toSet(), setOf(l.curation))
        }
    }

    // A leftovers channel given another curation than its starters' (not a shipped timing) is reported, claims from
    // the starters' one, and the build goes on.
    @Test
    fun aLeftoversDayThatIsNotTheStartersIsReportedNotThrown() {
        var reports = 0
        val days = LineupDays(TestCatalog, claimsMismatch = { reports++ })
        val lineup = lib.lineup()
        val scheduled = days.scheduled(lineup, noon, null)
        val wrong =
            scheduled.map {
                if (it.channel.source is ChannelSource.Leftovers) {
                    Scheduled(it.channel, it.settings, it.n, it.extras, DayCuration(emptyList(), true, basis = false))
                } else {
                    it
                }
            }
        val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { lib.snapshotAt(it) }

        val built = days.days(lineup, wrong, snapshots)

        assertEquals(1, reports)
        assertEquals(
            days.days(lineup, scheduled, snapshots).dayOf("leftovers:odds-and-ends").pool,
            built.dayOf("leftovers:odds-and-ends").pool,
        )
    }
}
