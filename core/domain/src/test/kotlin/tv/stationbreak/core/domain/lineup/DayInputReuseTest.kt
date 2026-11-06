// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #589: a day taken over from the build of an earlier lineup must be exactly the day a full build gives. [DayInput] is
 * a list of what a day reads, kept by hand beside `inputsOf`; this checks it over every fixture and every edit, at more
 * than one moment, with the extras filter on and off, across catalog changes, and then drops each of its fields in
 * turn to show that a test fails for it (a field no scenario pins would be a read nothing guards).
 */
class DayInputReuseTest {
    private val hour = 3_600_000L
    private val noon = NOON.toEpochMilliseconds()
    private val afterBoundary = noon + 26 * hour

    private val shorts = (950..952).map { movie(it, "Comedy", "Crime", rating = "PG", runtimeMs = 30_000L) }
    private val boxId = id(900)
    private val boxFilms = (810..821).map { movie(it, "Misc", rating = "R") }
    private val shortFilms = (960..965).map { movie(it, "Shorts", rating = "PG", runtimeMs = 30_000L) }
    private val items =
        (fillingLibrary() + CuratedLibrary.items + shorts + shortFilms + boxFilms).sortedBy { it.item.id.value }
    private val none = emptySet<tv.stationbreak.core.model.CanonicalId>()
    private val collections =
        mapOf(boxId to RuleCollection(boxId, boxFilms.map { it.item.id }.toSet(), none, none, none, "Box"))

    private fun snapshot(cutoff: Long): RuleSnapshot =
        RuleSnapshot(cutoff, setOf(LIBRARY_ID), items, emptyMap(), collections, collectionsSynced = true)

    private val v3 = programmedCatalog(3, selects = true)

    // Recipes that fit other genres: the curation homes the comedies with whodunit instead of laughs.
    private val v3Laughs =
        programmedCatalog(
            3,
            selects = true,
            change = {
                when (it.key) {
                    "laughs" -> it.copy(sources = listOf(Source.Genre("Horror")))
                    "whodunit" -> it.copy(sources = listOf(Source.Genre("Crime"), Source.Genre("Comedy")))
                    else -> it
                }
            },
        )
    private val v3OptIn =
        programmedCatalog(
            3,
            selects = true,
            change = { if (it.optIn) it.copy(sources = listOf(Source.Tag("late"), Source.Genre("Horror"))) else it },
        )

    // An opt-in themed recipe anchored on a household collection: a leftovers day withholds it as the flag says.
    private val boxed =
        Recipe(
            "boxed",
            "Boxed",
            "A box set.",
            Band.Premium,
            591,
            optIn = true,
            sources = listOf(Source.Genre("Drama")),
            exclusions = emptyList(),
            maxRating = null,
            ordering = OrderingMode.Curated,
            collectionHints = emptyList(),
            theme = Theme(listOf(Anchor(AnchorKind.Collection, NameMatcher.Exact("Box"))), emptyList(), null, false),
        )
    private val v3Box = programmedCatalog(3, selects = true, extra = listOf(boxed))

    // Whodunit ahead of laughs in the ties of the curation (a recipe's number breaks them).
    private val v3Order =
        programmedCatalog(
            3,
            selects = true,
            change = { if (it.key == "whodunit") it.copy(band = Band.General, number = 105) else it },
        )
    private val v3Kid =
        programmedCatalog(3, selects = true, change = { if (it.key == "toons") it.copy(number = 11) else it })

    /** One lineup as one build sees it. */
    private class Side(
        val catalog: StarterCatalogView,
        val lineup: Lineup,
        val atMs: Long,
        val extras: Boolean = false,
    )

    private class Built(
        val side: Side,
        val days: List<ChannelDay>,
        val inputs: Map<ChannelId, LineupDays.DayInput>,
    ) {
        val byId = days.associateBy { it.channel.id }
    }

    private fun describe(day: ChannelDay): List<Any?> = listOf(day.aired(), day.ceilingRank, day.settings, day.n)

