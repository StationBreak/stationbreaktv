// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 26, schema 11: where a moved item goes, whatever order the listers are read in (the read has no ORDER BY): a
 * chosen lister, then one being added, then a held one, and the smallest id only within a class.
 */
class BestTargetsTest {
    private val held = AlsoLister("i", "a", addStamp = null, heldUntilMs = 5)
    private val adding = AlsoLister("i", "b", addStamp = 7, heldUntilMs = null)
    private val chosenLate = AlsoLister("i", "d", addStamp = null, heldUntilMs = null)
    private val chosenEarly = AlsoLister("i", "c", addStamp = null, heldUntilMs = null)

    @Test
    fun theClassComesFirst_thenTheSmallestId_inAnyOrder() {
        val all = listOf(held, adding, chosenLate, chosenEarly)
        for (order in permutations(all)) assertEquals(order.toString(), mapOf("i" to "c"), bestTargets(order))
        for (order in permutations(listOf(held, adding))) assertEquals(mapOf("i" to "b"), bestTargets(order))
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) {
            listOf(list)
        } else {
            list.flatMap { head -> permutations(list - head).map { listOf(head) + it } }
        }
}
