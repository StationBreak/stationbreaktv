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
 * 1. the items the read returns for day n equal an independent in-memory model of "the version held before the
 *    cut-off";
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

        fun chance(percent: Int): Boolean = nextInt(PERCENT) < percent

        fun <T> pick(options: List<T>): T = options[nextInt(options.size)]

        companion object {
            const val GAMMA = 0x9E3779B97F4A7C15uL
            const val PERCENT = 100
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

    /** One state an item was in: when this TV first saw it, and its tombstone time if it was deleted while current. */
    private class State(
        val fields: Fields,
        val seenFromMs: Long,
        var deletedAtMs: Long?,
    )

    private class Op(
        val atMs: Long,
        val item: Int,
        val kind: Kind,
    ) {
        enum class Kind { Edit, Rewrite, NameOnly, Tombstone, Revive }
    }

    /** One generated case: the settings, the cut-off of day n, and the history around it. */
    @Suppress("LongParameterList") // A plain carrier for one case.
    private class History(
        val settings: SchedulerSettings,
        val n: Long,
        val cutoff: Long,
        val nextCutoff: Long,
        val ops: List<Op>,
        val firstFields: Map<Int, Fields>,
        val nextFields: Map<Op, Fields>,
    )

    private val ids = (1..8).map { hexId(0x100 + it) }
    private val seriesIds = listOf(hexId(0x900), hexId(0x901))
    private val someGenres = listOf("Drama", "Comedy", "Horror", "Action")

    private fun date(gen: HistoryGen): String {
        val year = (1950 + gen.nextInt(80)).toString()
        val month = (1 + gen.nextInt(12)).toString().padStart(2, '0')
        val day = (1 + gen.nextInt(28)).toString().padStart(2, '0')
        return "$year-$month-$day"
    }

    private fun fieldsOf(
        gen: HistoryGen,
        cutoffMs: Long,
    ): Fields {
        val episode = gen.chance(40)
        return Fields(
            library = if (gen.chance(80)) MOVIES else SHOWS,
            kind = if (episode) ItemKind.Episode else ItemKind.Movie,
            series = if (episode) gen.pick(seriesIds) else null,
            season = if (episode) gen.nextInt(4) else null,
            episode = if (episode) gen.nextInt(20) else null,
            runtimeMs = (1 + gen.nextInt(180)) * 60_000L,
            createdAtMs = cutoffMs - 1 - gen.nextLong(10 * DAY_MS),
            premiere = if (gen.chance(60)) date(gen) else null,
            year = if (gen.chance(60)) 1950 + gen.nextInt(80) else null,
            rating = if (gen.chance(50)) gen.pick(listOf("G", "PG", "R")) else null,
            genres = List(gen.nextInt(3)) { gen.pick(someGenres) },
            tags = List(gen.nextInt(3)) { gen.pick(listOf("a", "b", "c")) },
        )
    }

    /** One random change to [f] (library moves included), so that it differs in one thing. */
    private fun changed(
        gen: HistoryGen,
        f: Fields,
        cutoffMs: Long,
    ): Fields =
        when (gen.nextInt(8)) {
            0 -> f.copy(runtimeMs = (1 + gen.nextInt(180)) * 60_000L)
            1 -> f.copy(genres = f.genres + gen.pick(someGenres))
            2 -> f.copy(tags = f.tags.reversed() + "z")
            3 -> f.copy(rating = gen.pick(listOf("G", "PG", "R", "NC-17")))
            4 -> f.copy(library = if (f.library == MOVIES) SHOWS else MOVIES)
            5 -> f.copy(premiere = date(gen))
            6 -> f.copy(year = 1950 + gen.nextInt(80))
            else -> f.copy(createdAtMs = cutoffMs - 1 - gen.nextLong(10 * DAY_MS))
        }

    private fun mediaItem(
        index: Int,
        f: Fields,
        name: String,
    ) = MediaItem(
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

    /** What one op does to an item that exists: the stamp rule of the spec (Vimes H1) and the change test (H2, H3). */
    private fun step(
        states: MutableList<State>,
        op: Op,
        next: Fields?,
    ) {
        val last = states.last()
        val stamp = maxOf(op.atMs, last.seenFromMs + 1)
        val revives = last.deletedAtMs != null
        when (op.kind) {
            Op.Kind.Tombstone -> {
                if (!revives) last.deletedAtMs = op.atMs
            }

            Op.Kind.Revive, Op.Kind.NameOnly, Op.Kind.Rewrite -> {
                if (revives) states += State(last.fields, stamp, null)
            }

            Op.Kind.Edit -> {
                val fields = requireNotNull(next)
                if (fields != last.fields || revives) states += State(fields, stamp, null)
            }
        }
    }

    /** The history applied to a model: each item's states in the order they became current. */
    private fun model(
        history: History,
        ops: List<Op>,
    ): Map<Int, List<State>> {
        val items = mutableMapOf<Int, MutableList<State>>()
        for (op in ops) {
            val states = items[op.item]
            if (states == null) {
                items[op.item] = mutableListOf(State(history.firstFields.getValue(op.item), op.atMs, null))
            } else {
                step(states, op, history.nextFields[op])
            }
        }
        return items
    }

    /** What the read must return for [cutoffMs]: per item the last state first seen before it (else the first). */
    private fun expected(
        items: Map<Int, List<State>>,
        cutoffMs: Long,
    ): List<ScheduleItem> =
        items
            .map { (index, states) -> index to (states.lastOrNull { it.seenFromMs < cutoffMs } ?: states.first()) }
            .filter { (_, s) -> s.fields.library == MOVIES && (s.deletedAtMs ?: cutoffMs) >= cutoffMs }
            .map { (index, s) -> scheduleItem(index, s) }
            .sortedBy { it.id.value }

    private fun scheduleItem(
        index: Int,
        s: State,
    ) = ScheduleItem(
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

    /** Op times in order: some strictly before the cut-off, then some at or after it. */
    private fun timesOf(
        gen: HistoryGen,
        settings: SchedulerSettings,
        cutoff: Long,
    ): List<Long> {
        val times = mutableListOf<Long>()
        var t = cutoff - 1 - gen.nextLong(3 * DAY_MS)
        repeat(gen.nextInt(MAX_OPS)) {
            times += t
            t = minOf(cutoff - 1, t + gen.nextLong(3 * DAY_MS / MAX_OPS + 1))
        }
        var u = if (gen.chance(30)) cutoff else cutoff + gen.nextLong(settings.epochMs + DAY_MS)
        repeat(gen.nextInt(MAX_OPS)) {
            times += u
            u += gen.nextLong(settings.epochMs / 3 + 1)
        }
        return times
    }

    private fun historyOf(gen: HistoryGen): History {
        val settings = settingsOf(gen)
        val n = gen.nextLong(2001) - 1000
        val cutoff = Epochs.epochStart(settings, n) - settings.graceMs
        val firstFields = mutableMapOf<Int, Fields>()
        val nextFields = mutableMapOf<Op, Fields>()
        val ops = mutableListOf<Op>()
        val held = mutableMapOf<Int, Fields>()
        for (time in timesOf(gen, settings, cutoff)) {
            if (held.isEmpty() || (held.size < ids.size && gen.chance(25))) {
                // A new item: its first write.
                val index = ids.indices.first { it !in held }
                held[index] = fieldsOf(gen, cutoff).also { firstFields[index] = it }
                ops += Op(time, index, Op.Kind.Edit)
                continue
            }
            val index = gen.pick(held.keys.toList())
            val op = Op(time, index, gen.pick(Op.Kind.entries))
            if (op.kind == Op.Kind.Edit) {
                held[index] = changed(gen, held.getValue(index), cutoff).also { nextFields[op] = it }
            }
            ops += op
        }
        return History(settings, n, cutoff, cutoff + settings.epochMs, ops, firstFields, nextFields)
    }

    /** Applies [ops] to [repo] from an empty account, the clock set to each op's time. */
    private suspend fun apply(
        repo: RoomLibraryRepository,
        history: History,
        ops: List<Op>,
    ) {
        repo.forget(ALICE)
        repo.useAccount(ALICE)
        repo.select(ALICE, setOf(MOVIES, SHOWS))
        val current = mutableMapOf<Int, Fields>()
        for (op in ops) {
            clock.atMs(op.atMs)
            val fields =
                when {
                    op.item !in current -> history.firstFields.getValue(op.item)
                    op.kind == Op.Kind.Edit -> history.nextFields.getValue(op)
                    else -> current.getValue(op.item)
                }
            current[op.item] = fields
            when (op.kind) {
                Op.Kind.Tombstone -> repo.tombstone(ALICE, listOf(ItemId(ids[op.item])))
                Op.Kind.Revive -> repo.revive(ALICE, listOf(ItemId(ids[op.item])))
                Op.Kind.NameOnly -> repo.put(ALICE, fields.library, mediaItem(op.item, fields, "renamed"))
                else -> repo.put(ALICE, fields.library, mediaItem(op.item, fields, "first"))
            }
        }
    }

    private fun Collection<ScheduleItem>.withoutDeletionTimes() = map { it.copy(deletedAtMs = null) }

    private suspend fun read(
        repo: RoomLibraryRepository,
        cutoffMs: Long,
    ) = repo.scheduleItems(ALICE, setOf(MOVIES), Instant.fromEpochMilliseconds(cutoffMs))

    /** Runs one case. Returns true when day n + 1's inputs differ from day n's. */
    private fun check(history: History): Boolean =
        runBlocking {
            val prefix = history.ops.filter { it.atMs < history.cutoff }
            val modelFull = model(history, history.ops)
            apply(full, history, history.ops)
            apply(cut, history, prefix)

            // 1. Day n's items are the model's, and a history cut at the cut-off gives the same ones.
            val dayN = read(full, history.cutoff)
            assertEquals("day n items against the model", expected(modelFull, history.cutoff), dayN)
            val dayNCut = read(cut, history.cutoff)
            assertEquals(expected(model(history, prefix), history.cutoff), dayNCut)
            // An item this TV met for the first time after the cut-off is not an edit: by the oldest-copy rule it is in
            // the read (the model above says so), so the comparison with the cut history is over the known ones.
            val known = model(history, prefix).keys.map { ids[it] }.toSet()
            val dayNKnown = dayN.filter { it.id.value in known }
            assertEquals("day n items, history cut", dayNCut.withoutDeletionTimes(), dayNKnown.withoutDeletionTimes())

            // 2. buildDay makes the same day from both.
            val built = buildDay(history.settings, dayNKnown, history.n).blocks
            assertEquals("the day built", buildDay(history.settings, dayNCut, history.n).blocks, built)

            // 3. Day n + 1 sees the edits that were held back.
            val dayN1 = read(full, history.nextCutoff)
            assertEquals("day n + 1 items against the model", expected(modelFull, history.nextCutoff), dayN1)
            val upToNext = history.ops.filter { it.atMs < history.nextCutoff }
            apply(cut, history, upToNext)
            val knownNext = model(history, upToNext).keys.map { ids[it] }.toSet()
            assertEquals(
                "day n + 1 items, history cut at its cut-off",
                read(cut, history.nextCutoff).withoutDeletionTimes(),
                dayN1.filter { it.id.value in knownNext }.withoutDeletionTimes(),
            )
            full.assertIntervalsContiguous(ALICE)
            dayN.withoutDeletionTimes() != dayN1.withoutDeletionTimes()
        }

    @Test
    fun anEditSeenAtOrAfterTheCutoffNeverChangesDayNAndIsUsedFromDayNPlusOne() {
        var differing = 0
        var withLateOps = 0
        for (index in 0 until CASES) {
            val seed = splitmix64(BASE_SEED + index.toULong())
            try {
                val history = historyOf(HistoryGen(seed))
                if (history.ops.any { it.atMs >= history.cutoff }) withLateOps++
                if (check(history)) differing++
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                val rerun = "HistoryGen(${seed}uL)"
                val message = "EditCutoffPropertyTest: case $index failed, seed $seed ($rerun): ${failure.message}"
                println(message)
                throw AssertionError(message, failure)
            }
        }
        println(
            "EditCutoffPropertyTest: $CASES cases, $withLateOps with an op at or after the cut-off, " +
                "$differing where day n + 1 differs from day n",
        )
        // Not vacuous: a good share of the histories really do change the inputs between day n and day n + 1.
        assertTrue("only $differing cases changed between day n and n + 1", differing > CASES / 10)
    }

    private companion object {
        const val CASES = 1000
        const val BASE_SEED = 0x5EED_0902uL
        const val MAX_OPS = 14
        const val DAY_MS = 86_400_000L
    }
}
