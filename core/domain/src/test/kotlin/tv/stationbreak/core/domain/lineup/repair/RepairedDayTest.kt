// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.scheduler.composedDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Synthetic fixtures only.
class RepairedDayTest {
    private val dayStart = NOW_MS - 3 * 3_600_000L
    private val dayEnd = NOW_MS + 6 * 3_600_000L

    private fun program(
        start: Long,
        end: Long,
        item: Int,
        media: Long = 0L,
    ) = ScheduleBlock(start, end, ScheduleContent.Program(id(item), media))

    private fun original() =
        listOf(
            program(dayStart, NOW_MS, 1),
            program(NOW_MS, NOW_MS + 2 * HALF_HOUR_MS, 90, 5_000L),
            program(NOW_MS + 2 * HALF_HOUR_MS, dayEnd, 2),
        )

    private fun compose(
        repair: ChannelRepair?,
        fixes: List<LostFix> = emptyList(),
        original: List<ScheduleBlock> = original(),
    ) = RepairedDay.compose(dayStart, dayEnd, original, repair, fixes)

    private fun assertCovers(blocks: List<ScheduleBlock>) {
        composedDay(1L, dayStart, dayEnd, blocks, emptyList())
    }

    @Test
    fun noRepair_isTheOriginal() {
        val original = original()
        val composed = compose(null, original = original)
        assertEquals(original, composed.blocks)
        assertTrue(composed.fixRemainders.isEmpty())
    }

    @Test
    fun currentProgrammeKeepsItsSlot_onEveryChannel() {
        for (i in 0 until 50) {
            val at = NOW_MS - 2 * 3_600_000L + i * 400_000L
            val repair = repairOf(channel = "starter:c$i", atMs = at, firstItem = 10 + i)
            val on = compose(repair).blocks.single { it.contains(at) }
            val pin = repair.blocks.first()
            assertEquals(pin.startMs, on.startMs)
            assertEquals(pin.endMs, on.endMs)
            assertEquals(ScheduleContent.Program(pin.itemId!!, pin.mediaStartMs), on.content)
            assertEquals(repair.pinEndMs, on.endMs)
        }
    }

    @Test
    fun noGapNoOverlap_coversTheDay() {
        for (i in 0 until 50) {
            val repair = repairOf(atMs = NOW_MS - 2 * 3_600_000L + i * 400_000L)
            assertCovers(compose(repair).blocks)
        }
        // A repair that starts at the day's first instant has no past slate.
        val first = repairOf(atMs = dayStart + 60_000L, dayEndMs = dayEnd)
        assertEquals(dayStart, first.keptFromMs)
        val blocks = compose(first).blocks
        assertEquals(first.blocks.size, blocks.size)
        assertCovers(blocks)
    }

    @Test
    fun composeReadsOnlyTheRecord() {
        val repair = repairOf()
        val junk = listOf(program(dayStart, dayEnd, 77))
        val withJunk = compose(repair, original = junk)
        val withOriginal = compose(repair)
        assertEquals(withOriginal.blocks, withJunk.blocks)
        assertEquals(ScheduleContent.Slate, withJunk.blocks.first().content)
    }

    @Test
    fun fixReplacesOnlyItsBlock_remainderMarked() {
        val repair = repairOf(atMs = NOW_MS + 600_000L)
        val tail = repair.blocks[1]
        val gone = id(91)
        val goneRepair =
            repair.copy(
                blocks =
                    repair.blocks.toMutableList().also {
                        it[1] = tail.copy(itemId = gone, mediaStartMs = 7_000L)
                    },
            )
        val half = tail.startMs + HALF_HOUR_MS / 3
        val fix =
            LostFix(
                repair.channel,
                1L,
                dayEnd,
                tail.startMs,
                tail.endMs,
                gone,
                listOf(KeptBlock(tail.startMs, half, id(50), 0L)),
                0L,
            )
        val composed = compose(goneRepair, listOf(fix))
        assertCovers(composed.blocks)
        assertEquals(setOf(half), composed.fixRemainders)
        val mine = composed.blocks.filter { it.startMs >= tail.startMs && it.endMs <= tail.endMs }
        assertEquals(
            listOf(
                ScheduleContent.Program(id(50), 0L),
                ScheduleContent.Program(gone, 7_000L + (half - tail.startMs)),
            ),
            mine.map { it.content },
        )
        assertEquals(half, mine[1].startMs)
        assertEquals(tail.endMs, mine[1].endMs)
        // Everything else is the record's.
        val others = composed.blocks.filter { it !in mine }
        val untouched = compose(goneRepair).blocks.filter { it.startMs < tail.startMs || it.startMs >= tail.endMs }
        assertEquals(untouched, others)
    }

