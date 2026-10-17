// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.buildDay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Spec 09 task 2.11 (Granny HIGH-1, the format's "Edits" rule): an edit this TV saw at or after a day's cut-off
 * `S - G` leaves day n unchanged, and day n + 1 uses it. A seeded generator makes 1,000 random histories (adds, field
 * edits, genre and tag edits, library moves, deletions, revivals, identical rewrites and name-only edits, some before
 * the cut-off and some at or after it), applies them to a real database, and checks:
 *
 * 1. the items the read returns for day n equal an independent in-memory model of "the version held before the cut-off";
 * 2. the same history cut at the cut-off (nothing after it ever happened) gives the same items, apart from a deletion
 *    time that is still ahead, and `buildDay` makes the same day from both;
 * 3. for day n + 1 the read equals the model again, so the edits that were held back are used from there on.
 *
 * A failure prints the case number and the seed: rerun it with `HistoryGen(<seed>)`.
 */
class EditCutoffPropertyTest {
    private val clock = FakeClock()
    private val full = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val cut = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() {
        full.close()
        cut.close()
    }

    private class HistoryGen(
        val seed: ULong,
    ) {
        private var state = seed

        fun nextULong(): ULong {
            val out = splitmix64(state)
            state += GAMMA
            return out
        }

        fun nextInt(bound: Int): Int = (nextULong() % bound.toULong()).toInt()

        fun nextLong(bound: Long): Long = (nextULong() % bound.toULong()).toLong()

        fun chance(percent: Int): Boolean = nextInt(100) < percent

        fun <T> pick(options: List<T>): T = options[nextInt(options.size)]

        companion object {
            const val GAMMA = 0x9E3779B97F4A7C15uL
        }
    }

    /** The fields of an item that the history keeps (the name is left out: it is never a version). */
    private data class Fields(
        val library: ItemId,
        val kind: ItemKind,
        val series: String?,
        val season: Int?,
        val episode: Int?,
        val runtimeMs: Long,
        val createdAtMs: Long,
        val premiere: String?,
        val year: Int?,
        val rating: String?,
        val genres: List<String>,
        val tags: List<String>,
    )

    private class State(
        val fields: Fields,
        val seenFromMs: Long,
        var deletedAtMs: Long?,
    )

    private class Op(
        val atMs: Long,
        val item: Int,
        val kind: Kind,
        val change: Int,
    ) {
        enum class Kind { Edit, Rewrite, NameOnly, Tombstone, Revive }
    }

    private val ids = (1..8).map { hexId(0x100 + it) }
    private val seriesIds = listOf(hexId(0x900), hexId(0x901))

    private fun fieldsOf(gen: HistoryGen, cutoffMs: Long): Fields {
        val episode = gen.chance(40)
        return Fields(
            library = if (gen.chance(80)) MOVIES else SHOWS,
            kind = if (episode) ItemKind.Episode else ItemKind.Movie,
            series = if (episode) gen.pick(seriesIds) else null,
            season = if (episode) gen.nextInt(4) else null,
            episode = if (episode) gen.nextInt(20) else null,
            runtimeMs = (1 + gen.nextInt(180)) * 60_000L,
            createdAtMs = cutoffMs - 1 - gen.nextLong(10 * DAY_MS),
            premiere = if (gen.chance(60)) "%04d-%02d-%02d".format(1950 + gen.nextInt(80), 1 + gen.nextInt(12), 1 + gen.nextInt(28)) else null,
            year = if (gen.chance(60)) 1950 + gen.nextInt(80) else null,
            rating = if (gen.chance(50)) gen.pick(listOf("G", "PG", "R")) else null,
            genres = List(gen.nextInt(3)) { gen.pick(listOf("Drama", "Comedy", "Horror", "Action")) },
            tags = List(gen.nextInt(3)) { gen.pick(listOf("a", "b", "c")) },
        )
    }

