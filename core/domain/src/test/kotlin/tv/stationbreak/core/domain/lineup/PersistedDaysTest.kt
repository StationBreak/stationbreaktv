// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.DayStore
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
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        of: Lineup = lineup,
        catalog: Int = TestCatalog.version,
    ) = days.keyOf(account, libraries, generation, of, lineupDays.inputsOf(of), catalog, scheduled)

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

    /** The saved file's header and body, as text. */
    private fun savedParts(): Pair<String, String> {
        val text = store.only().second.decodeToString()
        return text.substringBefore('\n') to text.substringAfter('\n')
    }

    /** A file of [body] under the saved header, its checksum made to match, so that the body itself is what is read. */
    private fun forged(
        body: String,
        header: String = savedParts().first,
    ): ByteArray {
        // The checksum covers the key's JSON (the end of the header line) and then the body.
        val key = header.substringAfter("\"key\":").dropLast(1)
        val crc =
            CRC32()
                .apply {
                    update(key.encodeToByteArray())
                    update(body.encodeToByteArray())
                }.value
        return "${header.replace(Regex("\"crc\":[0-9]+"), "\"crc\":$crc")}\n$body".encodeToByteArray()
    }

    @Test
    fun aFlippedDigitInTheKeyFailsTheChecksumRatherThanMissingAsAnotherKey() {
        save()
        val (where, _) = store.only()
        val (header, body) = savedParts()
        val generation = "\"generation\":$GENERATION"
        assertTrue(generation in header)
        val flipped = header.replace(generation, "\"generation\":${GENERATION + 1}")
        store.files[where] = "$flipped\n$body".encodeToByteArray()
        assertNull(load())
        assertEquals(DaySnapshotMiss.Unreadable, lastMiss(), "corruption, not a library change")
        // With a checksum made for it, the same header is read, and is another generation.
        store.files[where] = forged(body, flipped)
        assertNull(load())
        assertEquals(DaySnapshotMiss.Generation, lastMiss())
    }

    @Test
    fun aFlippedDigitOrBitFailsTheChecksumAndMisses() {
        save()
        val (where, _) = store.only()
        val (header, body) = savedParts()
        // A runtime digit, a date digit and a character of a pool's bitset: each still decodes, as another schedule.
        val runtime =
            Regex("\"r\":([0-9])")
                .find(body)!!
                .groups[1]!!
                .range.first
        val date = body.indexOf("\"p\":\"2001") + "\"p\":\"200".length
        val bits = body.indexOf("\"bits\":\"") + "\"bits\":\"".length
        for (at in listOf(runtime, date, bits)) {
            val flipped = body.toCharArray().also { it[at] = if (it[at] == '1') '2' else '1' }.concatToString()
            assertTrue(flipped != body)
            store.files[where] = "$header\n$flipped".encodeToByteArray()
            assertNull(load(), "flipped at $at")
            assertEquals(DaySnapshotMiss.Unreadable, lastMiss())
            // The same change with a checksum to match is read: it is the checksum that refused it.
            store.files[where] = forged(flipped)
            assertNotNull(load(), "forged at $at")
        }
    }

    @Test
    fun aCorruptFileMisses() {
        save()
        val (where, _) = store.only()
        val header = savedParts().first
        // As many days as are scheduled (or one more), each one way or another naming what the table lacks.
        val count = scheduledAt(NOON_MS).size
        val days = { day: String, n: Int -> List(n) { day }.joinToString(",") }
        val oneItem = "{\"series\":[],\"items\":[{\"i\":\"${"a".repeat(32)}\",\"r\":60000,\"c\":0}],"
        val corrupt =
            listOf(
                "not json at all".encodeToByteArray(),
                "{\"format\":${DaySnapshotCodec.VERSION}}\n{}".encodeToByteArray(),
                "$header\n{\"series\":[],\"items\":[],\"days\":[]}".encodeToByteArray(),
                forged("{\"series\":[],\"items\":[],\"days\":[]}"),
                forged("{\"unknown\":1}"),
                // A negative row, and a row past the end.
                forged("$oneItem\"days\":[${days("{\"rows\":[-1]}", count)}]}"),
                forged("$oneItem\"days\":[${days("{\"rows\":[1]}", count)}]}"),
                // A bitset longer than the table (bit 8 of one item), and Base64 that is not Base64.
                forged("$oneItem\"days\":[${days("{\"bits\":\"AAE=\"}", count)}]}"),
                forged("$oneItem\"days\":[${days("{\"bits\":\"!!not base64!!\"}", count)}]}"),
                // One day more than are scheduled, and a day written both ways.
                forged("$oneItem\"days\":[${days("{\"rows\":[0]}", count + 1)}]}"),
                forged("$oneItem\"days\":[${days("{\"rows\":[0],\"bits\":\"AQ==\"}", count)}]}"),
                // An item of no known kind, and a series index the table lacks.
                forged(
                    "{\"series\":[],\"items\":[{\"i\":\"${"a".repeat(32)}\",\"k\":7,\"r\":1,\"c\":0}]," +
                        "\"days\":[${days("{\"rows\":[0]}", count)}]}",
                ),
                forged(
                    "{\"series\":[],\"items\":[{\"i\":\"${"a".repeat(32)}\",\"k\":1,\"r\":1,\"c\":0," +
                        "\"s\":-1}],\"days\":[${days("{\"rows\":[0]}", count)}]}",
                ),
            )
        for (bytes in corrupt) {
            store.files[where] = bytes
            assertNull(load(), bytes.decodeToString().takeLast(60))
            assertEquals(DaySnapshotMiss.Unreadable, lastMiss())
        }
        // The well-formed control: one item, one row each, reads.
        store.files[where] = forged("$oneItem\"days\":[${days("{\"rows\":[0]}", count)}]}")
        assertNotNull(load())
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

    @Test
    fun aKeyWhoseWriteFailedIsNotEncodedAgainInThisProcess() {
        val days = persisted()
        store.failWrites = true
        assertTrue(!save(days))
        store.failWrites = false
        assertTrue(!save(days), "not tried again")
        assertEquals(listOf<DaySnapshotEvent>(DaySnapshotEvent.SaveFailed), events)
        assertTrue(store.files.isEmpty())
        // Another key is written as usual, and so is the same key in a new process.
        val tomorrow = scheduledAt(NOON_MS + DAY_MS)
        assertTrue(save(days, keyOf(days, scheduled = tomorrow), tomorrow))
        assertTrue(save(persisted()))
    }

    @Test
    fun aDisabledCacheFindsAndKeepsNothing() {
        val off = PersistedDays.disabled()
        assertTrue(!off.enabled)
        assertTrue(!runBlocking { off.save(ACCOUNT, keyOf(off), built(scheduledAt(NOON_MS))) })
        assertNull(runBlocking { off.load(ACCOUNT, keyOf(off), scheduledAt(NOON_MS), lineupDays::restore) })
    }

    @Test
    fun aNameIsShortWhateverTheNumberOfCutoffs() {
        val days = persisted()
        val key = keyOf(days)
        val many = key.copy(cutoffs = (0 until 500).map { Long.MAX_VALUE - it }.sorted())
        for (k in listOf(key, many)) {
            val name = PersistedDays.nameOf(k)
            assertTrue(name.length <= DayStore.MAX_NAME, name)
            assertTrue(Regex("[0-9a-z_-]+").matches(name), name)
        }
        assertTrue(PersistedDays.nameOf(key) != PersistedDays.nameOf(key.copy(cutoffs = key.cutoffs + 1L)))
    }

    private fun nameAt(atMs: Long): String {
        val days = persisted()
        return PersistedDays.nameOf(keyOf(days, scheduled = scheduledAt(atMs)))
    }

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
        runBlocking { store.write(stray, "1_2_ab", byteArrayOf(1)) }
        runBlocking { store.write(ACCOUNT, "not-a-day", byteArrayOf(1)) }
        for (day in 0L..4L) saveAt(days, NOON_MS + day * DAY_MS)

        val wanted = listOf(2L, 3L, 4L).map { nameAt(NOON_MS + it * DAY_MS) }.toSet()
        assertEquals(wanted, runBlocking { store.names(ACCOUNT) }.toSet())
        assertEquals(listOf("1_2_ab"), runBlocking { store.names(stray) }, "another account's files are its own")
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
    fun aSetDatedFurtherAheadThanADayCanBeIsDropped() {
        // Written under a clock a month fast: it would otherwise stay "the latest" and push today's out.
        val days = persisted()
        saveAt(days, NOON_MS + 30 * DAY_MS)
        saveAt(days, NOON_MS + 3 * DAY_MS)
        saveAt(days, NOON_MS)

        val kept = runBlocking { store.names(ACCOUNT) }.toSet()
        assertTrue(nameAt(NOON_MS + 30 * DAY_MS) !in kept, "the month-ahead set is gone")
        // Three days ahead is within an epoch and a grace of today's: a real day, kept.
        assertEquals(setOf(nameAt(NOON_MS), nameAt(NOON_MS + 3 * DAY_MS)), kept)
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
