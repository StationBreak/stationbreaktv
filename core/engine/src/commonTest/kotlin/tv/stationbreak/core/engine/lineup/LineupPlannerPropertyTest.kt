// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.SeededGen
import tv.stationbreak.core.engine.forAllCases
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planner over 500 seeded histories: a first plan, then refreshes with changing fills, user changes in between
 * (hiding, renumbering, editing a starter, making it custom, a channel that could not be read) and sometimes a newer
 * catalog that drops a recipe and adds others, possibly at a dropped recipe's number. Every result must be the same
 * for the same inputs, keep every channel's number, keep the planner's own picks within `PLANNER_PICKS`, never switch
 * on an opt-in recipe, never change a hidden or switched-on channel's state, never touch an edited, custom or unusable
 * channel, and leave a newer catalog waiting while collections are not synced.
 */
class LineupPlannerPropertyTest {
    // Numbers recipes may take: every band except the leftovers numbers.
    private val numbers = (1..598).filter { it != 99 && it != 199 }

    // How many edited, custom or unusable channels were compared across a plan.
    private var untouchedChecks = 0

    // How many switch-ons and renumbers the generated viewer made.
    private var pinsMade = 0

    @Test
    fun thePlannerKeepsItsPromisesOverRefreshes() {
        var capped = 0
        var finalisedCases = 0
        var newerCatalogs = 0
        var deferredCatalogs = 0
        forAllCases("lineup", 0x4C494E45uL, CASES) { gen ->
            var current: StarterCatalogView = catalogOf(gen)
            var lineup: Lineup? = null
            repeat(ROUNDS) { round ->
                if (round > 0 && gen.chance(30)) {
                    current = newerCatalog(gen, current)
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
                    check(it, next, snapshot.collectionsSynced, now)
                    val waits = it.finalised && !snapshot.collectionsSynced && current.version > it.catalogVersion
                    if (waits) deferredCatalogs++
                }
                checkAlways(next, current)
                if (next.channels.count { it.state == ChannelState.On } >= LineupDefaults.PLANNER_PICKS) capped++
                lineup = userChanges(gen, next, current, now)
            }
            if (lineup?.finalised == true) finalisedCases++
        }
        // The generators must reach every path, or the checks prove little about them.
        assertTrue(capped > 40, "plans that reached the planner's picks: $capped")
        assertTrue(finalisedCases > 200, "finalised cases: $finalisedCases")
        assertTrue(newerCatalogs > 200, "newer catalogs: $newerCatalogs")
        assertTrue(deferredCatalogs > 30, "newer catalogs that waited for collections: $deferredCatalogs")
        assertTrue(untouchedChecks > 300, "edited, custom or unusable channels checked: $untouchedChecks")
        assertTrue(pinsMade > 300, "viewer pins made through the edits: $pinsMade")
    }

    private fun checkAlways(
        lineup: Lineup,
        catalog: StarterCatalogView,
    ) {
        val on = lineup.channels.filter { it.state == ChannelState.On }
        // The seat rule: the planner's free picks plus the pinned starters inside the top are at most
        // PLANNER_PICKS. Which pins are inside the top is not visible here, so the free picks (untouched, not
        // chosen by the viewer, not leaving) are held to the bound, which the in-top pins only tighten. Custom
        // and edited channels and the pins outside the top sit on top and are not counted.
        val free =
            on.count {
                val source = it.source as? ChannelSource.Starter
                source?.edited == false && !it.chosenByViewer && it.leavesAtMs == null
            }
        assertTrue(free <= LineupDefaults.PLANNER_PICKS, "the planner's free picks: $free")
        checkRoomIsFull(lineup, catalog, free)
        // Every pin stays on, and never leaves.
        for (pin in lineup.channels.filter { it.chosenByViewer && it.source is ChannelSource.Starter }) {
            val edited = (pin.source as ChannelSource.Starter).edited
            if (pin.state == ChannelState.On && !edited) assertNull(pin.leavesAtMs, "${pin.id} pinned yet leaving")
        }
        val optIn = optInIds(catalog)
        assertTrue(on.none { it.id in optIn && !it.chosenByViewer }, "the planner switched an opt-in recipe on")
        val numbers = lineup.channels.mapNotNull { it.number }
        assertEquals(numbers.toSet().size, numbers.size, "numbers unique")
        if (!lineup.finalised) assertTrue(on.isEmpty(), "an unfinalised lineup has nothing on")
    }

    // The room is the planner's own and never reduced by a switch-on: when a free starter that qualified is on
    // the shelf for want of room (BelowCap), the room is full: the free picks and the pins the viewer touched, which
    // are the only ones that can sit inside the top, reach it. A viewer's added starter takes no seat.
    private fun checkRoomIsFull(
        lineup: Lineup,
        catalog: StarterCatalogView,
        free: Int,
    ) {
        val missed =
            lineup.channels.any {
                val source = it.source as? ChannelSource.Starter
                source?.edited == false && it.state == ChannelState.Shelf && it.number != null &&
                    it.viewerChoice == ViewerChoice.None && it.shelfReason == ShelfReason.BelowCap
            }
        // A newer catalog waiting for its collection pass holds the on-set (`PlannerSelection.Mode.Hold`): the
        // seats the viewer's switch-offs free are not filled until it applies.
        if (!missed || catalog.version > lineup.catalogVersion) return
        val optIn = optInIds(catalog)
        val touched =
            lineup.channels.count {
                val source = it.source as? ChannelSource.Starter
                val pinned = it.state == ChannelState.On && it.viewerChoice == ViewerChoice.Touched
                source?.edited == false && pinned && it.id !in optIn
            }
        assertTrue(free + touched >= LineupDefaults.PLANNER_PICKS, "a free starter missed a room of $free+$touched")
        // With no touched pin, nothing but the planner's own picks can fill the room, so it is full exactly: no added
        // starter (never ranked) has taken a seat, however many there are.
        if (touched == 0) assertEquals(LineupDefaults.PLANNER_PICKS, free, "an addition took a seat")
    }

