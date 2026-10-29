// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The built days kept on disk (#480): a file gives back exactly the days it was written from, and only while every
 * input of those days is as it was. Anything else (another key, a torn or corrupt file, a format it does not know) is
 * a miss, and the caller builds as if there were no file.
 */
class PersistedDaysTest {
    private val lineupDays = LineupDays(TestCatalog)
    private val store = MemoryDayStore()
    private val events = mutableListOf<DaySnapshotEvent>()

    private fun persisted(
        app: String = APP,
        holds: List<String> = PersistedDays.HOLDS,
    ) = PersistedDays(store, app, { events += it }, holds)

    private val lineup =
        lineupOf(
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("frights", 310, ChannelState.UserHidden, rules("Frights", Source.Genre("Horror"))),
            channel(
                "custom-1",
                401,
                ChannelState.On,
                tv.stationbreak.core.engine.lineup.ChannelSource.Custom(
                    rules("Late", Source.Genre("Western"), timing = timing(start = "12:00")),
                ),
            ),
            // Nothing matches it: its day is empty, with a reason.
            channel(
                "custom-2",
                402,
                ChannelState.On,
                tv.stationbreak.core.engine.lineup.ChannelSource
                    .Custom(rules("Empty", Source.Genre("Opera"))),
            ),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
        )

    /** The filling library, an episode of a series (with a premiere date) and a deleted-later movie. */
    private val items: List<RuleItem> =
        fillingLibrary() +
            RuleItem(
                ScheduleItem(
                    id(700),
                    ScheduleItemKind.Episode,
                    1_800_000L,
                    ADDED_MS,
                    null,
                    "2001-02-03",
                    2001,
                    id(701),
                    1,
                    2,
                ),
                LIBRARY_ID,
                null,
                "TV-14",
                listOf("Comedy"),
                emptyList(),
            ) +
            movie(702, "Crime", rating = "R").let { it.copy(item = it.item.copy(deletedAtMs = NOON_MS + DAY_MS)) }

    private fun scheduledAt(
        atMs: Long,
        of: Lineup = lineup,
    ) = lineupDays.scheduled(of, atMs)