    /** One random change to [f] (library moves included), so that it differs in one thing. */
    private fun changed(gen: HistoryGen, f: Fields, cutoffMs: Long): Fields =
        when (gen.nextInt(8)) {
            0 -> f.copy(runtimeMs = (1 + gen.nextInt(180)) * 60_000L)
            1 -> f.copy(genres = f.genres + gen.pick(listOf("Drama", "Comedy", "Horror", "Action")))
            2 -> f.copy(tags = f.tags.reversed() + "z")
            3 -> f.copy(rating = gen.pick(listOf("G", "PG", "R", "NC-17")))
            4 -> f.copy(library = if (f.library == MOVIES) SHOWS else MOVIES)
            5 -> f.copy(premiere = "%04d-%02d-%02d".format(1950 + gen.nextInt(80), 1 + gen.nextInt(12), 1 + gen.nextInt(28)))
            6 -> f.copy(year = 1950 + gen.nextInt(80))
            else -> f.copy(createdAtMs = cutoffMs - 1 - gen.nextLong(10 * DAY_MS))
        }

    private fun mediaItem(index: Int, f: Fields, name: String) =
        MediaItem(
            id = ItemId(ids[index]),
            name = name,
            kind = f.kind,
            runtime = f.runtimeMs.milliseconds,
            parentId = f.library,
            dateCreated = Instant.fromEpochMilliseconds(f.createdAtMs),
            premiereDate = f.premiere,
            productionYear = f.year,
            officialRating = f.rating,
            genres = f.genres,
            tags = f.tags,
            seriesId = f.series?.let(::ItemId),
            seasonNumber = f.season,
            episodeNumber = f.episode,
        )

    /** The history applied to a model: each item's states in the order they became current. */
    private fun model(ops: List<Op>, firstFields: Map<Int, Fields>, nextFields: Map<Op, Fields>): Map<Int, MutableList<State>> {
        val items = mutableMapOf<Int, MutableList<State>>()
        for (op in ops) {
            val states = items[op.item]
            if (states == null) {
                items[op.item] = mutableListOf(State(firstFields.getValue(op.item), op.atMs, null))
                continue
            }
            val last = states.last()
            val stamp = maxOf(op.atMs, last.seenFromMs + 1)
            when (op.kind) {
                Op.Kind.Tombstone -> if (last.deletedAtMs == null) last.deletedAtMs = op.atMs
                Op.Kind.Revive -> if (last.deletedAtMs != null) states += State(last.fields, stamp, null)
                Op.Kind.NameOnly -> if (last.deletedAtMs != null) states += State(last.fields, stamp, null)
                Op.Kind.Rewrite -> if (last.deletedAtMs != null) states += State(last.fields, stamp, null)
                Op.Kind.Edit -> {
                    val next = nextFields.getValue(op)
                    if (next != last.fields || last.deletedAtMs != null) states += State(next, stamp, null)
                }
            }
        }
        return items
    }

