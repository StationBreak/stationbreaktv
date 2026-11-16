// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.SeededGen
import tv.stationbreak.core.engine.forAllCases
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The planner over 500 seeded histories: a first plan, then refreshes with changing fills, user changes in between
 * (hiding, renumbering, editing a starter, making it custom, a channel that could not be read) and sometimes a newer
 * catalog that drops a recipe and adds others, possibly at a dropped recipe's number. Every result must be the same
 * for the same inputs, keep every channel's number, keep the switched-on count within the cap (a ranked catalog) or
 * the planner's own picks within `PLANNER_PICKS` (a catalog that selects by identity), never switch on an
 * opt-in recipe, never change a hidden or switched-on channel's state, never touch an edited, custom or unusable
 * channel, and leave a newer catalog waiting while collections are not synced.
 */
class LineupPlannerPropertyTest {
    // Numbers recipes may take: every band except the leftovers numbers.
    private val numbers = (1..598).filter { it != 99 && it != 199 }

    // How many edited, custom or unusable channels were compared across a plan.
    private var untouchedChecks = 0

    @Test
    fun thePlannerKeepsItsPromisesOverRefreshes() {
        var capped = 0
        var selectingCases = 0
        var finalisedCases = 0
        var newerCatalogs = 0
        var deferredCatalogs = 0
        forAllCases("lineup", 0x4C494E45uL, CASES) { gen ->
            // Half the histories run a catalog that selects by identity (the seat rule), half a ranked one (old rule).
            val selecting = gen.chance(50)
            if (selecting) selectingCases++
            var current: StarterCatalogView = catalogOf(gen, selecting)
            var lineup: Lineup? = null
            repeat(ROUNDS) { round ->
                if (round > 0 && gen.chance(30)) {
                    current = newerCatalog(gen, current, selecting)
                    newerCatalogs++
                }
                // A kid recipe fills only with items made for children, so its items carry the Kids genre.
                val kidKeys = current.recipes.filter { it.band == Band.Kids }.mapTo(HashSet()) { it.key }
                val snapshot = libraryOf(fillsOf(gen, current), collectionsSynced = gen.chance(70), alsoKids = kidKeys)
                val now = round * 1_000L
                val offset = gen.nextInt(-48..56) * 15
                val next = planOf(lineup, snapshot, current, nowMs = now, offset = offset)
                // Planning twice doubles the cost (linuxX64Test is slow), so determinism is checked in half the rounds.
                if (gen.chance(50)) {
                    assertEquals(next, planOf(lineup, snapshot, current, nowMs = now, offset = offset), "same inputs")
                }
                lineup?.let {
                    check(it, next, snapshot.collectionsSynced, now.takeIf { selecting })
                    val waits = it.finalised && !snapshot.collectionsSynced && current.version > it.catalogVersion
                    if (waits) deferredCatalogs++
                }
                checkAlways(next, current, selecting)
                if (next.channels.count { it.state == ChannelState.On } == LineupDefaults.CAP) capped++
                lineup = userChanges(gen, next)
            }
            if (lineup?.finalised == true) finalisedCases++
        }
        // The generators must reach every path, or the checks prove little about them.
        assertTrue(selectingCases > 200, "histories on a selecting catalog: $selectingCases")
        assertTrue(capped > 40, "plans that reached the cap: $capped")
        assertTrue(finalisedCases > 200, "finalised cases: $finalisedCases")
        assertTrue(newerCatalogs > 200, "newer catalogs: $newerCatalogs")
        assertTrue(deferredCatalogs > 30, "newer catalogs that waited for collections: $deferredCatalogs")
        assertTrue(untouchedChecks > 300, "edited, custom or unusable channels checked: $untouchedChecks")
    }