    private fun build(
        side: Side,
        earlier: Built? = null,
    ): Built {
        val days = LineupDays(side.catalog)
        val scheduled = days.scheduled(side.lineup, side.atMs, if (side.extras) 0L else null)
        val inputs = days.dayInputsOf(side.lineup, scheduled, side.atMs)
        val reused = HashMap<ChannelId, ChannelDay>()
        if (earlier != null) {
            for (s in scheduled) {
                val before = earlier.inputs[s.channel.id]
                if (before != null && before == inputs[s.channel.id]) {
                    reused[s.channel.id] = earlier.byId.getValue(s.channel.id)
                }
            }
        }
        val cutoffs = scheduled.map { it.cutoffMs }.distinct()
        val plain = cutoffs.associateWith { snapshot(it) }
        val filtered = plain.mapValues { ExtrasFilter.filter(it.value).snapshot }
        return Built(side, days.days(side.lineup, scheduled, plain, filtered, reused = reused), inputs)
    }

    private val memo = HashMap<Triple<Lineup, Pair<StarterCatalogView, Long>, Boolean>, Built>()

    private fun full(side: Side): Built {
        val key = Triple(side.lineup, side.catalog to side.atMs, side.extras)
        return memo.getOrPut(key) { build(side) }
    }

    // The fixtures: ranked starters, v2 customs, kid band, hidden, shelf, retired leftovers; and programmed starters.
    private val ranked =
        lineupOf(
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("frights", 310, ChannelState.UserHidden, rules("Frights", Source.Genre("Horror"))),
            starter("westerns", 220, ChannelState.Shelf, rules("Westerns", Source.Genre("Western"))),
            starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Tag("late"))),
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            channel("custom-2", 610, ChannelState.On, ChannelSource.Custom(rules("Shorts", Source.Genre("Shorts")))),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
            leftovers("retired-odds", 299, null),
        )
    private val programmedLineup =
        lineupOf(
            starter("toons", 10, ChannelState.On, programmed("Toons", Source.Genre("Animation")).copy(maxRating = "G")),
            starter("laughs", 110, ChannelState.On, programmed("Laughs", Source.Genre("Comedy"))),
            starter(
                "whodunit",
                210,
                ChannelState.On,
                programmed("Whodunit", Source.Genre("Crime"), Source.Genre("Comedy")),
            ),
            starter("westerns", 220, ChannelState.Shelf, programmed("Westerns", Source.Genre("Western"))),
            starter("frights", 310, ChannelState.UserHidden, programmed("Frights", Source.Genre("Horror"))),
            starter("after-hours", 590, ChannelState.Shelf, programmed("After Hours", Source.Tag("late"))),
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
        )

    // No starter at all: nothing but a custom channel and the leftovers, so a leftovers day reads the catalog's opt-in
    // recipes as resolved, and no unedited starter's key moves with the household flag.
    private val bare =
        lineupOf(
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null),
        )

    // The same, with a leftovers channel that starts at the next boundary: it claims all the same.
    private val bareStarting =
        lineupOf(
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            leftovers("kids-odds-and-ends", 99, "PG"),
            leftovers("odds-and-ends", 199, null).copy(onSinceMs = noon + 18 * hour),
        )
    private val fixtures = listOf(ranked, programmedLineup, bare, bareStarting)

    private val numbers = listOf(5, 50, 98, 99, 120, 199, 250, 600)

    private fun editsOf(lineup: Lineup): List<LineupEdit> =
        lineup.channels.flatMap { channel ->
            val id = channel.id
            val holders = lineup.channels.mapNotNull { it.number?.let { n -> n to it.id } }
            listOf(
                LineupEdit.SwitchOn(id, confirmedOptIn = true),
                LineupEdit.SwitchOff(id),
                LineupEdit.ClearNumber(id),
            ) +
                numbers.map { LineupEdit.SetNumber(id, it) } +
                holders.filter { it.second != id }.map { (n, holder) -> LineupEdit.SetNumber(id, n, swapWith = holder) }
        }

    private fun applied(
        lineup: Lineup,
        edit: LineupEdit,
    ): Lineup? = (LineupEdits.apply(lineup, edit, noon, noon + 24 * hour, TestCatalog) as? EditOutcome.Applied)?.lineup

    private fun withChannel(
        lineup: Lineup,
        id: String,
        change: (tv.stationbreak.core.engine.lineup.LineupChannel) -> tv.stationbreak.core.engine.lineup.LineupChannel,
    ) = lineup.copy(channels = lineup.channels.map { if (it.id.value == id) change(it) else it })

    // Changes the edits do not make: the flag, the basis, a rules change, the server, the offset, a leave time.
    private fun changesOf(lineup: Lineup): List<Pair<String, Lineup>> {
        val starters = lineup.channels.filter { it.source is ChannelSource.Starter }.map { it.id.value }
        val own = lineup.channels.firstOrNull { it.state == ChannelState.On && it.source is ChannelSource.Starter }?.id
        val out = ArrayList<Pair<String, Lineup>>()
        out += "household" to lineup.copy(useMyCollections = !lineup.useMyCollections)
        out += "server" to lineup.copy(serverId = CanonicalId.parse("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0"))
        out += "offset" to lineup.copy(utcOffsetMinutes = 60)
        for (on in listOf(emptySet(), setOfNotNull(own), starters.map { ChannelId(it) }.toSet())) {
            for (household in listOf(true, false)) {
                val basis = CurationBasis(noon + 18 * hour, on, household)
                out += "basis $on $household" to lineup.copy(curationBasis = basis)
            }
        }
        for (id in starters) {
            out += "leaves $id" to withChannel(lineup, id) { it.copy(leavesAtMs = noon + 18 * hour) }
            out += "starts later $id" to withChannel(lineup, id) { it.copy(onSinceMs = noon + 18 * hour) }
            for (genre in listOf("Crime", "Western", "Comedy")) {
                out += "rules $id $genre" to
                    withChannel(lineup, id) {
                        val source = it.source as ChannelSource.Starter
                        it.copy(source = source.copy(rules = source.rules.copy(sources = listOf(Source.Genre(genre)))))
                    }
                out += "pending rules $id $genre" to
                    withChannel(lineup, id) {
                        val source = it.source as ChannelSource.Starter
                        it.copy(source = source.withPrevious(rules("Old", Source.Genre(genre)), noon + 18 * hour))
                    }
            }
            out += "edited $id" to
                withChannel(lineup, id) { it.copy(source = (it.source as ChannelSource.Starter).copy(edited = true)) }
        }
        for (genre in listOf("Documentary", "Comedy")) {
            out += "custom $genre" to
                withChannel(lineup, "custom-1") {
                    it.copy(source = ChannelSource.Custom(rules("Mine", Source.Genre(genre))))
                }
        }
        if (own != null) out += "hide" to withChannel(lineup, own.value) { it.copy(state = ChannelState.UserHidden) }
        out += "unhide" to withChannel(lineup, "starter:frights") { it.copy(state = ChannelState.On) }
        out += "opt in" to withChannel(lineup, "starter:after-hours") { it.copy(state = ChannelState.On) }
        out += "shelve" to withChannel(lineup, "starter:laughs") { it.copy(state = ChannelState.Shelf) }
        return out
    }

    private class Pair2(
        val name: String,
        val old: Built,
        val new: Built,
    )

    // Every (earlier build, later build) the edits and changes lead from, with both built in full.
    private val corpus: List<Pair2> by lazy {
        val out = ArrayList<Pair2>()
        for (catalog in listOf(v3, v3Box)) {
            for (at in listOf(noon, afterBoundary)) {
                for (fixture in fixtures) {
                    val old = full(Side(catalog, fixture, at))
                    val next = ArrayList<Pair<String, Lineup>>()
                    for (edit in editsOf(fixture)) applied(fixture, edit)?.let { next += "edit $edit" to it }
                    next += changesOf(fixture)
                    val random = Random(fixture.channels.size + at)
                    repeat(SEQUENCES) {
                        var lineup = fixture
                        repeat(STEPS) {
                            val edits = editsOf(lineup)
                            lineup = applied(lineup, edits[random.nextInt(edits.size)]) ?: lineup
                            next += "sequence" to lineup
                        }
                    }
                    for ((name, lineup) in next) out += Pair2(name, old, full(Side(catalog, lineup, at)))
                    // The same lineup under another catalog, another moment's extras filter, and the next day.
                    for (other in listOf(v3, v3Box, v3Laughs, v3OptIn, v3Kid, v3Order).filter { it !== catalog }) {
                        out +=
                            Pair2("catalog", old, full(Side(other, fixture, at)))
                    }
                    out += Pair2("extras", old, full(Side(catalog, fixture, at, extras = true)))
                    out += Pair2("day", old, full(Side(catalog, fixture, at + 24 * hour)))
                }
            }
        }
        out
    }

    private fun sameDay(
        a: Built,
        b: Built,
        id: ChannelId,
    ) = describe(a.byId.getValue(id)) == describe(b.byId.getValue(id))

    // The channels of a pair whose inputs are equal under [view] but whose full-build days differ: a stale day.
    private fun stale(view: (LineupDays.DayInput) -> LineupDays.DayInput): List<String> =
        corpus.flatMap { p ->
            p.new.inputs.keys
                .filter { id ->
                    val before = p.old.inputs[id]
                    before != null && view(before) == view(p.new.inputs.getValue(id)) && !sameDay(p.old, p.new, id)
                }.map { "${p.name}: $it" }
        }

    @Test
    fun aDayWithEqualInputsIsTheDayAFullBuildGives() {
        val stale = stale { it }
        assertTrue(stale.isEmpty(), "stale days:\n${stale.take(10).joinToString("\n")}")
        val equal =
            corpus.sumOf { p ->
                p.new.inputs.keys
                    .count { p.old.inputs[it] == p.new.inputs[it] }
            }
        assertTrue(equal >= MIN_EQUAL, "only $equal days had equal inputs: the test would prove little")
    }

    @Test
    fun theReusePathGivesTheDaysOfAFullBuild() {
        var reusedDays = 0
        for (p in corpus) {
            val taken = build(p.new.side, earlier = p.old)
            assertEquals(p.new.days.map { it.channel.id }, taken.days.map { it.channel.id }, p.name)
            for (day in p.new.days) {
                val got = describe(taken.byId.getValue(day.channel.id))
                assertEquals(describe(day), got, "${p.name}: ${day.channel.id}")
            }
            reusedDays +=
                p.new.inputs.keys
                    .count { p.old.inputs[it] == p.new.inputs[it] }
        }
        assertTrue(reusedDays >= MIN_EQUAL)
    }

    @Test
    fun aCuratedDayIsTakenOverWhenTheEditLeavesItsCurationAlone() {
        val old = full(Side(v3, programmedLineup, noon))
        val renumbered = LineupEdit.SetNumber(LineupIds.starter("laughs"), 150)
        val edited = full(Side(v3, checkNotNull(applied(programmedLineup, renumbered)), noon))
        val custom =
            full(
                Side(
                    v3,
                    withChannel(programmedLineup, "custom-1") {
                        it.copy(source = ChannelSource.Custom(rules("Mine", Source.Genre("Documentary"))))
                    },
                    noon,
                ),
            )
        val curated = listOf("laughs", "whodunit", "toons").map { LineupIds.starter(it) }
        for (id in curated) {
            assertEquals(old.inputs[id], edited.inputs[id], "a renumber moves nothing: $id")
            assertEquals(old.inputs[id], custom.inputs[id], "a custom channel's rules are no curated member: $id")
        }
        val taken = build(custom.side, earlier = old)
        for (id in curated) assertEquals(describe(custom.byId.getValue(id)), describe(taken.byId.getValue(id)))
    }

    @Test
    fun theChangesThatMoveADayChangeItsInput() {
        val old = full(Side(v3, programmedLineup, noon))

        fun differs(
            change: String,
            id: String,
        ) {
            val lineup = changesOf(programmedLineup).first { it.first == change }.second
            val after = full(Side(v3, lineup, noon)).inputs[ChannelId(id)]
            assertNotEquals(old.inputs[ChannelId(id)], after, "$change: $id")
        }
        differs("household", "leftovers:odds-and-ends")
        differs("household", "starter:laughs")
        differs("hide", "leftovers:odds-and-ends")
        differs("unhide", "leftovers:odds-and-ends")
        differs("opt in", "leftovers:odds-and-ends")
        differs("rules starter:laughs Crime", "starter:whodunit")
        differs("basis ${setOf(ChannelId("starter:toons"))} true", "starter:laughs")
    }

    @Test
    fun aLeftoversChannelThatStartsLaterStillClaimsInTheOrder() {
        val old = full(Side(v3, ranked, noon))
        val later = withChannel(ranked, "leftovers:retired-odds") { it.copy(onSinceMs = noon + 18 * hour) }
        val side = full(Side(v3, later, noon))
        assertNotEquals(
            old.inputs[LineupIds.leftovers("odds-and-ends")]?.claims,
            side.inputs[LineupIds.leftovers("odds-and-ends")]?.claims,
            "a leftovers channel that starts later is one the others' claims read",
        )
    }

    @Test
    fun theRealSwapTakesOverTheDaysOfTheChannelsOnTheAir() {
        // The upgrade launch: an older lineup built with this build's catalog, then the plan under that catalog.
        val old = lineupOfOlderCatalog()
        val planned =
            LineupPlanner.plan(
                old,
                SERVER,
                USER,
                0,
                CuratedLibrary.snapshotAt(noon),
                CollectionNames(emptyMap()),
                noon,
                boundaryMs,
                v3,
            )
        val before = full(Side(v3, old, noon))
        val after = full(Side(v3, planned, noon))
        val taken = build(after.side, earlier = before)
        val reused = after.inputs.keys.filter { before.inputs[it] == after.inputs[it] }
        assertTrue(reused.isNotEmpty(), "days are taken over")
        for (day in after.days) {
            assertEquals(describe(day), describe(taken.byId.getValue(day.channel.id)), "${day.channel.id}")
        }
    }

    private fun lineupOfOlderCatalog(): Lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("westerns", 220, ChannelState.On, rules("Westerns", Source.Genre("Western"))),
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            leftovers("odds-and-ends", 199, null),
        )

    // Each field of a day input dropped in turn: a field is pinned when some scenario of the corpus then gives equal
    // inputs for days that are not equal (the stale day it guards against).
    private val anySettings by lazy {
        corpus
            .first()
            .old.inputs.values
            .first()
            .settings
    }

    private val drops: List<Pair<String, (LineupDays.DayInput) -> LineupDays.DayInput>> =
        listOf(
            drop("key") { it.copy(key = PlayKey("")) },
            drop("server") { it.copy(server = SERVER) },
            drop("offset") { it.copy(offset = 0) },
            drop("settings") { it.copy(settings = anySettings) },
            drop("n") { it.copy(n = 0) },
            drop("cut") { it.copy(cut = Cut(0, false, 0)) },
            drop("starting") { it.copy(starting = false) },
            drop("curation") { it.copy(curation = null) },
            drop("recipes") { it.copy(recipes = emptyList()) },
            drop("claims") { it.copy(claims = null) },
            drop("claims.channels") { it.copy(claims = it.claims?.copy(channels = emptyList())) },
            drop("claims.claimOrder") { it.copy(claims = it.claims?.copy(claimOrder = emptyList())) },
            drop("claims.household") { it.copy(claims = it.claims?.copy(household = true)) },
            drop("claims.optIn") { it.copy(claims = it.claims?.copy(optIn = emptyList())) },
            drop("claims.channel.key") { it.claimed { c -> c.copy(key = PlayKey("")) } },
            drop("claims.channel.rules") { it.claimed { c -> c.copy(rules = null) } },
            drop("claims.channel.settings") { it.claimed { c -> c.copy(settings = null) } },
            drop("claims.channel.n") { it.claimed { c -> c.copy(n = null) } },
            drop("claims.channel.cut") { it.claimed { c -> c.copy(cut = null) } },
            drop("claims.channel.starting") { it.claimed { c -> c.copy(starting = null) } },
            drop("claims.channel.curation") { it.claimed { c -> c.copy(curation = null) } },
        )

    private fun drop(
        name: String,
        f: (LineupDays.DayInput) -> LineupDays.DayInput,
    ) = name to f

    private fun LineupDays.DayInput.claimed(change: (LineupDays.ClaimedChannel) -> LineupDays.ClaimedChannel) =
        copy(claims = claims?.copy(channels = claims.channels.map(change)))

    // Groups of fields dropped together: where one field only implies another, the pair is pinned as a whole.
    private val groups: List<Pair<String, (LineupDays.DayInput) -> LineupDays.DayInput>> =
        listOf(
            drop("n+cut") { it.copy(n = 0, cut = Cut(0, false, 0)) },
            drop("timing: server+offset+settings+n+cut (own and claimed)") {
                it
                    .copy(server = SERVER, offset = 0, settings = anySettings, n = 0, cut = Cut(0, false, 0))
                    .claimed { c -> c.copy(settings = null, n = null, cut = null) }
            },
            drop("key+starting") { it.copy(key = PlayKey(""), starting = false) },
            drop("household: claims.household+curation (own and claimed)") {
                val flagless = it.copy(curation = null, claims = it.claims?.copy(household = true))
                flagless.claimed { c -> c.copy(curation = null) }
            },
            drop("claims.channel.* (all)") {
                it.claimed { c ->
                    c.copy(
                        key = PlayKey(""),
                        rules = null,
                        settings = null,
                        n = null,
                        cut = null,
                        starting = null,
                        curation = null,
                    )
                }
            },
        )

    @Test
    fun everyFieldOfADayInputIsPinnedAloneOrWithTheFieldThatImpliesIt() {
        val alone = drops.filter { (_, drop) -> stale(drop).isNotEmpty() }.map { it.first }.toSet()
        assertEquals(PINNED_ALONE, alone, "the fields a scenario of the corpus pins on its own changed")
        val implied = drops.map { it.first }.toSet() - alone
        assertEquals(IMPLIED, implied, "the fields only implied by others changed")
        for ((name, drop) in groups) assertTrue(stale(drop).isNotEmpty(), "no scenario pins the group $name")
    }

    private companion object {
        const val SEQUENCES = 3
        const val STEPS = 4
        const val MIN_EQUAL = 200

        // What dropping a field alone gives (see the test): a counterexample (pinned) or none (implied by another,
        // and pinned with it by a group): n by the cut and the cut's own day start; settings, server and offset by one
        // another; the household flag of a leftovers day by its curation (which holds the flag in force); starting by
        // the key (a later onSince is in it); curation by the key of the curated members (a
        // curated starter's key holds the curation, a leftovers channel reads it through its claimed channels' keys);
        // a claimed channel's scheduled day by its key and rules.
        val PINNED_ALONE =
            setOf("key", "cut", "recipes", "claims", "claims.channels", "claims.claimOrder", "claims.optIn")
        val IMPLIED =
            setOf(
                "server",
                "offset",
                "settings",
                "n",
                "starting",
                "curation",
                "claims.household",
                "claims.channel.key",
                "claims.channel.rules",
                "claims.channel.settings",
                "claims.channel.n",
                "claims.channel.cut",
                "claims.channel.starting",
                "claims.channel.curation",
            )
    }
}