    @Test
    fun fixThatCoversTheBlock_hasNoRemainder() {
        val fix = fixOf(blockStartMs = NOW_MS, lengthMs = 2 * HALF_HOUR_MS, gone = 90)
        val composed = compose(null, listOf(fix))
        assertCovers(composed.blocks)
        assertTrue(composed.fixRemainders.isEmpty())
        assertEquals(ScheduleContent.Slate, composed.blocks.first { it.startMs == NOW_MS + HALF_HOUR_MS }.content)
    }

    @Test
    fun fixWithNoPieces_wholeBlockIsRemainder() {
        val fix = fixOf(blockStartMs = NOW_MS, gone = 90).copy(pieces = emptyList())
        val composed = compose(null, listOf(fix))
        assertEquals(original(), composed.blocks)
        assertEquals(setOf(NOW_MS), composed.fixRemainders)
    }

    @Test
    fun fixWithOverrunningOrGappedPieces_leavesBlockWhole() {
        val base = fixOf(blockStartMs = NOW_MS, lengthMs = 2 * HALF_HOUR_MS, gone = 90)
        val overrun =
            base.copy(pieces = listOf(KeptBlock(NOW_MS, NOW_MS + 3 * HALF_HOUR_MS, id(50), 0L)))
        val gapped =
            base.copy(
                pieces =
                    listOf(
                        KeptBlock(NOW_MS, NOW_MS + HALF_HOUR_MS / 3, id(50), 0L),
                        KeptBlock(NOW_MS + HALF_HOUR_MS / 2, NOW_MS + HALF_HOUR_MS, id(51), 0L),
                    ),
            )
        for (bad in listOf(overrun, gapped)) {
            val composed = compose(null, listOf(bad))
            assertEquals(original(), composed.blocks)
            assertTrue(composed.fixRemainders.isEmpty())
        }
    }

    @Test
    fun fixWhoseBlockTheRepairReplaced_isIgnored() {
        val repair = repairOf(atMs = NOW_MS + 600_000L)
        assertEquals(NOW_MS, repair.blocks.first().startMs)
        val fix = fixOf(blockStartMs = NOW_MS, lengthMs = 2 * HALF_HOUR_MS, gone = 90)
        val composed = compose(repair, listOf(fix))
        assertEquals(compose(repair).blocks, composed.blocks)
        assertTrue(composed.fixRemainders.isEmpty())
    }

    @Test
    fun twoFixesSameBlock_lastWins_deterministically() {
        val first = fixOf(blockStartMs = NOW_MS, gone = 90, piece = 50)
        val second = fixOf(blockStartMs = NOW_MS, gone = 90, piece = 51)

        fun pieceOf(fixes: List<LostFix>) = compose(null, fixes).blocks.first { it.startMs == NOW_MS }.content
        assertEquals(ScheduleContent.Program(id(51), 0L), pieceOf(listOf(first, second)))
        assertEquals(ScheduleContent.Program(id(50), 0L), pieceOf(listOf(second, first)))
        assertEquals(pieceOf(listOf(first, second)), pieceOf(listOf(first, second)))
    }

    @Test
    fun staleFix_isIgnored() {
        val repair = repairOf()
        val wrongTime = fixOf(blockStartMs = NOW_MS + 7 * 60_000L, gone = 11)
        val next = repair.blocks[1]
        val wrongItem = fixOf(blockStartMs = next.startMs, lengthMs = next.endMs - next.startMs, gone = 99)
        val composed = compose(repair, listOf(wrongTime, wrongItem))
        assertEquals(compose(repair).blocks, composed.blocks)
        assertTrue(composed.fixRemainders.isEmpty())
    }

    @Test
    fun behindSittingBeforeKeptWindow_readsSlate() {
        val repair = repairOf(atMs = NOW_MS + 600_000L)
        val past = compose(repair).blocks.first()
        assertEquals(ScheduleBlock(dayStart, repair.keptFromMs, ScheduleContent.Slate), past)
    }

    @Test
    fun recordThatIsNotContiguous_fallsBackToTheOriginal() {
        val repair = repairOf()
        val gap = repair.copy(blocks = repair.blocks.filterIndexed { i, _ -> i != 2 })
        val short = repair.copy(blocks = repair.blocks.dropLast(1))
        val flat = repair.blocks[1].let { it.copy(endMs = it.startMs) }
        val zero = repair.copy(blocks = repair.blocks.toMutableList().also { it[1] = flat })
        val backwards = repair.copy(keptFromMs = dayStart - 1)
        val original = original()
        for (bad in listOf(gap, short, zero, backwards, repair.copy(blocks = emptyList()))) {
            val composed = compose(bad, original = original)
            assertEquals(original, composed.blocks)
            assertTrue(composed.fixRemainders.isEmpty())
        }
    }
}
