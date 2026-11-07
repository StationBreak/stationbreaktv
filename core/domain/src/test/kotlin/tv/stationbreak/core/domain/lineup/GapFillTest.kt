// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GapFillTest {
    private val minute = 60_000L
    private val channel = ChannelId("laughs")
    private val start = 1_760_000_000_000L

    private fun item(
        n: Int,
        minutes: Long,
    ) = ScheduleItem(id(n), ScheduleItemKind.Movie, minutes * minute, ADDED_MS, null, null, 2000, null, null, null)

    @Suppress("LongParameterList") // The pinned formula's inputs, one for one.
    private fun pick(
        pool: List<ScheduleItem>,
        gapMinutes: Long = 60,
        exclude: Set<CanonicalId> = emptySet(),
        on: ChannelId = channel,
        at: Long = start,
        airable: (ScheduleItem) -> Boolean = { true },
    ) = GapFill.pick(on, pool, at, at + gapMinutes * minute, exclude, airable)

    @Test
    fun longestThatFits_thenNextLongest() {
        val pool = listOf(item(1, 50), item(2, 45), item(3, 30), item(4, 10))

        val pieces = pick(pool)

        assertEquals(listOf(id(1), id(4)), pieces.map { it.item.id })
        assertEquals(start, pieces[0].startMs)
        assertEquals(start + 50 * minute, pieces[0].endMs)
        assertEquals(pieces[0].endMs, pieces[1].startMs)
        assertEquals(start + 60 * minute, pieces[1].endMs)
    }

    @Test
    fun nothingFits_empty() {
        assertEquals(emptyList(), pick(listOf(item(1, 22), item(2, 45)), gapMinutes = 20))
    }

    @Test
    fun neverExcludedOrRepeated() {
        val pool = listOf(item(1, 10), item(2, 10), item(3, 10), item(4, 10))

        val pieces = pick(pool, gapMinutes = 120, exclude = setOf(id(2)), airable = { it.id != id(3) })

        assertEquals(setOf(id(1), id(4)), pieces.map { it.item.id }.toSet())
        assertEquals(2, pieces.size, "each item once, though more time is left")
    }

    @Test
    fun sameInputsSameResult_anyPoolOrder() {
        val pool = (1..9).map { item(it, 20) }

        assertEquals(pick(pool), pick(pool))
        assertEquals(pick(pool), pick(pool.reversed()))
        assertEquals(pick(pool), pick(pool.shuffled(java.util.Random(7))))
    }

    @Test
    fun tieBrokenByChannelAndSlotStart() {
        val pool = (1..20).map { item(it, 30) }
        val first = { on: ChannelId, at: Long -> pick(pool, gapMinutes = 30, on = on, at = at).single().item.id }

        val byChannel = ('a'..'h').map { first(ChannelId("ch-$it"), start) }.toSet()
        val bySlot = (0 until 8).map { first(channel, start + it * 30 * minute) }.toSet()

        assertTrue(byChannel.size > 1, "another channel breaks the tie another way")
        assertTrue(bySlot.size > 1, "another slot start breaks the tie another way")
        assertNotEquals(emptySet(), byChannel)
    }

    @Test
    fun exclusionsAreTheGoneIdTheItemsWithNoRowAndTheFixPieces() {
        val pool = (1..5).map { item(it, 30) }

        val live = setOf(id(1), id(2), id(3), id(4))

        val excluded = fillExclusions(pool, live = live, gone = id(9), fixPieces = setOf(id(2)))

        assertEquals(setOf(id(9), id(5), id(2)), excluded)
    }

    @Test
    fun pinnedOrder_forAFixedChannelSlotAndEqualRuntimes() {
        val pool = (1..8).map { item(it, 30) }

        val picked = pick(pool, gapMinutes = 90, on = ChannelId("pinned-channel"), at = 1_760_000_000_000L)

        assertEquals(PINNED, picked.map { it.item.id })
    }

    private companion object {
        val PINNED: List<CanonicalId> = listOf(id(4), id(7), id(1))
    }
}
