// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.model.ordering.splitmix64
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 09 Unit 1: 1,000 generated tick values show the runtime and `createdAt` the adapter hands on equal the rules
 * format's formulas, `floorDiv(RunTimeTicks, 10000)` and `floorDiv(T, 10000)`. A failure prints the seed.
 */
class InputMappingPropertyTest {
    private val edges =
        listOf(
            Long.MIN_VALUE,
            Long.MIN_VALUE + 1,
            -10_001L,
            -10_000L,
            -9_999L,
            -1L,
            0L,
            1L,
            9_999L,
            10_000L,
            10_001L,
            Long.MAX_VALUE,
        )

    @Test
    fun theRuntimeIsExactlyFloorDivOfTheTicksOrAbsent() {
        forAllCases("runtime", baseSeed = 0x5EED_0001uL) { gen ->
            val ticks = gen.ticks()
            val runtime = toMediaItem(ItemDto(id = "i", runTimeTicks = ticks)).runtime

            if (ticks > 0) {
                assertEquals(Math.floorDiv(ticks, TICKS_PER_MS), runtime?.inWholeMilliseconds, "ticks $ticks")
            } else {
                assertNull(runtime, "ticks $ticks")
            }
        }
    }

    @Test
    fun aMissingRuntimeIsAbsent() {
        assertNull(toMediaItem(ItemDto(id = "i")).runtime)
    }

    @Test
    fun createdAtIsExactlyFloorDivOfTheTicksWhateverTheOffset() {
        forAllCases("createdAt", baseSeed = 0x5EED_0002uL) { gen ->
            val ticks = gen.ticks()
            val text = gen.dateText(ticks)
            val created = toMediaItem(ItemDto(id = "i", dateCreated = text)).dateCreated

            assertEquals(Math.floorDiv(ticks, TICKS_PER_MS), created?.toEpochMilliseconds(), "ticks $ticks text $text")
        }
    }

    @Test
    fun sevenFractionDigitsAreFlooredNotRounded() {
        val created = toMediaItem(ItemDto(id = "i", dateCreated = "2026-01-02T03:04:05.1239999Z")).dateCreated

        assertEquals(Instant.parse("2026-01-02T03:04:05.123Z").toEpochMilli(), created?.toEpochMilliseconds())
    }

    @Test
    fun aTimeWithNoOffsetIsReadAsUtc() {
        val created = toMediaItem(ItemDto(id = "i", dateCreated = "2026-01-02T03:04:05.1239999")).dateCreated

        assertEquals(Instant.parse("2026-01-02T03:04:05.123Z").toEpochMilli(), created?.toEpochMilliseconds())
    }

    @Test
    fun aDateBeyondWhatMillisecondsCanHoldIsAbsentAndNeverThrows() {
        assertNull(createdAtOf("+999999999-12-31T23:59:59Z"))
        assertNull(createdAtOf("-999999999-01-01T00:00:00Z"))
        assertNull(createdAtOf("+999999999-12-31T23:59:59-18:00"))
    }

    @Test
    fun aMissingOrOddDateIsAbsent() {
        assertNull(toMediaItem(ItemDto(id = "i")).dateCreated)
        assertNull(toMediaItem(ItemDto(id = "i", dateCreated = "garbage")).dateCreated)
    }

    private fun Gen.ticks(): Long {
        val random = nextULong().toLong() shr nextInt(MAX_SHIFT)
        return if (chance(EDGE_PERCENT)) pick(edges) else random
    }

    /** [ticks] as ISO text in a random offset with 7 fraction digits, as Jellyfin writes it. */
    private fun Gen.dateText(ticks: Long): String {
        val seconds = Math.floorDiv(ticks, TICKS_PER_SECOND)
        val nanos = Math.floorMod(ticks, TICKS_PER_SECOND) * NANOS_PER_TICK
        val minutes = nextInt(-MAX_OFFSET_MINUTES..MAX_OFFSET_MINUTES)
        val offset = if (chance(OFFSET_PERCENT)) ZoneOffset.ofTotalSeconds(minutes * 60) else ZoneOffset.UTC
        val moment = OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), offset)
        // Some texts have no offset at all, which reads as UTC: the same instant, written in UTC without the "Z".
        val utc = moment.withOffsetSameInstant(ZoneOffset.UTC)
        return if (chance(ZONELESS_PERCENT)) utc.format(ZONELESS) else moment.format(FORMAT)
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val TICKS_PER_SECOND = 10_000_000L
        const val NANOS_PER_TICK = 100L
        const val EDGE_PERCENT = 15
        const val OFFSET_PERCENT = 70
        const val MAX_SHIFT = 60
        const val MAX_OFFSET_MINUTES = 1080
        const val ZONELESS_PERCENT = 10
        val ZONELESS: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSS")
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSXXX")
    }
}

private const val GAMMA = 0x9E3779B97F4A7C15uL

/** A deterministic generator on `splitmix64`, in the style of `core:scheduler`'s `SeededGen`. */
private class Gen(
    val seed: ULong,
) {
    private var state = seed

    fun nextULong(): ULong {
        val out = splitmix64(state)
        state += GAMMA
        return out
    }

    fun nextInt(bound: Int): Int = (nextULong() % bound.toULong()).toInt()

    fun nextInt(range: IntRange): Int = range.first + nextInt(range.last - range.first + 1)

    fun chance(percent: Int): Boolean = nextInt(100) < percent

    fun <T> pick(options: List<T>): T = options[nextInt(options.size)]
}

private fun forAllCases(
    name: String,
    baseSeed: ULong,
    cases: Int = 1000,
    check: (Gen) -> Unit,
) {
    for (index in 0 until cases) {
        val seed = splitmix64(baseSeed + index.toULong())
        try {
            check(Gen(seed))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            val message = "$name: case $index failed, seed $seed (Gen($seed)uL): ${failure.message}"
            println(message)
            throw AssertionError(message, failure)
        }
    }
}