    private fun checkAlways(
        lineup: Lineup,
        catalog: StarterCatalogView,
        selecting: Boolean,
    ) {
        val on = lineup.channels.filter { it.state == ChannelState.On }
        if (selecting) {
            // The seat rule: the planner's free picks and the pinned starters inside the top are at most
            // PLANNER_PICKS. No history here pins a starter (the viewer's switch-ons are not generated), so what the
            // planner picked is every untouched starter on that is not a leftovers channel; the custom and edited
            // ones sit on top and are not counted.
            // A channel leaving at the boundary is already counted out of the days after it.
            val picked = on.count { (it.source as? ChannelSource.Starter)?.edited == false && it.leavesAtMs == null }
            assertTrue(picked <= LineupDefaults.PLANNER_PICKS, "the planner's picks: $picked")
        } else {
            assertTrue(on.size <= LineupDefaults.CAP, "on: ${on.size}")
        }
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
        collectionsSynced: Boolean,
        selectingAt: Long?,
    ) {
        if (!before.finalised) return
        assertEquals(before.utcOffsetMinutes, after.utcOffsetMinutes, "offset")
        assertTrue(after.catalogVersion >= before.catalogVersion, "catalog version lowered")
        if (!collectionsSynced) assertEquals(before.catalogVersion, after.catalogVersion, "a newer catalog waits")
        val afterById = after.channels.associateBy { it.id }
        for (old in before.channels) {
            val new = afterById[old.id] ?: throw AssertionError("${old.id} dropped")
            assertEquals(old.defaultNumber, new.defaultNumber, "${old.id} default number")
            assertEquals(old.number, new.number, "${old.id} number")
            // A selecting planner puts a starter that never aired (chosen for a boundary still ahead, `onSinceMs`) back
            // on the shelf when a later plan no longer picks it; any other channel keeps its state.
            val neverAired = selectingAt != null && old.state == ChannelState.On && old.onSinceMs > selectingAt
            val backOnShelf = neverAired && new.state == ChannelState.Shelf
            if (old.state != ChannelState.Shelf && !backOnShelf) assertEquals(old.state, new.state, "${old.id} state")
            if (old.state == ChannelState.On) assertEquals(old.onSinceMs, new.onSinceMs, "${old.id} added")
            // Without a full collection pass nothing is resolved again, except a starter that is switched on.
            val switchedOn = old.state == ChannelState.Shelf && new.state == ChannelState.On
            if (!collectionsSynced && !switchedOn) assertEquals(old.source, new.source, "${old.id} unsynced source")
            checkUntouched(old, new)
        }
    }

    // A catalog that selects by identity holds only themed recipes, as a shipped one must.
    private fun catalogFrom(
        recipes: List<Recipe>,
        version: Int,
        selecting: Boolean,
    ): StarterCatalogView =
        if (selecting) {
            val theme = Theme(emptyList(), emptyList(), null, false)
            val themed = recipes.map { it.copy(ordering = OrderingMode.Curated, theme = theme) }
            TestCatalog(themed, version = version, selectsByIdentity = true)
        } else {
            TestCatalog(recipes, version = version)
        }

    private fun catalogOf(
        gen: SeededGen,
        selecting: Boolean,
    ): StarterCatalogView {
        // A few catalogs are big enough to reach the cap; the rest are small, so the test stays quick on linuxX64.
        val count = if (gen.chance(BIG_PERCENT)) gen.nextInt(55..65) else gen.nextInt(3..12)
        val picked = numbers.shuffledBy(gen).take(count)
        return catalogFrom(picked.mapIndexed { i, n -> recipeOf("k$i", n, optIn = gen.chance(5)) }, 1, selecting)
    }

    private fun newerCatalog(
        gen: SeededGen,
        catalog: StarterCatalogView,
        selecting: Boolean,
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
        return catalogFrom(kept + added, version, selecting)
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
                val placed =
                    when {
                        gen.chance(5) -> channel.copy(state = ChannelState.UserHidden)
                        gen.chance(5) && channel.number != null -> channel.copy(numberOverride = free.next())
                        else -> channel
                    }
                val source = sourceChange(gen, placed.source)
                // A channel that is no longer a starter has no day to leave on and no shelf reason (`Lineup` holds it
                // to that); a selecting planner sets both on a starter, so the user's change clears them.
                if (source is ChannelSource.Starter) {
                    placed.copy(source = source)
                } else {
                    placed.copy(source = source, leavesAtMs = null, shelfReason = null)
                }
            }
        return lineup.copy(channels = changed)
    }

    // Sometimes the user edits a starter or makes it their own, and sometimes a stored channel could not be read.
    private fun sourceChange(
        gen: SeededGen,
        source: ChannelSource,
    ): ChannelSource {
        val starter = source as? ChannelSource.Starter ?: return source
        return when (gen.nextInt(PERCENT)) {
            in 0 until 4 -> starter.copy(edited = true, rules = starter.rules.copy(name = "Edited"))
            in 4 until 6 -> ChannelSource.Custom(starter.rules)
            in 6 until 8 -> ChannelSource.Unusable("{\"unreadable\":${gen.nextInt(PERCENT)}}")
            else -> source
        }
    }

    // A source the planner must never change: an edited starter (it may only be frozen as custom when its recipe
    // leaves the catalog), a custom channel or an unreadable one. Nor does such a channel switch on by itself.
    private fun checkUntouched(
        old: LineupChannel,
        new: LineupChannel,
    ) {
        val source = old.source
        val frozen = source is ChannelSource.Custom || source is ChannelSource.Unusable
        val edited = source is ChannelSource.Starter && source.edited
        if (!frozen && !edited) return
        untouchedChecks++
        assertEquals(old.state, new.state, "${old.id} state of an edited, custom or unusable channel")
        if (edited && new.source is ChannelSource.Custom) {
            assertEquals(ChannelSource.Custom(source.rules), new.source, "${old.id} frozen")
        } else {
            assertEquals(source, new.source, "${old.id} source")
        }
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
        const val PERCENT = 100
    }
}