    private fun built(scheduled: List<Scheduled>): List<ChannelDay> {
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                RuleSnapshot(it, setOf(LIBRARY_ID), items, emptyMap(), emptyMap(), collectionsSynced = true)
            }
        return lineupDays.days(lineup, scheduled, snapshots)
    }

    @Suppress("LongParameterList")
    private fun keyOf(
        days: PersistedDays,
        scheduled: List<Scheduled> = scheduledAt(NOON_MS),
        account: LibraryAccount = ACCOUNT,
        libraries: Set<ItemId> = LIBRARIES,
        generation: Long = GENERATION,
        pass: Instant? = PASS,
        of: Lineup = lineup,
        catalog: Int = TestCatalog.version,
    ) = days.keyOf(account, libraries, generation, pass, of, catalog, scheduled)

    private fun save(
        days: PersistedDays = persisted(),
        key: DayKey = keyOf(days),
        scheduled: List<Scheduled> = scheduledAt(NOON_MS),
    ) = runBlocking { days.save(ACCOUNT, key, built(scheduled)) }

    private fun load(
        days: PersistedDays = persisted(),
        key: DayKey = keyOf(days),
        scheduled: List<Scheduled> = scheduledAt(NOON_MS),
        account: LibraryAccount = ACCOUNT,
    ) = runBlocking { days.load(account, key, scheduled, lineupDays::restore) }

    private fun lastMiss() = (events.last() as DaySnapshotEvent.Miss).reason

    @Test
    fun aSavedDayReadsBackAsExactlyTheDaysItWasWrittenFrom() {
        val scheduled = scheduledAt(NOON_MS)
        assertTrue(scheduled.map { it.cutoffMs }.distinct().size >= 2, "two cut-offs are in play")
        assertTrue(save())

        val loaded = assertNotNull(load())
        assertEquals(describe(built(scheduled)), describe(loaded))
        assertTrue(loaded.any { it.hidden != null }, "an empty day keeps its reason")
        assertTrue(loaded.any { day -> day.pool.any { it.seriesId != null } }, "an episode keeps its series")
        assertTrue(events.first() is DaySnapshotEvent.Saved && events.last() is DaySnapshotEvent.Hit)
    }

    @Test
    fun noFileIsAMiss() {
        assertNull(load())
        assertEquals(DaySnapshotMiss.Absent, lastMiss())
    }

    @Test
    fun anotherAppBuildMisses() {
        save()
        val updated = persisted(app = "2:$INSTALLED")
        assertNull(load(updated, keyOf(updated)))
        assertEquals(DaySnapshotMiss.App, lastMiss())
    }

    @Test
    fun aReinstallOfTheSameVersionMisses() {
        save()
        val reinstalled = persisted(app = "1:${INSTALLED + 1}")
        assertNull(load(reinstalled, keyOf(reinstalled)))
        assertEquals(DaySnapshotMiss.App, lastMiss())
    }

    @Test
    fun anotherHoldVersionMisses() {
        save()
        val held = persisted(holds = listOf("kid-hold-3", "grown-up-hold-1"))
        assertNull(load(held, keyOf(held)))
        assertEquals(DaySnapshotMiss.Holds, lastMiss())
    }

    @Test
    fun anotherAccountFindsNoFileAndAForeignHeaderMisses() {
        save()
        val other = LibraryAccount(ServerId(SERVER.value), UserId("e".repeat(32)))
        val days = persisted()
        assertNull(load(days, keyOf(days, account = other), account = other))
        assertEquals(DaySnapshotMiss.Absent, lastMiss())

        // Were another account's file ever found under this one, its header still tells.
        assertNull(load(days, keyOf(days, account = other)))
        assertEquals(DaySnapshotMiss.Account, lastMiss())
    }

    @Test
    fun otherLibrariesMiss() {
        save()
        val days = persisted()
        assertNull(load(days, keyOf(days, libraries = setOf(ItemId("d".repeat(32))))))
        assertEquals(DaySnapshotMiss.Libraries, lastMiss())
    }

    @Test
    fun aLibraryChangeMisses() {
        save()
        val days = persisted()
        assertNull(load(days, keyOf(days, generation = GENERATION + 1)))
        assertEquals(DaySnapshotMiss.Generation, lastMiss())
    }

    @Test
    fun aLaterCollectionPassMisses() {
        save()
        val days = persisted()
        assertNull(load(days, keyOf(days, pass = Instant.parse("2026-10-19T11:00:00Z"))))
        assertEquals(DaySnapshotMiss.CollectionPass, lastMiss())
        assertNull(load(days, keyOf(days, pass = null)))
        assertEquals(DaySnapshotMiss.CollectionPass, lastMiss())
    }

    @Test
    fun anotherCatalogMisses() {
        save()
        val days = persisted()
        assertNull(load(days, keyOf(days, catalog = TestCatalog.version + 1)))
        assertEquals(DaySnapshotMiss.Catalog, lastMiss())
    }

    @Test
    fun aChangedChannelRuleMisses() {
        save()
        val days = persisted()
        val edited =
            lineup.copy(
                channels =
                    lineup.channels.map {
                        if (it.number == 110) {
                            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Drama")))
                        } else {
                            it
                        }
                    },
            )
        assertNull(load(days, keyOf(days, of = edited, scheduled = scheduledAt(NOON_MS, edited))))
        assertEquals(DaySnapshotMiss.Lineup, lastMiss())
    }

    @Test
    fun anotherUtcOffsetMisses() {
        save()
        val days = persisted()
        val moved = lineup.copy(utcOffsetMinutes = 60)
        assertNull(load(days, keyOf(days, of = moved)))
        assertEquals(DaySnapshotMiss.Offset, lastMiss())
    }

    @Test
    fun theNextDayHasItsOwnFile() {
        save()
        val days = persisted()
        val tomorrow = scheduledAt(NOON_MS + DAY_MS)
        assertNull(load(days, keyOf(days, scheduled = tomorrow), tomorrow))
        assertEquals(DaySnapshotMiss.Absent, lastMiss())
    }

    @Test
    fun aHeaderForOtherCutoffsOrDaysMisses() {
        save()
        val days = persisted()
        val key = keyOf(days)
        // A file found under the name of other cut-offs (it was moved, say) still tells by its header.
        val shifted = key.copy(cutoffs = key.cutoffs.map { it + 1 })
        store.files[ACCOUNT to PersistedDays.nameOf(shifted)] = store.only().second
        assertNull(load(days, shifted))
        assertEquals(DaySnapshotMiss.Cutoffs, lastMiss())
        assertNull(load(days, key.copy(days = key.days.map { it + 1 })))
        assertEquals(DaySnapshotMiss.Channels, lastMiss())
        assertNull(load(days, key.copy(channels = key.channels.reversed())))
        assertEquals(DaySnapshotMiss.Channels, lastMiss())
    }

    @Test
    fun anotherFormatMisses() {
        save()
        val (where, bytes) = store.only()
        val text = bytes.decodeToString()
        assertTrue("\"format\":${DaySnapshotCodec.VERSION}" in text)
        store.files[where] = text.replace("\"format\":${DaySnapshotCodec.VERSION}", "\"format\":99").encodeToByteArray()
        assertNull(load())
        assertEquals(DaySnapshotMiss.Format, lastMiss())
    }

    @Test
    fun aTornFileMisses() {
        save()
        val (where, bytes) = store.only()
        for (length in listOf(0, 10, bytes.size / 3, bytes.size / 2, bytes.size - 2)) {
            store.files[where] = bytes.copyOf(length)
            assertNull(load(), "cut to $length bytes")
            assertEquals(DaySnapshotMiss.Unreadable, lastMiss())
        }
    }

    @Test
    fun aCorruptFileMisses() {
        save()
        val (where, bytes) = store.only()
        val header = bytes.decodeToString().substringBefore('\n')
        // As many days as are scheduled, each naming a row the (empty) table lacks.
        val days = { day: String -> List(scheduledAt(NOON_MS).size) { day }.joinToString(",") }
        val corrupt =
            listOf(
                "not json at all",
                "$header\n{\"series\":[],\"items\":[],\"days\":[]}",
                "$header\n{\"series\":[],\"items\":[],\"days\":[${days("{\"rows\":[3]}")}]}",
                "$header\n{\"series\":[],\"items\":[],\"days\":[${days("{\"bits\":\"AQ==\"}")}]}",
                "$header\n{\"unknown\":1}",
                "{\"format\":1}\n{}",
            )
        for (text in corrupt) {
            store.files[where] = text.encodeToByteArray()
            assertNull(load(), text.take(40))
            assertEquals(DaySnapshotMiss.Unreadable, lastMiss())
        }
    }

    @Test
    fun aFailedReadMisses() {
        save()
        store.failReads = true
        assertNull(load())
        assertEquals(DaySnapshotMiss.Unreadable, lastMiss())
    }

    @Test
    fun aFailedWriteIsReportedAndKeepsNothing() {
        store.failWrites = true
        assertTrue(!save())
        assertEquals(DaySnapshotEvent.SaveFailed, events.last())
        assertTrue(store.files.isEmpty())
    }

    private fun nameAt(atMs: Long) =
        scheduledAt(atMs)
            .map { it.cutoffMs }
            .distinct()
            .sorted()
            .joinToString("_")

    private fun saveAt(
        days: PersistedDays,
        atMs: Long,
    ) {
        val scheduled = scheduledAt(atMs)
        save(days, keyOf(days, scheduled = scheduled), scheduled)
    }

    @Test
    fun onlyTheLatestThreeSetsOfCutoffsAreKept() {
        val days = persisted()
        val stray = LibraryAccount(ServerId("f".repeat(32)), UserId(USER.value))
        runBlocking { store.write(stray, "1_2", byteArrayOf(1)) }
        runBlocking { store.write(ACCOUNT, "not-a-day", byteArrayOf(1)) }
        for (day in 0L..4L) saveAt(days, NOON_MS + day * DAY_MS)

        val wanted = listOf(2L, 3L, 4L).map { nameAt(NOON_MS + it * DAY_MS) }.toSet()
        assertEquals(wanted, runBlocking { store.names(ACCOUNT) }.toSet())
        assertEquals(listOf("1_2"), runBlocking { store.names(stray) }, "another account's files are its own")
    }

    @Test
    fun withTwoDayStartsTheCurrentDayOutlivesBothOfTheNextOnes() {
        // At 11:30 the noon channel is still on yesterday's day; ahead of it are the noon change and the 06:00 one.
        val days = persisted()
        val current = NOON_MS - HALF_HOUR_MS
        val noon = NOON_MS
        val morning = NOON_MS + 18 * HOUR_MS
        assertEquals(3, setOf(nameAt(current), nameAt(noon), nameAt(morning)).size)
        for (at in listOf(current, noon, morning)) saveAt(days, at)

        assertNotNull(load(scheduled = scheduledAt(current), key = keyOf(days, scheduled = scheduledAt(current))))
        assertEquals(3, runBlocking { store.names(ACCOUNT) }.size)
    }

    @Test
    fun theFileHoldsNoNamesOrTitles() {
        save()
        val text = store.only().second.decodeToString()
        for (word in listOf("Toons", "Laughs", "Comedy", "Animation", "PG", "TV-14", "Odds")) {
            assertTrue(word !in text, "$word is not in the file")
        }
    }

    private companion object {
        val NOON_MS = NOON.toEpochMilliseconds()
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
        const val HALF_HOUR_MS = 1_800_000L
        const val GENERATION = 7L
        const val INSTALLED = 1_767_225_600_000L
        val APP = "1:$INSTALLED"
        val PASS: Instant = Instant.parse("2026-10-19T10:00:00Z")
    }
}

/** Every fact of the days, the laid-out schedule included: equal lists are the same days. */
internal fun describe(days: List<ChannelDay>) =
    days.map {
        listOf(
            it.channel,
            it.name,
            it.settings,
            it.n,
            it.epochStartMs,
            it.pool,
            it.hidden,
            it.ceilingRank,
            it.day.blocks,
        )
    }
