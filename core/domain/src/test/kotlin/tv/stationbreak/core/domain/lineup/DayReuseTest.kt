// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Built days follow what a day depends on, not the whole lineup (spec 21, F2.4): an edit that changes no channel's pool
 * (a renumber, a swap on one side of the Kids band) reuses every built day, in memory and on disk, while the numbers
 * and names the guide, flipping and live see follow the lineup at once.
 */
class DayReuseTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val disk = MemoryDayStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private val toons = LineupIds.starter("toons")
    private val laughs = LineupIds.starter("laughs")
    private val whodunit = LineupIds.starter("whodunit")
    private val frights = LineupIds.starter("frights")

    private fun held(): Lineup =
        lineupOf(
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Horror"))),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
        )

    /** One process's service and source over the shared stores, and the builds it reported. */
    private inner class Process {
        val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
        val events: MutableList<DaySnapshotEvent> = Collections.synchronizedList(mutableListOf())
        val service = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog, derived = disk))
        val source =
            BuiltInScheduleSource(
                service,
                library,
                built = { builds += it },
                persisted = PersistedDays(disk, "1:100", { events += it }),
            ) { LIBRARIES }

        fun days(at: Instant = NOON): List<ChannelDay> =
            runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at).also { source.awaitSaves() } }

        fun edit(change: LineupEdit) {
            val saved = runBlocking { service.edit(ACCOUNT, change) }
            assertIs<EditOutcome.Applied>((saved as? Saved.Ok<*>)?.value, "the edit applied: $saved")
        }

        val evaluations: Int get() = builds.sumOf { it.evaluations }
    }

    private fun process(lineup: Lineup = held()): Process {
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineup)) }
        return Process()
    }

    private fun pools(days: List<ChannelDay>): Map<ChannelId, List<Any>> = days.associate { it.channel.id to it.pool }

    private fun assertSamePools(
        before: List<ChannelDay>,
        after: List<ChannelDay>,
    ) {
        assertEquals(before.map { it.channel.id }.toSet(), after.map { it.channel.id }.toSet())
        val was = pools(before)
        for ((id, pool) in pools(after)) assertSame(was.getValue(id), pool, "the day of $id was reused")
    }

    @Test
    fun aRenumberReusesEveryBuiltDay() {
        val tv = process()
        val before = tv.days()
        val evaluations = tv.evaluations
        assertEquals(1, tv.builds.size)

        tv.edit(LineupEdit.SetNumber(laughs, 150))
        val after = tv.days()

        assertEquals(1, tv.builds.size, "no build after the renumber")
        assertEquals(evaluations, tv.evaluations, "no pool was evaluated again")
        assertSamePools(before, after)
    }

    @Test
    fun aSwapAboveTheKidsBandReusesEveryBuiltDay() {
        val tv = process()
        val before = tv.days()

        tv.edit(LineupEdit.SetNumber(laughs, 210, swapWith = whodunit))
        val after = tv.days()

        assertEquals(1, tv.builds.size, "no build after the swap")
        assertSamePools(before, after)
        assertEquals(listOf(10, 99, 110, 199, 210, 310), after.map { it.channel.number })
        assertEquals(laughs, after.first { it.channel.number == 210 }.channel.id)
    }

    @Test
    fun aRenumberReusesTheDaysOnDiskInTheNextProcess() {
        val first = process()
        first.days()
        first.edit(LineupEdit.SetNumber(laughs, 150))
        first.days()
        val reads = library.snapshotCutoffs.size

        val next = Process()
        val days = next.days()

        assertEquals("disk", next.builds.single().snapshot, "the renumbered lineup's days came from disk")
        assertEquals(reads, library.snapshotCutoffs.size)
        assertEquals(150, days.first { it.channel.id == laughs }.channel.number)
    }

    // The edits never move a channel into 1-99 unless it is a kid channel already, so this is the key itself: the
    // same lineup with a custom channel at 600 and at 50, where the Kids band holds it to made-for-kids content.
    @Test
    fun aMoveIntoTheKidsBandRebuilds() {
        val days = LineupDays(TestCatalog)
        val custom = ChannelSource.Custom(rules("Mine", Source.Genre("Animation")))
        val at600 = held().let { it.copy(channels = it.channels + channel("custom-1", 600, ChannelState.On, custom)) }
        val at50 = renumbered(at600, 50)
        val at650 = renumbered(at600, 650)

        assertNotEquals(days.inputsOf(at600), days.inputsOf(at50), "into the Kids band: another policy and ceiling")
        assertEquals(days.inputsOf(at600), days.inputsOf(at650), "within the grown-up numbers: the same days")
    }

    private fun renumbered(
        lineup: Lineup,
        number: Int,
    ) = lineup.copy(
        channels = lineup.channels.map { if (it.id.value == "custom-1") it.copy(numberOverride = number) else it },
    )

    @Test
    fun aMoveOutOfTheKidsBandRebuilds() {
        val tv = process()
        tv.days()

        tv.edit(LineupEdit.SetNumber(toons, 120))
        tv.days()

        assertEquals(2, tv.builds.size, "a kid channel moved out of 1-99 is no longer held to made-for-kids content")
    }

    @Test
    fun aSwitchOffRebuilds() {
        val tv = process()
        tv.days()

        tv.edit(LineupEdit.SwitchOff(frights))
        val after = tv.days()

        assertEquals(2, tv.builds.size)
        assertTrue(after.none { it.channel.id == frights })
    }

    @Test
    fun aNameInTheRulesIsNotPartOfTheKey() {
        val days = LineupDays(TestCatalog)
        val renamed =
            held().let { lineup ->
                lineup.copy(
                    channels =
                        lineup.channels.map {
                            val source = it.source
                            if (it.id == laughs && source is ChannelSource.Starter) {
                                it.copy(source = source.copy(rules = source.rules.copy(name = "Giggles")))
                            } else {
                                it
                            }
                        },
                )
            }
        assertEquals(days.inputsOf(held()), days.inputsOf(renamed))
    }

    @Test
    fun airingsShowTheNewNumberAtOnce() =
        runBlocking<Unit> {
            val tv = process()
            tv.source.airings(NOON)
            tv.source.schedule(NOON, NOON + 2.hours, ScheduleBuild.AsNeeded)

            tv.edit(LineupEdit.SetNumber(laughs, 150))

            val airings = tv.source.airings(NOON)
            assertEquals(listOf(10, 99, 150, 199, 210, 310), airings.map { it.number }, "in the new number order")
            assertEquals(150, airings.first { it.id == laughs }.number)
            assertEquals(
                150,
                tv.source
                    .onAir(laughs, NOON, 1)
                    ?.channel
                    ?.number,
            )
            val window = tv.source.schedule(NOON, NOON + 2.hours, ScheduleBuild.CachedOnly)
            assertEquals(
                150,
                window
                    ?.rows
                    ?.first { it.channel.id == laughs }
                    ?.channel
                    ?.number,
            )
            // The window shows the channels with something on (an empty day is hidden), in the same order.
            val shown = airings.filter { it.hidden == null }.map { it.number }
            assertEquals(shown, window?.rows?.map { it.channel.number })
            assertEquals(1, tv.builds.size)
        }

    // No edit renames a channel, but a reconcile with the stored lineup can publish one: the held lineup was planned
    // while the file could not be read, and the file, readable again, has the user's name for a channel.
    @Test
    fun aNameChangeFollowsAtOnceWithoutABuild() =
        runBlocking<Unit> {
            val planner = LineupService(RecordingStore(), library, clock, { 0 }, {}, LineupSeams(TestCatalog))
            planner.refresh(ACCOUNT, LIBRARIES)
            val planned = checkNotNull(planner.lineup(ACCOUNT).value)
            val renamed =
                planned.lineup.copy(
                    channels =
                        planned.lineup.channels.map {
                            if (it.id == laughs) it.copy(nameOverride = "Giggles") else it
                        },
                )
            store.inner.save(ACCOUNT, LineupCodec.encode(renamed, planned.meta))
            store.inner.failReads = true
            val tv = Process()
            tv.service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(
                planned.lineup,
                tv.service
                    .lineup(ACCOUNT)
                    .value
                    ?.lineup,
                "planned from scratch, as the planner",
            )
            assertEquals(
                "Laughs",
                tv.source
                    .airings(NOON)
                    .first { it.id == laughs }
                    .name,
            )

            store.inner.failReads = false
            // Switching on a channel that is on changes nothing, but first the stored lineup replaces the held one.
            tv.service.edit(ACCOUNT, LineupEdit.SwitchOn(laughs))

            assertEquals(
                renamed,
                tv.service
                    .lineup(ACCOUNT)
                    .value
                    ?.lineup,
            )
            assertEquals(
                "Giggles",
                tv.source
                    .airings(NOON)
                    .first { it.id == laughs }
                    .name,
            )
            assertEquals(1, tv.builds.size, "the name came without a build")
        }

    // A reset forgets the days built from the old lineup: on disk, and in memory too.
    @Test
    fun aResetForgetsTheBuiltDaysInMemoryToo() =
        runBlocking<Unit> {
            val tv = Process()
            tv.service.refresh(ACCOUNT, LIBRARIES)
            tv.days()
            assertEquals(1, tv.builds.size)

            assertIs<Saved.Ok<*>>(tv.service.reset(ACCOUNT, LIBRARIES))
            tv.days()

            assertEquals(2, tv.builds.size, "the reset's lineup has the same days, but they were forgotten")
            assertTrue(tv.events.any { it == DaySnapshotEvent.Miss(DaySnapshotMiss.Absent) }, "and looked for again")
        }

    @Test
    fun aBuildReportsTheTimeOfTheDayInputs() {
        val tv = process()
        tv.days()
        assertTrue(tv.builds.single().inputsMs >= 0, "inputsMs=${tv.builds.single().inputsMs}")
    }

    @Test
    fun daysPersistedUnderTheOldKeyAreNotRead() {
        val first = process()
        val built = first.days()
        val (where, bytes) = disk.only()
        val current = DaySnapshotCodec.header(bytes) as DaySnapshotCodec.Header.Read
        // The key as the app wrote it before: a hash of the whole stored lineup, the channels in number order.
        val lineup = checkNotNull(first.service.lineup(ACCOUNT).value).lineup
        val oldKey =
            current.key.copy(
                format = 3,
                inputs = sha256(LineupCodec.encode(lineup)),
                channels = built.map { it.channel.id.value },
                days = built.map { it.n },
            )
        val old = DaySnapshotCodec.encode(oldKey, built).decodeToString()
        val asWritten = old.replaceFirst("{\"format\":${DaySnapshotCodec.VERSION},", "{\"format\":3,")
        disk.files[where] = asWritten.encodeToByteArray()

        val next = Process()
        next.days()
        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Format), next.events.first())
        assertEquals("miss", next.builds.single().snapshot)

        // Even under this format's number, the old key's lineup hash is never taken for the new one.
        disk.files[where] = DaySnapshotCodec.encode(oldKey.copy(format = DaySnapshotCodec.VERSION), built)
        val third = Process()
        third.days()
        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Lineup), third.events.first())
        assertEquals("miss", third.builds.single().snapshot)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }
}