    /** What the read must return for [cutoffMs]: per item the last state first seen before it (else the first), filtered. */
    private fun expected(items: Map<Int, List<State>>, cutoffMs: Long): List<ScheduleItem> =
        items
            .map { (index, states) ->
                val held = states.lastOrNull { it.seenFromMs < cutoffMs } ?: states.first()
                index to held
            }.filter { (_, s) -> s.fields.library == MOVIES && (s.deletedAtMs == null || s.deletedAtMs!! >= cutoffMs) }
            .map { (index, s) ->
                ScheduleItem(
                    id = CanonicalId.parse(ids[index]),
                    kind = if (s.fields.kind == ItemKind.Episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                    runtimeMs = s.fields.runtimeMs,
                    createdAtMs = s.fields.createdAtMs,
                    deletedAtMs = s.deletedAtMs,
                    premiereDate = s.fields.premiere,
                    productionYear = s.fields.year,
                    seriesId = s.fields.series?.let(CanonicalId::parse),
                    seasonNumber = s.fields.season,
                    episodeNumber = s.fields.episode,
                )
            }.sortedBy { it.id.value }

    private fun settingsOf(gen: HistoryGen): SchedulerSettings {
        val grid = gen.pick(listOf(15, 30, 60))
        return SchedulerSettings(
            orderingMode = gen.pick(OrderingMode.entries),
            seed = Seed(gen.nextULong()),
            blockLength = 1 + gen.nextInt(5),
            alignToGrid = gen.chance(50),
            gridMinutes = grid,
            epochHours = gen.pick(listOf(1, 2, 3, 4, 6, 8, 12, 24)),
            utcOffsetMinutes = (gen.nextInt(1 + (840 + 720) / 15) * 15) - 720,
            startMinuteOfDay = gen.nextInt(1440 / grid) * grid,
            graceHours = gen.pick(listOf(0, 1, 3, 6, 12, 24, 48)),
            minRuntimeMs = 1_000L + gen.nextLong(3_599_000L),
        )
    }

    private suspend fun apply(repo: RoomLibraryRepository, ops: List<Op>, firstFields: Map<Int, Fields>, nextFields: Map<Op, Fields>) {
        repo.forget(ALICE)
        repo.useAccount(ALICE)
        repo.select(ALICE, setOf(MOVIES, SHOWS))
        val current = mutableMapOf<Int, Fields>()
        for (op in ops) {
            clock.atMs(op.atMs)
            val id = ItemId(ids[op.item])
            if (op.item !in current) {
                current[op.item] = firstFields.getValue(op.item)
                repo.put(ALICE, current.getValue(op.item).library, mediaItem(op.item, current.getValue(op.item), "first"))
                continue
            }
            when (op.kind) {
                Op.Kind.Tombstone -> repo.tombstone(ALICE, listOf(id))
                Op.Kind.Revive -> repo.revive(ALICE, listOf(id))
                Op.Kind.NameOnly -> {
                    val f = current.getValue(op.item)
                    repo.put(ALICE, f.library, mediaItem(op.item, f, "renamed at ${op.atMs}"))
                }
                Op.Kind.Rewrite -> {
                    val f = current.getValue(op.item)
                    repo.put(ALICE, f.library, mediaItem(op.item, f, "first"))
                }
                Op.Kind.Edit -> {
                    val f = nextFields.getValue(op)
                    current[op.item] = f
                    repo.put(ALICE, f.library, mediaItem(op.item, f, "first"))
                }
            }
        }
    }

    private fun Collection<ScheduleItem>.withoutDeletionTimes() = map { it.copy(deletedAtMs = null) }

    @Test
    fun anEditSeenAtOrAfterTheCutoffNeverChangesDayNAndIsUsedFromDayNPlusOne() {
        var dayNDiffersFromNextDay = 0
        var casesWithLateOps = 0
        forAllCases("EditCutoffPropertyTest", baseSeed = 0x5EED_0902uL, cases = 1000) { gen ->
            val settings = settingsOf(gen)
            val n = gen.nextLong(2001) - 1000
            val start = Epochs.epochStart(settings, n)
            val cutoff = start - settings.graceMs
            val nextCutoff = cutoff + settings.epochMs

            // The history: ops in time order, some before the cut-off and some at or after it.
            val before = gen.nextInt(14)
            val after = gen.nextInt(14)
            val times = mutableListOf<Long>()
            var t = cutoff - 1 - gen.nextLong(3 * DAY_MS)
            repeat(before) {
                times += t
                t = minOf(cutoff - 1, t + gen.nextLong(3 * DAY_MS / 14 + 1))
            }
            var u = if (gen.chance(30)) cutoff else cutoff + gen.nextLong(settings.epochMs + DAY_MS)
            repeat(after) {
                times += u
                u += gen.nextLong(settings.epochMs / 3 + 1)
            }
            val firstFields = mutableMapOf<Int, Fields>()
            val nextFields = mutableMapOf<Op, Fields>()
            val ops = mutableListOf<Op>()
            val live = mutableSetOf<Int>()
            val heldFields = mutableMapOf<Int, Fields>()
            for (time in times) {
                val canAdd = live.size < ids.size
                val doAdd = live.isEmpty() || (canAdd && gen.chance(25))
                if (doAdd) {
                    val index = (ids.indices).first { it !in live }
                    live += index
                    firstFields[index] = fieldsOf(gen, cutoff).also { heldFields[index] = it }
                    ops += Op(time, index, Op.Kind.Edit, 0)
                    continue
                }
                val index = gen.pick(live.toList())
                val kind = gen.pick(Op.Kind.entries)
                val op = Op(time, index, kind, 0)
                if (kind == Op.Kind.Edit) {
                    val next = changed(gen, heldFields.getValue(index), cutoff)
                    nextFields[op] = next
                    heldFields[index] = next
                }
                ops += op
            }
            if (ops.any { it.atMs >= cutoff }) casesWithLateOps++

            val prefix = ops.filter { it.atMs < cutoff }
            val modelFull = model(ops, firstFields, nextFields)
            val modelCut = model(prefix, firstFields, nextFields)

            runBlocking {
                apply(full, ops, firstFields, nextFields)
                apply(cut, prefix, firstFields, nextFields)

                // 1. Day n's items are the model's, and a history cut at the cut-off gives the same ones.
                val dayN = full.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(cutoff))
                assertEquals("day n items against the model", expected(modelFull, cutoff), dayN)
                val dayNCut = cut.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(cutoff))
                assertEquals(expected(modelCut, cutoff), dayNCut)
                // An item this TV met for the first time after the cut-off is not an edit: by the oldest-copy rule it is
                // in the read (the model above says so), so the comparison with the cut history is over the known ones.
                val known = modelCut.keys.map { ids[it] }.toSet()
                val dayNKnown = dayN.filter { it.id.value in known }
                assertEquals("day n items, history cut at the cut-off", dayNCut.withoutDeletionTimes(), dayNKnown.withoutDeletionTimes())

                // 2. buildDay makes the same day from both.
                val dayFromFull = buildDay(settings, dayNKnown, n)
                val dayFromCut = buildDay(settings, dayNCut, n)
                assertEquals("the day built", dayFromCut.blocks, dayFromFull.blocks)

                // 3. Day n + 1 sees the edits that were held back.
                val dayN1 = full.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(nextCutoff))
                assertEquals("day n + 1 items against the model", expected(modelFull, nextCutoff), dayN1)
                val cutNext = ops.filter { it.atMs < nextCutoff }
                apply(cut, cutNext, firstFields, nextFields)
                val dayN1Cut = cut.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(nextCutoff))
                val knownNext = model(cutNext, firstFields, nextFields).keys.map { ids[it] }.toSet()
                assertEquals(
                    "day n + 1 items, history cut at its cut-off",
                    dayN1Cut.withoutDeletionTimes(),
                    dayN1.filter { it.id.value in knownNext }.withoutDeletionTimes(),
                )
                if (dayN.withoutDeletionTimes() != dayN1.withoutDeletionTimes()) dayNDiffersFromNextDay++
                full.assertIntervalsContiguous(ALICE)
            }
        }
        println("EditCutoffPropertyTest: 1000 cases, $casesWithLateOps with an op at or after the cut-off, $dayNDiffersFromNextDay where day n + 1 differs from day n")
        // Not vacuous: a good share of the histories really do change the inputs between day n and day n + 1.
        assertTrue("only $dayNDiffersFromNextDay cases changed between day n and n + 1", dayNDiffersFromNextDay > 100)
    }

    private fun forAllCases(name: String, baseSeed: ULong, cases: Int, check: (HistoryGen) -> Unit) {
        for (index in 0 until cases) {
            val seed = splitmix64(baseSeed + index.toULong())
            try {
                check(HistoryGen(seed))
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                val message = "$name: case $index failed, seed $seed (HistoryGen(${seed}uL)): ${failure.message}"
                println(message)
                throw AssertionError(message, failure)
            }
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