    // An on channel keeps its time; the planner's unpick stamps the plan's time, so it is never mistaken for the
    // viewer's switch-off of a pick that waits (a time ahead).
    private fun checkTime(
        old: LineupChannel,
        new: LineupChannel,
        backOnShelf: Boolean,
        selectingAt: Long,
    ) {
        if (backOnShelf) {
            assertEquals(selectingAt, new.onSinceMs, "${old.id} unpicked")
        } else if (old.state == ChannelState.On) {
            assertEquals(old.onSinceMs, new.onSinceMs, "${old.id} added")
        }
    }

    private fun optInIds(catalog: StarterCatalogView): Set<ChannelId> =
        catalog.recipes
            .filter { it.optIn }
            .map { LineupIds.starter(it.key) }
            .toSet()

    private fun check(
        before: Lineup,
        after: Lineup,
        collectionsSynced: Boolean,
        selectingAt: Long,
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
            val neverAired = old.state == ChannelState.On && old.onSinceMs > selectingAt
            val backOnShelf = neverAired && new.state == ChannelState.Shelf
            if (old.state != ChannelState.Shelf && !backOnShelf) assertEquals(old.state, new.state, "${old.id} state")
            checkTime(old, new, backOnShelf, selectingAt)
            // Without a full collection pass nothing is resolved again, except a starter that is switched on.
            val switchedOn = old.state == ChannelState.Shelf && new.state == ChannelState.On
            if (!collectionsSynced && !switchedOn) assertEquals(old.source, new.source, "${old.id} unsynced source")
            checkUntouched(old, new)
        }
    }

    // The planner selects over themed recipes, as a shipped catalog's are.
    private fun catalogFrom(
        recipes: List<Recipe>,
        version: Int,
    ): StarterCatalogView {
        val theme = Theme(emptyList(), emptyList(), null, false)
        return TestCatalog(recipes.map { it.copy(ordering = OrderingMode.Curated, theme = theme) }, version = version)
    }

    private fun catalogOf(gen: SeededGen): StarterCatalogView {
        // A few catalogs are big enough to reach the cap; the rest are small, so the test stays quick on linuxX64.
        val count = if (gen.chance(BIG_PERCENT)) gen.nextInt(55..65) else gen.nextInt(3..12)
        val picked = numbers.shuffledBy(gen).take(count)
        return catalogFrom(picked.mapIndexed { i, n -> recipeOf("k$i", n, optIn = gen.chance(5)) }, 1)
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
        return catalogFrom(kept + added, version)
    }

    private fun fillsOf(
        gen: SeededGen,
        catalog: StarterCatalogView,
    ): Map<String, Int> {
        // Big catalogs fill well, so most of them pass the cap.
        val fills = if (catalog.recipes.size > LineupDefaults.PLANNER_PICKS) 90 else 70
        return catalog.recipes.associate {
            it.key to if (gen.chance(fills)) gen.nextInt(QUALIFYING_ITEMS..QUALIFYING_ITEMS + 6) else gen.nextInt(0..23)
        }
    }

    // The viewer's own edits, through the real edit code, so `chosenByViewer` pins occur: a switch-on of a shelved or
    // hidden starter (an opt-in one, confirmed, now and then), a switch-off and a renumber. A refused edit changes
    // nothing.
    private fun viewerEdits(
        gen: SeededGen,
        lineup: Lineup,
        catalog: StarterCatalogView,
        now: Long,
    ): Lineup {
        var edited = lineup
        val boundary = nextDayStart(now, lineup.utcOffsetMinutes)
        val taken = lineup.channels.flatMap { listOfNotNull(it.defaultNumber, it.numberOverride) }.toMutableSet()
        for (channel in lineup.channels) {
            val edit = viewerEditOf(gen, channel, taken)
            val outcome = edit?.let { LineupEdits.apply(edited, it, now, boundary, catalog) }
            if (outcome is EditOutcome.Applied) {
                edited = outcome.lineup
                pinsMade++
            }
        }
        return edited
    }

    // The edit the viewer makes to [channel], if any: an unedited starter is switched on (shelved or hidden), off or
    // renumbered, now and then.
    private fun viewerEditOf(
        gen: SeededGen,
        channel: LineupChannel,
        taken: MutableSet<Int>,
    ): LineupEdit? {
        val starter = channel.source as? ChannelSource.Starter
        val off = channel.state == ChannelState.Shelf || channel.state == ChannelState.UserHidden
        return when {
            starter == null || starter.edited -> {
                null
            }

            off && gen.chance(PIN_PERCENT) -> {
                LineupEdit.SwitchOn(channel.id, confirmedOptIn = gen.chance(PERCENT / 2))
            }

            channel.state == ChannelState.On && gen.chance(PIN_PERCENT / 2) -> {
                LineupEdit.SwitchOff(channel.id)
            }

            channel.state == ChannelState.On && gen.chance(PIN_PERCENT) -> {
                LineupEdit.SetNumber(channel.id, (700..899).first { it !in taken }.also { taken += it })
            }

            else -> {
                null
            }
        }
    }

    private fun userChanges(
        gen: SeededGen,
        lineup: Lineup,
        catalog: StarterCatalogView,
        now: Long,
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
        return viewerEdits(gen, lineup.copy(channels = changed), catalog, now)
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
        const val PIN_PERCENT = 4
    }
}
