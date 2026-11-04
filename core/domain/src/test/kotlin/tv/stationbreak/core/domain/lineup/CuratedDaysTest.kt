// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.Curated.boundaryMs
import tv.stationbreak.core.domain.lineup.Curated.channel
import tv.stationbreak.core.domain.lineup.Curated.ids
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.EpochPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A curated starter airs its homes and guests, with the share cap's window and the weights (spec 23, Task 21). */
class CuratedDaysTest {
    private val days = LineupDays(TestCatalog)
    private val noon = NOON.toEpochMilliseconds()
    private val tomorrow = noon + Curated.DAY_MS
    private val laughs = Curated.laughsId.value
    private val whodunit = Curated.whodunitId.value
    private val westerns = Curated.westernsId.value
    private val odds = "leftovers:odds-and-ends"

    // The curation's own view of a starter of [TestCatalog] in the lineup, as the day build makes it.
    private fun curationChannel(
        key: String,
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): CurationChannel {
        val recipe = TestCatalog.recipes.first { it.key == key }
        val pool = PoolEvaluator.evaluate(rules, snapshot, PoolPolicy.GrownUp).items
        val settings = rules.toSchedulerSettings()
        return CurationChannel(ChannelId("starter:$key"), recipe.number, recipe, false, rules, settings, pool)
    }

    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ): Lineup {
        val outcome = LineupEdits.apply(lineup, edit, noon, boundaryMs, TestCatalog)
        return assertIs<EditOutcome.Applied>(outcome).lineup
    }

    @Test
    fun aCuratedChannelAirsOnlyItsHomesAndGuests() {
        val lineup = Curated.lineup()
        val built = days.curatedDays(lineup, noon)
        val snapshot = Curated.snapshotAt(built.dayOf(laughs).let { Curated.todayStartMs - it.settings.graceMs })
        val on =
            listOf(
                curationChannel("laughs", Curated.laughs, snapshot),
                curationChannel("whodunit", Curated.whodunit, snapshot),
                curationChannel("westerns", Curated.westerns, snapshot),
            )
        val curated = Curation.curate(snapshot, on)

        for (channel in on) {
            val day = built.dayOf(channel.id.value)
            val pool = curated.pools.getValue(channel.id)
            // Films are never trimmed: they are exactly the curated ones.
            val films = pool.items.filter { it.seriesId == null }.mapTo(HashSet()) { it.id }
            assertEquals(films, day.pool.filter { it.seriesId == null }.mapTo(HashSet()) { it.id }, channel.id.value)
            assertTrue(day.ids().all { id -> pool.items.any { it.id == id } }, "only its members: ${channel.id}")
            assertEquals(pool.weights.entries, day.weights.entries, "the weights reach the day")
        }
        // Each film that fits two channels airs at its home at weight 2, and on the other only as a guest (weight 1).
        for (film in Curated.comedyCrime) {
            val title = film.item.id
            val home = checkNotNull(curated.homes.homeOf(title)).value
            assertTrue(title in built.dayOf(home).ids(), "at its home")
            assertEquals(2, built.dayOf(home).weights[title])
            val other = if (home == laughs) whodunit else laughs
            if (title in built.dayOf(other).ids()) assertEquals(1, built.dayOf(other).weights[title], "a guest")
        }
        assertTrue(
            built
                .dayOf(laughs)
                .weights.entries
                .isNotEmpty(),
        )
    }

    @Test
    fun theTrimWindowReachesTheDay() {
        val today = days.curatedDays(Curated.lineup(), noon).dayOf(laughs)
        val next = days.curatedDays(Curated.lineup(), tomorrow).dayOf(laughs)
        val episodes = ids(Curated.showAEpisodes)

        val aired = today.pool.map { it.id }.filter { it in episodes }
        assertTrue(aired.isNotEmpty() && aired.size < Curated.EPISODES, "a capped series airs a window: ${aired.size}")
        // The window is the day's: the next day airs another.
        assertNotEquals(aired, next.pool.map { it.id }.filter { it in episodes })
        // The window is the scheduler's own over the epoch pool, applied once.
        val epoch = today.pool.filter { it.seriesId == null } + Curated.showAEpisodes.map { it.item }
        val windowed = EpochPool.dayPool(EpochPool.build(today.settings, epoch, today.n), today.n, keepsOf(today))
        assertEquals(windowed.filter { it.id in episodes }.map { it.id }, aired)
        // The layout airs nothing but the day pool.
        val blocks = today.day.blocks.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId }
        assertTrue(blocks.isNotEmpty() && blocks.all { it in today.ids() })
    }

    // The keeps the curation gives laughs today.
    private fun keepsOf(day: ChannelDay) =
        run {
            val snapshot = Curated.snapshotAt(Curated.todayStartMs - day.settings.graceMs)
            val on =
                listOf(
                    curationChannel("laughs", Curated.laughs, snapshot),
                    curationChannel("whodunit", Curated.whodunit, snapshot),
                    curationChannel("westerns", Curated.westerns, snapshot),
                )
            Curation
                .pools(snapshot, on)
                .getValue(Curated.laughsId)
                .keeps
                .also { assertTrue(!it.isEmpty) }
        }

    @Test
    fun theLeftoversNeverAirATrimmedItem() {
        val lineup = Curated.lineup()
        val scheduled = days.scheduled(lineup, noon, null)
        val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { Curated.snapshotAt(it) }
        val built = days.days(lineup, scheduled, snapshots)

        val episodes = ids(Curated.showAEpisodes)
        assertTrue(built.dayOf(laughs).ids().count { it in episodes } < Curated.EPISODES, "some are trimmed")
        assertTrue(built.dayOf(odds).ids().none { it in episodes }, "no trimmed episode is left over")
        assertEquals(ids(Curated.documentary), built.dayOf(odds).ids())
        assertEquals(0, days.airingNowhere(lineup, scheduled, snapshots), "a trimmed episode is not airing nowhere")
    }

    @Test
    fun aCustomChannelIsNeverCurated() {
        val mine =
            channel("custom-2", 610, ChannelState.On, ChannelSource.Custom(programmed("Mine", Source.Genre("Comedy"))))
        val lineup = Curated.lineup().let { it.copy(channels = it.channels + mine) }

        val built = days.curatedDays(lineup, noon)

        val day = built.dayOf("custom-2")
        val comedy = Curated.comedy + Curated.comedyCrime + Curated.comedyWestern + Curated.showAEpisodes
        assertEquals(ids(comedy), day.ids(), "its whole pool, every episode")
        assertTrue(day.weights.isEmpty)
        // And it moves no curated channel: laughs airs as it does without it.
        assertEquals(days.curatedDays(Curated.lineup(), noon).dayOf(laughs).aired(), built.dayOf(laughs).aired())
    }

    @Test
    fun aSwitchMidDayLeavesOtherChannelsTodayUnchanged() {
        val before = Curated.lineup(westernsState = ChannelState.Shelf)
        val today = days.curatedDays(before, noon)

        for (edit in listOf(LineupEdit.SwitchOff(Curated.whodunitId), LineupEdit.SwitchOn(Curated.westernsId))) {
            val after = applied(before, edit)
            assertTrue(after.curationBasis != null, "the switch waits for the boundary: $edit")
            val changed = (edit as LineupEdit.ChannelEdit).id.value
            val now = days.curatedDays(after, noon)
            for (day in today.filter { it.channel.id.value != changed }) {
                assertEquals(day.aired(), now.dayOf(day.channel.id.value).aired(), "${day.channel.id} after $edit")
            }
        }
        // From the boundary each change applies: with whodunit hidden, laughs homes every comedy-crime film; with
        // westerns on, the western films leave the leftovers.
        val switchedOff = applied(before, LineupEdit.SwitchOff(Curated.whodunitId))
        val off = days.curatedDays(switchedOff.rolled(boundaryMs), tomorrow)
        assertTrue(ids(Curated.comedyCrime).all { it in off.dayOf(laughs).ids() })
        val on = days.curatedDays(applied(before, LineupEdit.SwitchOn(Curated.westernsId)).rolled(boundaryMs), tomorrow)
        assertTrue(ids(Curated.western).all { it in days.curatedDays(before, tomorrow).dayOf(odds).ids() })
        assertTrue(ids(Curated.western).none { it in on.dayOf(odds).ids() })
    }

    @Test
    fun aShelvedStarterAirsUntilTheBoundary() {
        val before = Curated.lineup()
        val on = setOf(Curated.laughsId, Curated.whodunitId, Curated.westernsId)
        // The planner dropped whodunit: it leaves at the boundary, and the basis keeps today's on-set.
        val leaving =
            before.copy(
                channels =
                    before.channels.map {
                        if (it.id == Curated.whodunitId) it.copy(leavesAtMs = boundaryMs) else it
                    },
                curationBasis = CurationBasis(boundaryMs, on, true),
            )

        val today = days.curatedDays(before, noon)
        val now = days.curatedDays(leaving, noon)

        assertEquals(today.map { it.channel.id }, now.map { it.channel.id })
        for (day in today) assertEquals(day.aired(), now.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        val next = days.curatedDays(leaving, tomorrow)
        assertTrue(next.none { it.channel.id == Curated.whodunitId }, "gone at the boundary")
        assertTrue(ids(Curated.crime).all { it in next.dayOf(odds).ids() }, "its own films go to the leftovers then")
    }

    @Test
    fun aNewChannelTodayAirsWhatTheBasisDidNotHome() {
        val before = Curated.lineup(westernsState = ChannelState.Shelf)
        val after = applied(before, LineupEdit.SwitchOn(Curated.westernsId))

        val day = days.curatedDays(after, noon).dayOf(westerns)

        // Its western films, but not those the basis's comedy channel homes.
        assertEquals(ids(Curated.western), day.ids())
        assertTrue(day.weights.entries.all { it.second == 2 }, "weight 2 each")
        assertEquals(ids(Curated.western), day.weights.entries.mapTo(HashSet()) { it.first })
        // Tomorrow it is curated with the others: the comedy-western films have one home among them.
        val next = days.curatedDays(after.rolled(boundaryMs), tomorrow)
        for (film in Curated.comedyWestern) {
            val airs = listOf(laughs, westerns).count { film.item.id in next.dayOf(it).ids() }
            assertTrue(airs >= 1, "each airs at its home")
        }
    }

    // LOW-2 (Task 20's review): a starter switched on in the middle of a day airs its candidates minus the basis
    // homes. When every one of its titles is homed on a basis channel it has nothing until the boundary. Pinned; see
    // the report.
    @Test
    fun aStarterSwitchedOnMidDayIsEmptyTodayOnlyWhenTheBasisHomesAllOfIt() {
        val before = Curated.lineup(westernsState = ChannelState.Shelf)
        val after = applied(before, LineupEdit.SwitchOn(Curated.westernsId))
        assertTrue(
            days
                .curatedDays(after, noon)
                .dayOf(westerns)
                .pool
                .isNotEmpty(),
            "it has titles of its own",
        )

        // A comedy-western channel whose every title is a candidate of the basis's comedy channel.
        val comedyOnly = programmed("Westerns", Source.Genre("Comedy"))
        val onlyShared =
            after.copy(
                channels =
                    after.channels.map {
                        val source = it.source
                        if (it.id == Curated.westernsId && source is ChannelSource.Starter) {
                            it.copy(source = source.copy(rules = comedyOnly))
                        } else {
                            it
                        }
                    },
            )
        val empty = days.curatedDays(onlyShared, noon).dayOf(westerns)
        assertTrue(empty.pool.isEmpty())
        assertEquals(HiddenReason.EmptyToday, empty.hidden)
    }

    @Test
    fun aMidDaySwitchLeavesOtherChannelsPlayKeysUnchanged() {
        val before = Curated.lineup(westernsState = ChannelState.Shelf)
        for (edit in listOf(LineupEdit.SwitchOff(Curated.whodunitId), LineupEdit.SwitchOn(Curated.westernsId))) {
            val after = applied(before, edit)
            val changed = (edit as LineupEdit.ChannelEdit).id
            for (channel in before.channels.filter { it.id != changed }) {
                val other = after.channels.first { it.id == channel.id }
                val key = days.playKeyOf(channel, before, noon)
                assertEquals(key, days.playKeyOf(other, after, noon), "${channel.id} $edit")
            }
            // At the boundary the curated channels' keys follow the new on-set.
            val rolled = after.rolled(boundaryMs)
            assertNotEquals(
                days.playKeyOf(before.channel(laughs), before, tomorrow),
                days.playKeyOf(rolled.channel(laughs), rolled, tomorrow),
                "$edit",
            )
        }
    }

    @Test
    fun leftoversClaimExactlyTheF5Union() {
        // Until the boundary the leftovers claim the custom channel's day pool and the basis on-set's curated claims:
        // laughs', and whodunit's though it is not scheduled today; never westerns', which is not in the basis.
        val lineup =
            Curated.lineup(whodunitState = ChannelState.Shelf).copy(
                curationBasis = CurationBasis(boundaryMs, setOf(Curated.laughsId, Curated.whodunitId), true),
            )

        val today = days.curatedDays(lineup, noon)

        assertTrue(today.none { it.channel.id == Curated.whodunitId })
        val claimedToday =
            ids(Curated.horror) + ids(Curated.comedy + Curated.crime + Curated.comedyCrime + Curated.comedyWestern) +
                ids(Curated.showAEpisodes)
        assertEquals(ids(Curated.items) - claimedToday, today.dayOf(odds).ids())
        assertEquals(ids(Curated.western + Curated.documentary), today.dayOf(odds).ids())

        // From the boundary: the custom channel's day pool and the scheduled curated channels' claims.
        val next = days.curatedDays(lineup.rolled(boundaryMs), tomorrow)
        val claimedNext =
            ids(Curated.horror) + ids(Curated.comedy + Curated.western + Curated.comedyCrime + Curated.comedyWestern) +
                ids(Curated.showAEpisodes)
        assertEquals(ids(Curated.items) - claimedNext, next.dayOf(odds).ids())
        assertEquals(ids(Curated.crime + Curated.documentary), next.dayOf(odds).ids())
    }

    // More than the cap of channels can be on until the boundary (a switch-on now, a leaving starter): the curation
    // takes every one of them, and the leftovers claim from all.
    @Test
    fun moreThanTheCapOnUntilTheBoundaryStillCurates() {
        val genres = (0 until MANY).map { "Genre $it" }
        val catalog = ManyCatalog(genres)
        val many = LineupDays(catalog)
        val films =
            genres.flatMapIndexed { g, genre -> (0 until 6).map { movie(3000 + g * 10 + it, genre, rating = "R") } }
        val shared = (0 until 4).map { movie(2900 + it, genres[0], genres[1], rating = "R") }
        val lineup =
            lineupOf(
                *genres
                    .mapIndexed {
                        g,
                        genre,
                        ->
                        val rules = programmed(genre, Source.Genre(genre))
                        starter(catalog.recipes[g].key, 300 + g, ChannelState.On, rules)
                    }.toTypedArray(),
                leftovers("odds-and-ends", 199, null),
            )
        val all = (films + shared + Curated.documentary).sortedBy { it.item.id.value }
        val scheduled = many.scheduled(lineup, noon, null)
        assertTrue(scheduled.size > tv.stationbreak.core.engine.lineup.LineupDefaults.CAP)
        val snapshots =
            scheduled.map { it.cutoffMs }.distinct().associateWith {
                RuleSnapshot(it, setOf(LIBRARY_ID), all, emptyMap(), emptyMap(), collectionsSynced = true)
            }

        val built = many.days(lineup, scheduled, snapshots)

        assertTrue(built.filter { it.channel.source is ChannelSource.Starter }.all { it.pool.isNotEmpty() })
        assertEquals(ids(Curated.documentary), built.dayOf(odds).ids())
        val sharedIds = ids(shared)
        val first = built.dayOf("starter:${catalog.recipes[0].key}").ids()
        val second = built.dayOf("starter:${catalog.recipes[1].key}").ids()
        assertTrue(sharedIds.all { it in first || it in second })
        assertEquals(0, many.airingNowhere(lineup, scheduled, snapshots))
    }

    private class ManyCatalog(
        genres: List<String>,
    ) : tv.stationbreak.core.engine.lineup.StarterCatalogView by TestCatalog {
        override val recipes =
            genres.mapIndexed { g, genre ->
                tv.stationbreak.core.engine.lineup.Recipe(
                    "genre-$g",
                    genre,
                    "Synthetic.",
                    tv.stationbreak.core.engine.lineup.Band.Genre,
                    300 + g,
                    optIn = false,
                    sources = listOf(Source.Genre(genre)),
                    exclusions = emptyList(),
                    maxRating = null,
                    ordering = tv.stationbreak.core.model.OrderingMode.Curated,
                    collectionHints = emptyList(),
                )
            }
    }

    private companion object {
        const val MANY = 52
    }
}
