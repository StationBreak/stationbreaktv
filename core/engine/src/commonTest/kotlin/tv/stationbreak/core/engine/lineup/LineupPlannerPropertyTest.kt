// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.SeededGen
import tv.stationbreak.core.engine.forAllCases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The planner over 500 seeded histories: a first plan, then refreshes with changing fills, user changes in between
 * (hiding, renumbering) and sometimes a newer catalog that drops a recipe and adds others, possibly at a dropped
 * recipe's number. Every result must be the same for the same inputs, keep every channel's number, keep the
 * switched-on count within the cap, and never switch on an opt-in recipe or change a hidden or switched-on channel's
 * state.
 */
class LineupPlannerPropertyTest {
    // Numbers recipes may take: every band except the leftovers numbers.
    private val numbers = (1..598).filter { it != 99 && it != 199 }

    @Test
    fun thePlannerKeepsItsPromisesOverRefreshes() {
        var capped = 0
        var finalisedCases = 0
        var newerCatalogs = 0
        forAllCases("lineup", 0x4C494E45uL, CASES) { gen ->
            var current: StarterCatalogView = catalogOf(gen)
            var lineup: Lineup? = null
            repeat(ROUNDS) { round ->
                if (round > 0 && gen.chance(30)) {
                    current = newerCatalog(gen, current)
                    newerCatalogs++
                }
                val snapshot = libraryOf(fillsOf(gen, current), collectionsSynced = gen.chance(70))
                val now = round * 1_000L
                val offset = gen.nextInt(-48..56) * 15
                val next = planOf(lineup, snapshot, current, nowMs = now, offset = offset)
                // Planning twice doubles the cost (linuxX64Test is slow), so determinism is checked in half the rounds.
                if (gen.chance(50)) {
                    assertEquals(next, planOf(lineup, snapshot, current, nowMs = now, offset = offset), "same inputs")
                }
                lineup?.let { check(it, next) }
                checkAlways(next, current)
                if (next.channels.count { it.state == ChannelState.On } == LineupDefaults.CAP) capped++
                lineup = userChanges(gen, next)
            }
            if (lineup?.finalised == true) finalisedCases++
        }
        // The generators must reach the cap, finalising and catalog updates, or the checks prove little about them.
        assertTrue(capped > 40, "plans that reached the cap: $capped")
        assertTrue(finalisedCases > 200, "finalised cases: $finalisedCases")
        assertTrue(newerCatalogs > 200, "newer catalogs: $newerCatalogs")
    }

    private fun checkAlways(
        lineup: Lineup,
        catalog: StarterCatalogView,
    ) {
        val on = lineup.channels.filter { it.state == ChannelState.On }
        assertTrue(on.size <= LineupDefaults.CAP, "on: ${on.size}")
        val optIn =
            catalog.recipes
                .filter { it.optIn }
                .map { LineupIds.starter(it.key) }
                .toSet()
        assertTrue(on.none { it.id in optIn }, "an opt-in recipe is on")
        val numbers = lineup.channels.mapNotNull { it.number }
        assertEquals(numbers.toSet().size, numbers.size, "numbers unique")
        if (!lineup.finalised) assertTrue(on.isEmpty(), "an unfinalised lineup has nothing on")
    }

    private fun check(
        before: Lineup,
        after: Lineup,
    ) {
        if (!before.finalised) return
        assertEquals(before.utcOffsetMinutes, after.utcOffsetMinutes, "offset")
        assertTrue(after.catalogVersion >= before.catalogVersion, "catalog version lowered")
        val afterById = after.channels.associateBy { it.id }
        for (old in before.channels) {
            val new = afterById[old.id] ?: throw AssertionError("${old.id} dropped")
            assertEquals(old.defaultNumber, new.defaultNumber, "${old.id} default number")
            assertEquals(old.number, new.number, "${old.id} number")
            if (old.state != ChannelState.Shelf) assertEquals(old.state, new.state, "${old.id} state")
            if (old.state == ChannelState.On) assertEquals(old.addedAtMs, new.addedAtMs, "${old.id} added")
        }
    }

    private fun catalogOf(gen: SeededGen): StarterCatalogView {
        // A few catalogs are big enough to reach the cap; the rest are small, so the test stays quick on linuxX64.
        val count = if (gen.chance(BIG_PERCENT)) gen.nextInt(55..65) else gen.nextInt(3..12)
        val picked = numbers.shuffledBy(gen).take(count)
        return TestCatalog(picked.mapIndexed { i, n -> recipeOf("k$i", n, optIn = gen.chance(5)) })
    }

    private fun newerCatalog(
        gen: SeededGen,
        catalog: StarterCatalogView,
    ): StarterCatalogView {
        val version = catalog.version + 1
        val kept = catalog.recipes.filter { catalog.recipes.size < 2 || !gen.chance(10) }
        val keptNumbers = kept.map { it.number }.toSet()
        // A dropped recipe's number is still held by its channel (now custom), so a new recipe there must not collide.
        val dropped = catalog.recipes.map { it.number }.filter { it !in keptNumbers }
        val added =
            List(gen.nextInt(0..2)) {
                val number = if (dropped.isNotEmpty() && gen.chance(50)) gen.pick(dropped) else gen.pick(numbers)
                recipeOf("v${version}n$it", number)
            }.distinctBy { it.number }.filter { it.number !in keptNumbers }
        return TestCatalog(kept + added, version = version)
    }

    private fun fillsOf(
        gen: SeededGen,
        catalog: StarterCatalogView,
    ): Map<String, Int> {
        // Big catalogs fill well, so most of them pass the cap.
        val fills = if (catalog.recipes.size > LineupDefaults.CAP) 90 else 70
        return catalog.recipes.associate {
            it.key to if (gen.chance(fills)) gen.nextInt(QUALIFYING_ITEMS..QUALIFYING_ITEMS + 6) else gen.nextInt(0..23)
        }
    }

    private fun userChanges(
        gen: SeededGen,
        lineup: Lineup,
    ): Lineup {
        if (!lineup.finalised) return lineup
        val taken = lineup.channels.flatMap { listOfNotNull(it.defaultNumber, it.numberOverride) }.toSet()
        val free = (600..899).filter { it !in taken }.iterator()
        val changed =
            lineup.channels.map { channel ->
                when {
                    gen.chance(5) -> channel.copy(state = ChannelState.UserHidden)
                    gen.chance(5) && channel.number != null -> channel.copy(numberOverride = free.next())
                    else -> channel
                }
            }
        return lineup.copy(channels = changed)
    }

    private fun <T> List<T>.shuffledBy(gen: SeededGen): List<T> {
        val copy = toMutableList()
        for (i in copy.indices.reversed()) {
            val j = gen.nextInt(i + 1)
            val t = copy[i]
            copy[i] = copy[j]
            copy[j] = t
        }
        return copy
    }

    private companion object {
        const val CASES = 500
        const val ROUNDS = 4
        const val BIG_PERCENT = 8
    }
}
