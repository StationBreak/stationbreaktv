// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ordering.splitmix64

/**
 * Spec 10 task 1.5.1 (Vimes H1 for `series_version`): over 1,000 random histories of edits, tombstones, revivals,
 * identical rewrites, name-only edits and a clock that stands still, steps forward and steps back, every series'
 * versions form contiguous, strictly increasing intervals, and the number of versions equals the number of changes an
 * independent model counts. A failure prints the case number and the seed: rerun it with `SeriesGen(<seed>)`.
 */
class SeriesMonotonicPropertyTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() = repository.close()

    private class SeriesGen(
        val seed: ULong,
    ) {
        private var state = seed

        fun nextULong(): ULong {
            val out = splitmix64(state)
            state += GAMMA
            return out
        }

        fun nextInt(bound: Int): Int = (nextULong() % bound.toULong()).toInt()

        fun chance(percent: Int): Boolean = nextInt(PERCENT) < percent

        fun <T> pick(options: List<T>): T = options[nextInt(options.size)]

        companion object {
            const val GAMMA = 0x9E3779B97F4A7C15uL
            const val PERCENT = 100
        }
    }

    // What the model holds of one series: the fields the change test reads, and whether it is a tombstone.
    private data class Model(
        val rating: String?,
        val genres: List<String>,
        val tags: List<String>,
        val deleted: Boolean,
    )

    @Test
    fun versionsAreContiguousAndCountedByAnIndependentModelOverRandomHistories() {
        runBlocking {
            repository.select(ALICE, setOf(MOVIES, SHOWS))
            for (case in 0 until CASES) {
                val seed = splitmix64(BASE_SEED + case.toULong())
                try {
                    runCase(case, SeriesGen(seed))
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    println("SeriesMonotonicPropertyTest: case $case failed, seed $seed (SeriesGen($seed)uL)")
                    throw failure
                }
            }
        }
    }

    private suspend fun runCase(
        case: Int,
        gen: SeriesGen,
    ) {
        val ids = (0 until SERIES_PER_CASE).map { hexId(case * SERIES_PER_CASE + it + 1) }
        val models = HashMap<String, Model>()
        val expected = HashMap<String, Int>()
        repeat(gen.nextInt(MAX_STEPS) + 1) { step(gen, ids, models, expected) }
        repository.assertSeriesIntervalsContiguous(ALICE)
        val versions = repository.seriesVersions(ALICE).groupBy { it.seriesId }
        ids.forEach { id ->
            assertEquals("versions of $id", expected[id] ?: 0, versions[id].orEmpty().size)
        }
        val stamps = versions.values.flatten().map { it.seenFromMs }
        assertTrue(stamps.isEmpty() || stamps.all { it > 0 })
    }

    // One random step: the clock moves (or not), then one series is tombstoned, revived or written.
    private suspend fun step(
        gen: SeriesGen,
        ids: List<String>,
        models: MutableMap<String, Model>,
        expected: MutableMap<String, Int>,
    ) {
        when (gen.nextInt(3)) {
            0 -> clock.stepMs(gen.nextInt(SPAN).toLong())
            1 -> clock.rewindMs(gen.nextInt(SPAN).toLong())
        }
        val id = gen.pick(ids)
        val held = models[id]
        when {
            held != null && gen.chance(TOMBSTONE_PERCENT) -> {
                repository.tombstoneSeries(ALICE, listOf(ItemId(id)))
                models[id] = held.copy(deleted = true)
            }

            held != null && held.deleted && gen.chance(REVIVE_PERCENT) -> {
                repository.reviveSeries(ALICE, listOf(ItemId(id)))
                models[id] = held.copy(deleted = false)
                expected.merge(id, 1, Int::plus)
            }

            else -> {
                val next = edit(gen, held)
                val library = gen.pick(listOf(MOVIES, SHOWS))
                val info = series(id, next.rating, next.genres, next.tags, name = "Name ${gen.nextInt(NAMES)}")
                repository.putSeries(ALICE, library, info)
                val changed = held != null && (held.copy(deleted = false) != next || held.deleted)
                if (changed) expected.merge(id, 1, Int::plus)
                models[id] = next
            }
        }
    }

    private fun edit(
        gen: SeriesGen,
        held: Model?,
    ): Model {
        val base = held ?: Model(null, emptyList(), emptyList(), false)
        return when (gen.nextInt(EDIT_KINDS)) {
            0 -> base.copy(rating = gen.pick(listOf(null, "", " ", "G", "TV-14")), deleted = false)
            1 -> base.copy(genres = gen.pick(LISTS), deleted = false)
            2 -> base.copy(tags = gen.pick(LISTS), deleted = false)
            else -> base.copy(deleted = false)
        }
    }

    private companion object {
        const val BASE_SEED = 0x5E21E5uL
        const val CASES = 1000
        const val SERIES_PER_CASE = 3
        const val MAX_STEPS = 14
        const val SPAN = 20_000
        const val NAMES = 4
        const val EDIT_KINDS = 4
        const val TOMBSTONE_PERCENT = 20
        const val REVIVE_PERCENT = 50
        val LISTS = listOf(emptyList(), listOf("A"), listOf("A", "B"), listOf("B", "A"), listOf("C"))
    }
}
