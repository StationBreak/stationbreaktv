// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import tv.stationbreak.core.domain.lineup.repair.RepairConfig
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.fixOf
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.LineupLimits
import tv.stationbreak.core.rules.Source
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What has become past is rolled in the background, once per boundary, without ever making a read wait (spec 23, D1).
 */
class LineupServiceRollTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private var now = NOON

    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun newService() = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    private val laughs = LineupIds.starter("laughs")

    // A finalised lineup with a leaving starter, previous rules and a basis, all due at [dueMs], stored.
    private fun storeDue(dueMs: Long): Lineup {
        runBlocking { newService().refresh(ACCOUNT, LIBRARIES) }
        val stored = (LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded)
        val old = rules("Old", Source.Genre("Crime"))
        val due =
            stored.lineup.copy(
                channels =
                    stored.lineup.channels.map {
                        val source = it.source
                        if (it.id == laughs && source is ChannelSource.Starter) {
                            it.copy(source = source.withPrevious(old, dueMs), leavesAtMs = dueMs)
                        } else {
                            it
                        }
                    },
                curationBasis = CurationBasis(dueMs, emptySet(), true),
            )
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(due, stored.meta)) }
        return due
    }

    private fun pastDue() {
        now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 7_200_000L)
    }

    private fun savedLineup(): Lineup {
        val stored = LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT)))
        return (stored as LineupCodec.Decoded.Loaded).lineup
    }

    @Test
    fun aLookupRollsWhatIsPastInTheBackgroundAndSavesIt() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            val rolled = checkNotNull(service.lineup(ACCOUNT).value).lineup
            val laughsNow = rolled.channels.first { it.id == laughs }
            assertEquals(ChannelState.Shelf, laughsNow.state)
            assertNull(laughsNow.leavesAtMs)
            assertNull((laughsNow.source as ChannelSource.Starter).previousRules)
            assertNull(rolled.curationBasis)
            assertEquals(rolled, savedLineup())
            // Nothing is past now: another lookup starts nothing.
            val attempts = service.rollAttempts.get()
            service.rollSoon(ACCOUNT, checkNotNull(service.lineup(ACCOUNT).value))
            assertEquals(attempts, service.rollAttempts.get())
        }

    @Test
    fun aLookupBeforeTheBoundaryChangesNothing() =
        runBlocking<Unit> {
            val due = storeDue(now.toEpochMilliseconds() + 3_600_000L)
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(0, service.rollAttempts.get())
            assertEquals(due, checkNotNull(service.lineup(ACCOUNT).value).lineup)
        }

    @Test
    fun aSessionThatCannotSaveMakesOneAttemptPerBoundary() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            store.inner.failWrites = true
            val saves = store.calls.count { it == "save" }
            repeat(25) { service.rollSoon(ACCOUNT, held) }
            service.awaitRolls()
            assertEquals(1, service.rollAttempts.get(), "however many lookups")
            repeat(25) { service.rollSoon(ACCOUNT, held) }
            service.awaitRolls()
            assertEquals(1, service.rollAttempts.get())
            assertEquals(saves + 1, store.calls.count { it == "save" }, "the failed save was tried once")
            // The next boundary is another attempt.
            now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 86_400_000L)
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(2, service.rollAttempts.get())
        }

    @Test
    fun aLookupNeverWaitsOnAHeldLock() =
        runBlocking<Unit> {
            val due = storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            val lock = checkNotNull(service.lockOf(ACCOUNT))
            lock.lock()
            try {
                service.rollSoon(ACCOUNT, held)
                service.awaitRolls()
            } finally {
                lock.unlock()
            }
            assertEquals(due, checkNotNull(service.lineup(ACCOUNT).value).lineup, "skipped: a refresh rolls anyway")
            // The busy lock did not use up the boundary's attempt: a later lookup rolls.
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(2, service.rollAttempts.get())
            assertNull(checkNotNull(service.lineup(ACCOUNT).value).lineup.curationBasis)
            assertEquals(checkNotNull(service.lineup(ACCOUNT).value).lineup, savedLineup())
        }

    @Test
    fun aHeldLineupThatMayNotBeSavedStartsNoRoll() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            repeat(5) { service.rollSoon(ACCOUNT, held.copy(writable = false)) }
            service.awaitRolls()
            assertEquals(0, service.rollAttempts.get())
        }

    // The stored lineup has nothing to roll, but its household change has reached its boundary.
    private fun storeReplanDue(dueMs: Long) {
        runBlocking { newService().refresh(ACCOUNT, LIBRARIES) }
        val stored = (LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded)
        val meta = stored.meta.copy(replanFromMs = dueMs)
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(stored.lineup, meta)) }
    }

    @Test
    fun aDueReplanIsSignalledOncePerBoundaryWhileNothingHoldsTheLock() =
        runBlocking<Unit> {
            storeReplanDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            val signals = AtomicInteger()
            val listening =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    service.replanRequests.collect { signals.incrementAndGet() }
                }
            val lock = checkNotNull(service.lockOf(ACCOUNT))

            lock.lock()
            try {
                service.rollSoon(ACCOUNT, held)
                yield()
            } finally {
                lock.unlock()
            }
            assertEquals(0, signals.get(), "something holds the lock: it plans")
            repeat(5) {
                service.rollSoon(ACCOUNT, held)
                yield()
            }
            assertEquals(1, signals.get(), "nothing to roll, but the change is due: once, however many lookups")
            assertEquals(0, service.rollAttempts.get())
            now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 86_400_000L)
            service.rollSoon(ACCOUNT, held)
            yield()
            assertEquals(2, signals.get(), "still due at the next boundary: once more")
            listening.cancel()
        }

    @Test
    fun concurrentLookupsAtTurnoverReadTheStoreNoMoreThanOnce() =
        runBlocking<Unit> {
            storeDue(now.toEpochMilliseconds() + 3_600_000L)
            pastDue()
            val service = newService()
            val held = checkNotNull(service.loaded(ACCOUNT))
            val loads = store.calls.count { it == "load" }
            (1..20).map { async { service.rollSoon(ACCOUNT, held) } }.awaitAll()
            service.awaitRolls()
            assertEquals(1, service.rollAttempts.get())
            assertEquals(loads, store.calls.count { it == "load" }, "a roll works from the held lineup")
        }

    // ---- The repair record (spec 26) ----

    private fun serviceWith(config: RepairConfig = RepairConfig()) =
        LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog, repairConfig = config))

    private fun stored(): LineupCodec.Decoded.Loaded =
        LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded

    private val nowMs get() = now.toEpochMilliseconds()

    @Test
    fun v3File_withRepairs_savesAsV4_withBakCopy() =
        runBlocking<Unit> {
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            val v3Bytes = checkNotNull(store.inner.peek(ACCOUNT))
            assertEquals(3, stored().fileVersion)
            assertNull(store.inner.keptCopy(ACCOUNT, "v3"), "no copy for a save that stays at version 3")

            val service = serviceWith()
            val record = TodayRepairs(selection = listOf(repairOf(atMs = nowMs + 60_000L)))
            assertTrue(service.recordRepairs(ACCOUNT) { record })

            assertEquals(4, stored().fileVersion)
            assertEquals(record, stored().repairs)
            assertEquals(v3Bytes.toList(), checkNotNull(store.inner.keptCopy(ACCOUNT, "v3")).toList())
            assertEquals(record, service.repairs(ACCOUNT).value)
            assertEquals(record, checkNotNull(service.lineup(ACCOUNT).value).repairs)
            // The copy is kept once: a later record is already version 4.
            assertTrue(service.recordRepairs(ACCOUNT) { it.repairs.copy(lostCheckedGeneration = 3L) })
            assertEquals(v3Bytes.toList(), checkNotNull(store.inner.keptCopy(ACCOUNT, "v3")).toList())
        }

    @Test
    fun aRefreshKeepsTheRepairs() =
        runBlocking<Unit> {
            val service = serviceWith()
            service.refresh(ACCOUNT, LIBRARIES)
            val record = TodayRepairs(fixes = listOf(fixOf(blockStartMs = nowMs)))
            assertTrue(service.recordRepairs(ACCOUNT) { record })
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(record, service.repairs(ACCOUNT).value)
            assertEquals(record, stored().repairs)
            // A restarted session reads them back.
            val restarted = serviceWith()
            restarted.refresh(ACCOUNT, LIBRARIES)
            assertEquals(record, restarted.repairs(ACCOUNT).value)
        }

    @Test
    fun recordRepairs_whenThereIsNoLineup_orItMayNotBeWritten_isFalse() =
        runBlocking<Unit> {
            assertFalse(serviceWith().recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(fixOf())) })
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            store.inner.failReads = true
            val service = serviceWith()
            assertFalse(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(fixOf())) })
            store.inner.failReads = false
            assertEquals(TodayRepairs.NONE, stored().repairs)
        }

    @Test
    fun recordRepairs_saveFails_changesNothing() =
        runBlocking<Unit> {
            val service = serviceWith()
            service.refresh(ACCOUNT, LIBRARIES)
            val before = checkNotNull(store.inner.peek(ACCOUNT))
            val published = checkNotNull(service.lineup(ACCOUNT).value)
            store.inner.failWrites = true
            assertFalse(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(fixOf())) })
            store.inner.failWrites = false
            assertEquals(before.toList(), checkNotNull(store.inner.peek(ACCOUNT)).toList())
            assertEquals(published, service.lineup(ACCOUNT).value)
            assertEquals(TodayRepairs.NONE, service.repairs(ACCOUNT).value)
        }

    @Test
    fun rolled_aBackgroundRollPrunesEndedDaysFromTheRecordAndFile() =
        runBlocking<Unit> {
            val service = serviceWith()
            service.refresh(ACCOUNT, LIBRARIES)
            val soon = nowMs + 7_200_000L
            val later = nowMs + 30 * 3_600_000L
            val record =
                TodayRepairs(
                    selection =
                        listOf(repairOf(n = 1L, dayEndMs = soon), repairOf(n = 2L, atMs = soon, dayEndMs = later)),
                    fixes =
                        listOf(fixOf(n = 1L, dayEndMs = soon), fixOf(n = 2L, dayEndMs = later, blockStartMs = soon)),
                )
            assertTrue(service.recordRepairs(ACCOUNT) { record })
            pastDue()
            val restarted = serviceWith()
            val held = checkNotNull(restarted.loaded(ACCOUNT))
            restarted.rollSoon(ACCOUNT, held)
            restarted.awaitRolls()
            val rolled = record.rolled(nowMs)
            assertEquals(listOf(2L), rolled.selection.map { it.n })
            assertEquals(rolled, restarted.repairs(ACCOUNT).value)
            assertEquals(rolled, stored().repairs)
        }

    @Test
    fun rolled_whenTheLastDayEnds_thePrunedFileIsVersion3Again() =
        runBlocking<Unit> {
            val service = serviceWith()
            service.refresh(ACCOUNT, LIBRARIES)
            val soon = nowMs + 7_200_000L
            assertTrue(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(fixOf(dayEndMs = soon))) })
            assertEquals(4, stored().fileVersion)
            pastDue()
            val restarted = serviceWith()
            restarted.rollSoon(ACCOUNT, checkNotNull(restarted.loaded(ACCOUNT)))
            restarted.awaitRolls()
            assertEquals(3, stored().fileVersion)
            assertEquals(TodayRepairs.NONE, restarted.repairs(ACCOUNT).value)
        }

    // The config that leaves [room] bytes under the cap for a file holding [with].
    private fun configLeaving(
        room: Int,
        with: TodayRepairs,
    ): RepairConfig {
        val held = stored()
        val size = LineupCodec.encode(held.lineup, held.meta, with).size
        return RepairConfig(fileMargin = LineupLimits.MAX_FILE_BYTES - size - room)
    }

    private fun fixAt(
        n: Long,
        coversNow: Boolean = false,
    ) = fixOf(
        n = n,
        dayEndMs = nowMs + n * 86_400_000L,
        blockStartMs = if (coversNow) nowMs - 600_000L else nowMs + n * 86_400_000L - 7_200_000L,
        gone = 100 + n.toInt(),
    )

    @Test
    fun sizeTrim_neverDropsTheFixCoveringNow() =
        runBlocking<Unit> {
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            // The covering fix belongs to the oldest day, so a plain oldest-first trim would drop it first.
            val covering = fixAt(1L, coversNow = true)
            val later = fixAt(2L)
            val config = configLeaving(room = 50, with = TodayRepairs(fixes = listOf(covering)))
            val service = serviceWith(config)
            assertTrue(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(covering, later)) })
            assertEquals(listOf(covering), service.repairs(ACCOUNT).value.fixes)
            assertEquals(listOf(covering), stored().repairs.fixes)
        }

    @Test
    fun sizeTrim_dropsTheOldestDayThenTheEarliestBlockFirst() =
        runBlocking<Unit> {
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            val d2a = fixAt(2L)
            val d2b = fixOf(n = 2L, dayEndMs = d2a.dayEndMs, blockStartMs = d2a.blockStartMs + 3_600_000L, gone = 102)
            val d3 = fixAt(3L)
            val d4 = fixAt(4L)
            val config = configLeaving(room = 50, with = TodayRepairs(fixes = listOf(d2b, d3, d4)))
            val service = serviceWith(config)
            assertTrue(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(d4, d3, d2b, d2a)) })
            val kept = service.repairs(ACCOUNT).value.fixes
            assertEquals(listOf(d4, d3, d2b).toSet(), kept.toSet(), "the earliest block of the oldest day went")
        }

    @Test
    fun sizeTrim_aCoveringFixThatDoesNotFit_isFalseAndNothingChanges() =
        runBlocking<Unit> {
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            val config = configLeaving(room = 10, with = TodayRepairs.NONE)
            val service = serviceWith(config)
            service.loaded(ACCOUNT)
            val before = checkNotNull(store.inner.peek(ACCOUNT))
            val published = checkNotNull(service.lineup(ACCOUNT).value)
            assertFalse(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(fixAt(1L, coversNow = true))) })
            assertEquals(before.toList(), checkNotNull(store.inner.peek(ACCOUNT)).toList())
            assertEquals(published, service.lineup(ACCOUNT).value)
            assertNull(store.inner.keptCopy(ACCOUNT, "v3"), "a refused record keeps no upgrade copy either")
        }

    @Test
    fun fixesAreCappedAtMaxFixes_oldestFirst() =
        runBlocking<Unit> {
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            val service = serviceWith(RepairConfig(maxFixes = 3))
            val fixes = (1L..5L).map { fixAt(it) }
            assertTrue(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = fixes) })
            assertEquals(
                fixes.drop(2).toSet(),
                service
                    .repairs(ACCOUNT)
                    .value.fixes
                    .toSet(),
            )
        }

    private fun fiftyChannelLineup(): Lineup {
        val channels =
            (1..50).map { n ->
                val many = (1..150).map { Source.Collection(id(n * 1000 + it)) }
                val source = ChannelSource.Custom(rules("Mine $n", *many.toTypedArray()))
                channel("custom-$n", 600 + n, ChannelState.On, source)
            }
        return lineupOf(*channels.toTypedArray())
    }

    // A real 50-channel lineup close to the cap: twenty changes follow, each a selection repair and a fix.
    @Test
    fun nearLimitLineup_twentyChanges_fitsOrFailsCleanly() =
        runBlocking<Unit> {
            val lineup = fiftyChannelLineup()
            val bare = LineupCodec.encode(lineup).size
            assertTrue(bare in 1 until LineupLimits.MAX_FILE_BYTES, "the lineup alone is $bare bytes")
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            // Room for a handful of fixes, no more.
            val room = 6 * 1_200
            val limited = RepairConfig(fileMargin = LineupLimits.MAX_FILE_BYTES - bare - room)
            val service = serviceWith(limited)
            service.loaded(ACCOUNT)
            val cap = LineupLimits.MAX_FILE_BYTES - limited.fileMargin

            var accepted = 0
            var lastAccepted = 0L
            for (n in 1L..20L) {
                val fix = fixAt(n)
                val repair = repairOf(channel = "custom-$n", n = 1L, seq = n, dayEndMs = nowMs + (n + 2) * 3_600_000L)
                val before = service.repairs(ACCOUNT).value
                val file = checkNotNull(store.inner.peek(ACCOUNT)).toList()
                val ok =
                    service.recordRepairs(ACCOUNT) {
                        it.repairs.copy(
                            selection = it.repairs.selection + repair,
                            fixes =
                                it.repairs.fixes + fix,
                        )
                    }
                val bytes = checkNotNull(store.inner.peek(ACCOUNT))
                if (ok) {
                    accepted++
                    lastAccepted = n
                    assertTrue(bytes.size <= cap, "file ${bytes.size} over $cap")
                    assertTrue(repair in service.repairs(ACCOUNT).value.selection, "the new selection repair is held")
                    assertEquals(service.repairs(ACCOUNT).value, stored().repairs)
                } else {
                    assertEquals(file, bytes.toList())
                    assertEquals(before, service.repairs(ACCOUNT).value)
                }
            }
            assertTrue(accepted in 1..19, "some changes fit and some do not: $accepted")
            val held = service.repairs(ACCOUNT).value
            assertTrue(held.fixes.size < accepted, "the oldest fixes were dropped to make room: ${held.fixes.size}")
            assertTrue(held.fixes.any { it.n == lastAccepted }, "the newest accepted fix stays")
            assertEquals(
                held.fixes.map { it.n }.sorted(),
                held.fixes
                    .map { it.n }
                    .sorted()
                    .let { it },
                "ordered",
            )
            assertTrue(held.fixes.minOf { it.n } > 1L, "the oldest went first")

            // A selection repair that cannot fit even with every droppable fix gone: false, nothing changes.
            val file = checkNotNull(store.inner.peek(ACCOUNT)).toList()
            val before = service.repairs(ACCOUNT).value
            val huge = repairOf(channel = "custom-1", n = 99L, tailBlocks = 3_000, dayEndMs = nowMs + 99 * 3_600_000L)
            assertFalse(service.recordRepairs(ACCOUNT) { it.repairs.copy(selection = it.repairs.selection + huge) })
            assertEquals(file, checkNotNull(store.inner.peek(ACCOUNT)).toList())
            assertEquals(before, service.repairs(ACCOUNT).value)
            assertEquals(before, stored().repairs)
        }

    @Test
    fun aTriedBoundaryCostsALookupNoRollingWork() =
        runBlocking<Unit> {
            storeDue(nowMs + 3_600_000L)
            pastDue()
            var looks = 0
            val seams = LineupSeams(TestCatalog, rolledLineup = { lineup, ms -> looks++.let { lineup.rolled(ms) } })
            val service = LineupService(store, library, clock, { 0 }, {}, seams)
            val held = checkNotNull(service.loaded(ACCOUNT))
            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()
            assertEquals(1, looks, "the first lookup of the boundary looks")
            repeat(20) { service.rollSoon(ACCOUNT, held) }
            assertEquals(1, looks, "later lookups of a tried boundary do not")
        }

    @Test
    fun anEditStillSaves_whenTheLineupGrowsPastTheRoom_andTheRecordIsTrimmed() =
        runBlocking<Unit> {
            serviceWith().refresh(ACCOUNT, LIBRARIES)
            val fixes = (1L..3L).map { fixAt(it) }
            val record = TodayRepairs(fixes = fixes)
            val config = configLeaving(room = 0, with = record)
            val trims = mutableListOf<Triple<Int, Int, Int>>()
            val seams =
                LineupSeams(TestCatalog, repairConfig = config, trimmed = { a, b, c -> trims += Triple(a, b, c) })
            val service = LineupService(store, library, clock, { 0 }, {}, seams)
            assertTrue(service.recordRepairs(ACCOUNT) { record })
            val before = checkNotNull(store.inner.peek(ACCOUNT)).size

            service.loaded(ACCOUNT)
            val edit = LineupEdit.SetNumber(laughs, 555)
            assertTrue(service.edit(ACCOUNT, edit) is Saved.Ok<*>)

            val after = checkNotNull(store.inner.peek(ACCOUNT))
            assertEquals(
                555,
                stored()
                    .lineup.channels
                    .first { it.id == laughs }
                    .numberOverride,
                "the edit landed",
            )
            assertTrue(after.size <= before, "a lineup that grew by a few bytes cost the record a fix")
            assertTrue(after.size <= LineupLimits.MAX_FILE_BYTES - config.fileMargin)
            val kept = service.repairs(ACCOUNT).value.fixes
            assertTrue(kept.size in 1..2, "the oldest fix went: ${kept.size}")
            assertEquals(kept, stored().repairs.fixes)
            assertEquals(listOf(Triple(3 - kept.size, 0, 0)), trims)
        }

    @Test
    fun withTheDefaultMargin_aRecordNeverTakesTheFileWithin64KiBOfTheCap() =
        runBlocking<Unit> {
            val limit = LineupLimits.MAX_FILE_BYTES - RepairConfig().fileMargin
            var channels = 30

            fun lineupOfSize(count: Int) =
                lineupOf(
                    *(1..count)
                        .map { n ->
                            val many = (1..150).map { Source.Collection(id(n * 1000 + it)) }
                            val source = ChannelSource.Custom(rules("M$n", *many.toTypedArray()))
                            channel("custom-$n", 600 + n, ChannelState.On, source)
                        }.toTypedArray(),
                )
            while (LineupCodec.encode(lineupOfSize(channels + 1)).size < limit - 3_000) channels++
            val lineup = lineupOfSize(channels)
            assertTrue(LineupCodec.encode(lineup).size < limit)
            store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
            val service = serviceWith()
            service.loaded(ACCOUNT)

            assertTrue(service.recordRepairs(ACCOUNT) { TodayRepairs(fixes = listOf(fixAt(1L))) })
            val huge = repairOf(channel = "custom-1", tailBlocks = 5_000)
            assertFalse(service.recordRepairs(ACCOUNT) { it.repairs.copy(selection = listOf(huge)) })
            assertTrue(checkNotNull(store.inner.peek(ACCOUNT)).size <= limit)
            assertEquals(listOf(fixAt(1L)), stored().repairs.fixes)
        }

    @Test
    fun aRecordTheFormatCannotHold_isRefusedAndNothingChanges() =
        runBlocking<Unit> {
            val service = serviceWith()
            service.refresh(ACCOUNT, LIBRARIES)
            val before = checkNotNull(store.inner.peek(ACCOUNT)).toList()
            val good = repairOf(atMs = nowMs + 60_000L)
            val zero = good.copy(blocks = good.blocks.map { it.copy(endMs = it.startMs) })
            val overlapped = good.blocks.mapIndexed { i, b -> if (i == 1) b.copy(startMs = b.startMs - 1) else b }
            val overlap = good.copy(blocks = overlapped)
            for (bad in listOf(zero, overlap)) {
                assertFalse(service.recordRepairs(ACCOUNT) { TodayRepairs(selection = listOf(bad)) })
                assertEquals(before, checkNotNull(store.inner.peek(ACCOUNT)).toList())
                assertEquals(TodayRepairs.NONE, service.repairs(ACCOUNT).value)
            }
        }
}
