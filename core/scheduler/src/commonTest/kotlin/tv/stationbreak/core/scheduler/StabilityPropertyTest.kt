// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * For one device: nothing that happens at or after the cut-off `S - G` changes day `n` (spec 08, Unit 3). The changes
 * are the ones a library can see: an item added, an item deleted (the device first sees it gone), a deletion moved to
 * another moment, and an item re-created at a later moment. Each is dated at or after the cut-off.
 */
class StabilityPropertyTest {
    private fun later(
        gen: SeededGen,
        cutoff: Long,
    ): Long = cutoff + gen.nextInt(0..(60 * 60)) * 1000L * 60L // up to 60 hours after the cut-off, in whole minutes

    /** Changes [items] with events dated at or after [cutoff]. */
    private fun mutate(
        gen: SeededGen,
        items: List<ScheduleItem>,
        cutoff: Long,
    ): List<ScheduleItem> {
        var out = items
        repeat(gen.nextInt(1..5)) {
            out =
                when (gen.nextInt(5)) {
                    0 -> out + addedItem(gen, cutoff)

                    // Deleted (first seen gone) at or after the cut-off. An item deleted earlier stays as it was.
                    1 -> out.changeOne(gen) { if (it.notDeletedBefore(cutoff)) it.copy(deletedAtMs = later(gen, cutoff)) else it }

                    // Seen again after a deletion that was itself at or after the cut-off.
                    2 -> out.changeOne(gen) { if (it.isDeletedAtOrAfter(cutoff)) it.copy(deletedAtMs = null) else it }

                    // Re-created at a later moment, but still at or after the cut-off.
                    3 -> out.changeOne(gen) { if (it.createdAtMs >= cutoff) it.copy(createdAtMs = later(gen, cutoff)) else it }

                    // A deletion moved to another moment at or after the cut-off.
                    else -> out.changeOne(gen) { if (it.isDeletedAtOrAfter(cutoff)) it.copy(deletedAtMs = later(gen, cutoff)) else it }
                }
        }
        return out
    }

    private fun addedItem(
        gen: SeededGen,
        cutoff: Long,
    ) = testItem(
        CanonicalId.parse(gen.hexId()),
        runtimeMs = gen.pick(listOf(10, 25, 40, 90)) * MS_MINUTE,
        createdAtMs = later(gen, cutoff),
    )

    private fun ScheduleItem.isDeletedAtOrAfter(cutoff: Long): Boolean = deletedAtMs.let { it != null && it >= cutoff }

    private fun ScheduleItem.notDeletedBefore(cutoff: Long): Boolean = deletedAtMs.let { it == null || it >= cutoff }

    private fun List<ScheduleItem>.changeOne(
        gen: SeededGen,
        change: (ScheduleItem) -> ScheduleItem,
    ): List<ScheduleItem> {
        if (isEmpty()) return this
        val at = gen.nextInt(size)
        return mapIndexed { i, item -> if (i == at) change(item) else item }
    }

    @Test
    fun changesAtOrAfterTheCutOffNeverChangeTheDay() {
        var withChange = 0
        forAllCases("stability", 0x57AB1EuL) { gen ->
            val case = generateCase(gen)
            val s = case.settings
            val cutoff = Epochs.epochStart(s, case.n) - s.graceMs
            // Some items are already late (created or deleted at or after the cut-off), some deleted earlier (excluded).
            val base =
                case.items.map { item ->
                    when (gen.nextInt(6)) {
                        0 -> item.copy(createdAtMs = later(gen, cutoff))
                        1 -> item.copy(deletedAtMs = later(gen, cutoff))
                        2 -> item.copy(deletedAtMs = cutoff - 1 - gen.nextInt(0..600) * MS_MINUTE)
                        else -> item
                    }
                }
            val changed = mutate(gen, base, cutoff)
            if (changed != base) withChange++
            val before = buildDay(s, base, case.n)
            val after = buildDay(s, changed, case.n)
            assertEquals(before.blocks, after.blocks, "settings $s, n ${case.n}")
        }
        assertTrue(withChange > 600, "only $withChange cases changed anything")
    }

    @Test
    fun aChangeBeforeTheCutOffDoesChangeTheDay() {
        // The control: an item created before S - G joins the pool, so the lineup of day n changes.
        val s = testSettings(alignToGrid = false, epochHours = 1, startMinuteOfDay = 0)
        val cutoff = Epochs.epochStart(s, 0L) - s.graceMs
        val base = listOf(movie('1', 20), movie('2', 20))
        val withNew = base + testItem(idOf('3'), runtimeMs = 20 * MS_MINUTE, createdAtMs = cutoff - 1)
        val control = buildDay(s, base, 0L)
        assertNotEquals(control.blocks, buildDay(s, withNew, 0L).blocks)
        val atCutoff = base + testItem(idOf('3'), runtimeMs = 20 * MS_MINUTE, createdAtMs = cutoff)
        assertEquals(control.blocks, buildDay(s, atCutoff, 0L).blocks)
    }
}
