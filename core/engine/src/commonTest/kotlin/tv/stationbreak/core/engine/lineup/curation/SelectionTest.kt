// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.QualifyingBar
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SCRIPTED_CANDIDATES = 6

class SelectionTest {
    private fun qualifying(
        lib: Library,
        genre: String,
        titles: Int = 6,
    ) = lib.itemsOf(List(titles) { lib.show(eps = 3, genres = listOf(genre)) })

    private fun stats(
        titles: Int,
        hours: Long,
        anchors: Int = 0,
        qualifies: Boolean = true,
    ) = ChannelStats(titles, hours * HOUR, hours * HOUR, anchors, qualifies)

    // A scripted homing: a channel's stats come from [statsFor] by call number and the channels asked about.
    private class Script(
        private val statsFor: (Int, List<CurationChannel>) -> Map<ChannelId, ChannelStats>,
    ) : Homing {
        val calls = ArrayList<List<ChannelId>>()
        private var known: List<CurationChannel> = emptyList()

        fun factory(
            @Suppress("UNUSED_PARAMETER") snapshot: RuleSnapshot,
            channels: List<CurationChannel>,
        ): Homing {
            known = channels
            return this
        }

        override fun homes(channels: Set<ChannelId>?): Homes {
            val chosen = known.filter { channels == null || it.id in channels }
            val ids = chosen.map { it.id }
            val stats = statsFor(calls.size, chosen)
            calls += ids
            return Homes(emptyMap(), emptyArray(), stats, ids.associateWith { SCRIPTED_CANDIDATES })
        }
    }

    private val emptySnapshot = Library().snapshot()

    private fun bareChannel(
        id: String,
        number: Int,
        pool: Boolean = true,
    ): CurationChannel {
        val lib = Library()
        return channel(id, number, listOf("Drama"), if (pool) lib.itemsOf(listOf(lib.show(eps = 1))) else emptyList())
    }

    @Test
    fun channelScoreCapsHoursAnchorsAndTitles() {
        assertEquals(1_200L + 1_500L * 2 + 100L * 7, Selection.channelScore(stats(7, 12, 2)))
        assertEquals(8_000L + 6_000L + 3_000L, Selection.channelScore(stats(99, 500, 9)))
    }

    @Test
    fun aNarrowAnchoredChannelBeatsABroadOne() {
        val lib = Library()
        val broadPool = lib.itemsOf(List(10) { lib.show(eps = 3, genres = listOf("Comedy")) })
        val narrowShows = List(6) { lib.show(eps = 3, genres = listOf("Drama")) }
        val broad = channel("broad", 100, listOf("Comedy"), broadPool)
        val narrow =
            channel(
                "narrow",
                200,
                listOf("Drama"),
                lib.itemsOf(narrowShows),
                anchors = narrowShows.take(4).map { Source.Series(it) },
            )
        val picked = Selection.select(lib.snapshot(), listOf(broad, narrow), emptySet(), emptySet(), room = 1)
        assertEquals(setOf(narrow.id), picked.on)
        assertEquals(mapOf(broad.id to ShelfReason.BelowCap), picked.shelf)
    }

    @Test
    fun theTopFortyEightAreChosenTiesByNumber() {
        val lib = Library()
        val channels = List(50) { i -> channel("ch-$i", 100 + i, listOf("G$i"), qualifying(lib, "G$i")) }
        val picked = Selection.select(lib.snapshot(), channels.reversed(), emptySet(), emptySet(), room = 48)
        assertEquals(channels.take(48).map { it.id }.toSet(), picked.on)
        assertEquals(setOf(channels[48].id, channels[49].id), picked.shelf.keys)
        assertTrue(picked.shelf.values.all { it == ShelfReason.BelowCap })
    }

    // A real library (not a script): a niche lane of four 2-hour films is chosen; a standard one of the same shelf is
    // shelved too thin. (Dropping a channel only adds homes to the rest, so a niche lane that qualifies in round one
    // cannot fail in a later one; the property test below covers that for every lane.)
    @Test
    fun aNicheLaneOfFourFilmsIsChosenAndAStandardOneIsShelved() {
        val lib = Library()
        val films = List(4) { lib.film(genres = listOf("Western")) }
        val items = lib.itemsOf(films)
        val niche = channel("niche", 200, listOf("Western"), items, bar = QualifyingBar.Niche)
        val plain = channel("plain", 201, listOf("Western"), items)
        val onlyNiche = Selection.select(lib.snapshot(), listOf(niche), emptySet(), emptySet(), 5)
        assertEquals(setOf(niche.id), onlyNiche.on)
        val onlyPlain = Selection.select(lib.snapshot(), listOf(plain), emptySet(), emptySet(), 5)
        assertEquals(emptySet(), onlyPlain.on)
        assertEquals(ShelfReason.TooThin, onlyPlain.shelf.getValue(plain.id))
    }

    @Test
    fun aChannelThatLosesItsTitlesAfterRehomingIsDropped() {
        val a = bareChannel("a", 100)
        val b = bareChannel("b", 101)
        val c = bareChannel("c", 102)
        // A scripted case: a real library cannot produce a drop (see the property test below). Over everything all
        // three qualify. Re-homed over the chosen two, b no longer does; it is dropped and the
        // next round re-homes over a and c, which take the room.
        val script =
            Script { call, channels ->
                channels.associate { ch ->
                    ch.id to stats(6, 200L - ch.number, qualifies = !(call == 1 && ch.id == b.id))
                }
            }
        val picked = Selection.select(emptySnapshot, listOf(a, b, c), emptySet(), emptySet(), 2, script::factory)
        assertEquals(setOf(a.id, c.id), picked.on)
        assertEquals(ShelfReason.TooThin, picked.shelf.getValue(b.id))
    }

    @Test
    fun eachRoundRehomesOverAllButTheDropped() {
        val a = bareChannel("a", 100)
        val b = bareChannel("b", 101)
        val c = bareChannel("c", 102)
        val script =
            Script { call, channels ->
                channels.associate { ch -> ch.id to stats(6, 20, qualifies = !(call == 1 && ch.id == b.id)) }
            }
        Selection.select(emptySnapshot, listOf(c, b, a), emptySet(), emptySet(), 2, script::factory)
        // Round one: all three, then the chosen a and b. Round two: all but b, and the chosen a and c are everything
        // left, so the same homes serve (no second call).
        val want = listOf(setOf(a.id, b.id, c.id), setOf(a.id, b.id), setOf(a.id, c.id))
        assertEquals(want, script.calls.map { it.toSet() })
    }

    @Test
    fun aPinnedChannelIsNeverDropped() {
        val a = bareChannel("a", 100)
        val b = bareChannel("b", 101)
        val c = bareChannel("c", 102)
        // The pinned b qualifies in the first homing and fails in the second, beside the chosen a and c (scripted).
        val script =
            Script { call, channels ->
                channels.associate { ch -> ch.id to stats(6, 20, qualifies = !(call == 1 && ch.id == b.id)) }
            }
        val picked = Selection.select(emptySnapshot, listOf(a, b, c), setOf(b.id), emptySet(), 1, script::factory)
        assertEquals(setOf(a.id, b.id), picked.on)
        assertEquals(2, script.calls.size)
        assertEquals(ShelfReason.BelowCap, picked.shelf.getValue(c.id))
    }

    // The pin moves between rounds (scripted, as a real library cannot drop a channel): round one, the pin p sits
    // outside the top two and costs no place; the best free channel a then fails the re-homing and is dropped; round
    // two re-homes without a, p now scores into the top, takes a place, and the next free channel c is not picked.
    @Test
    fun aPinnedChannelLiftedIntoTheTopBetweenRoundsTakesThePlaceOfTheNextFreeChannel() {
        val a = bareChannel("a", 100)
        val b = bareChannel("b", 101)
        val c = bareChannel("c", 102)
        val p = bareChannel("p", 103)
        val script =
            Script { call, channels ->
                channels.associate { ch ->
                    val hours =
                        when (ch.id) {
                            a.id -> 70L
                            b.id -> 60L
                            c.id -> 50L
                            else -> if (call < 2) 30L else 65L
                        }
                    ch.id to stats(6, hours, qualifies = !(call == 1 && ch.id == a.id))
                }
            }
        val picked = Selection.select(emptySnapshot, listOf(a, b, c, p), setOf(p.id), emptySet(), 2, script::factory)
        assertEquals(setOf(b.id, p.id), picked.on)
        assertEquals(ShelfReason.TooThin, picked.shelf.getValue(a.id))
        assertEquals(ShelfReason.BelowCap, picked.shelf.getValue(c.id))
        assertEquals(4, script.calls.size, "two rounds: all and chosen, then all but a and chosen")
    }

    @Test
    fun itStopsAfterSixRounds() {
        val channels = List(8) { bareChannel("ch-$it", 100 + it) }
        // The best channel always fails the re-homing over the chosen set, so one is dropped every round, for ever.
        val script =
            Script { call, chans ->
                chans.associate { ch -> ch.id to stats(6, 50L - (ch.number - 100), qualifies = call % 2 == 0) }
            }
        val picked = Selection.select(emptySnapshot, channels, emptySet(), emptySet(), 1, script::factory)
        // Two homings a round, six rounds; the set of the last round stands.
        assertEquals(2 * CurationDefaults.ROUNDS, script.calls.size)
        assertEquals(setOf(channels[CurationDefaults.ROUNDS - 1].id), picked.on)
    }

    @Test
    fun shelfReasonsAreEmptyThinBelowCapAndOptIn() {
        val lib = Library()
        val good = channel("good", 100, listOf("Drama"), qualifying(lib, "Drama"))
        val over = channel("over", 101, listOf("Comedy"), qualifying(lib, "Comedy"))
        val thin = channel("thin", 102, listOf("Mystery"), qualifying(lib, "Mystery", titles = 2))
        val empty = channel("empty", 103, listOf("Western"), emptyList())
        val opt = channel("opt", 104, listOf("Horror"), qualifying(lib, "Horror"))
        val picked =
            Selection.select(lib.snapshot(), listOf(opt, empty, thin, over, good), emptySet(), setOf(opt.id), room = 1)
        assertEquals(setOf(good.id), picked.on)
        assertEquals(
            mapOf(
                over.id to ShelfReason.BelowCap,
                thin.id to ShelfReason.TooThin,
                empty.id to ShelfReason.Empty,
                opt.id to ShelfReason.OptIn,
            ),
            picked.shelf,
        )
    }

    @Test
    fun anOptInChannelIsOnOnlyWhenPinnedAndTakesNoTitlesOtherwise() {
        val lib = Library()
        val shows = List(6) { lib.show(eps = 3, genres = listOf("Drama")) }
        val pool = lib.itemsOf(shows)
        val plain = channel("plain", 100, listOf("Drama"), pool)
        val opt = channel("opt", 101, listOf("Drama"), pool, anchors = shows.map { Source.Series(it) })
        val snap = lib.snapshot()
        val unpinned = Selection.select(snap, listOf(plain, opt), emptySet(), setOf(opt.id), room = 5)
        assertEquals(setOf(plain.id), unpinned.on)
        assertEquals(ShelfReason.OptIn, unpinned.shelf.getValue(opt.id))
        val pinned = Selection.select(snap, listOf(plain, opt), setOf(opt.id), setOf(opt.id), room = 5)
        assertTrue(opt.id in pinned.on)
        assertTrue(opt.id !in pinned.shelf)
    }

    @Test
    fun aLibraryWithOnlyGenresStillFillsTheLineup() {
        val lib = Library()
        val genres = listOf("Drama", "Comedy", "Mystery", "Western", "Horror", "Documentary", "Music", "Crime")
        val channels = genres.mapIndexed { i, g -> channel("g-$i", 100 + i, listOf(g), qualifying(lib, g, titles = 7)) }
        val picked = Selection.select(lib.snapshot(), channels, emptySet(), emptySet(), room = 48)
        assertEquals(channels.map { it.id }.toSet(), picked.on)
        assertTrue(picked.shelf.isEmpty())
    }

    // Five channels in genres of their own that fill, the earlier the stronger: 10, 9, 8, 7 and 6 titles.
    private fun ranked(lib: Library): List<CurationChannel> =
        List(5) { i -> channel("c$i", 100 + i, listOf("G$i"), qualifying(lib, "G$i", titles = 10 - i)) }

    // The seat rule (#785): pinned channels are ranked with the free ones. One in the top room uses a place; one
    // outside it, or one that does not qualify, is on and costs none.
    @Test
    fun aPinnedChannelInsideTheTopUsesAPlace() {
        val lib = Library()
        val c = ranked(lib)
        val picked = Selection.select(lib.snapshot(), c, setOf(c[1].id), emptySet(), room = 3)
        assertEquals(setOf(c[0].id, c[1].id, c[2].id), picked.on)
        assertEquals(setOf(c[3].id, c[4].id), picked.shelf.keys)
    }

    @Test
    fun aPinnedChannelOutsideTheTopCostsNoPlace() {
        val lib = Library()
        val c = ranked(lib)
        val picked = Selection.select(lib.snapshot(), c, setOf(c[4].id), emptySet(), room = 3)
        assertEquals(setOf(c[0].id, c[1].id, c[2].id, c[4].id), picked.on, "the three best free ones and the pin")
        assertEquals(setOf(c[3].id), picked.shelf.keys)
    }

    @Test
    fun aPinnedChannelThatDoesNotQualifyCostsNoPlace() {
        val lib = Library()
        val c = ranked(lib)
        val thin = channel("thin", 110, listOf("Thin"), qualifying(lib, "Thin", titles = 2))
        val picked = Selection.select(lib.snapshot(), c + thin, setOf(thin.id), emptySet(), room = 3)
        assertEquals(setOf(c[0].id, c[1].id, c[2].id, thin.id), picked.on)
    }

    @Test
    fun aPinnedOptInChannelTakesPartInTheHomingButNeverInTheRanking() {
        val lib = Library()
        val c = ranked(lib)
        // It heads every order (20 titles), so were it ranked it would take one of the three places.
        val opt = channel("opt", 110, listOf("Opt"), qualifying(lib, "Opt", titles = 20))
        val picked = Selection.select(lib.snapshot(), c + opt, setOf(opt.id), setOf(opt.id), room = 3)
        assertEquals(setOf(c[0].id, c[1].id, c[2].id, opt.id), picked.on, "the pin sits on top, the places stay free")

        // It takes part in the homing: it holds every title of a free channel's genre and leaves it too thin.
        val shows = List(6) { lib.show(eps = 3, genres = listOf("Drama")) }
        val plain = channel("plain", 120, listOf("Drama"), lib.itemsOf(shows))
        val anchors = shows.map { Source.Series(it) }
        val holder = channel("holder", 115, listOf("Drama"), lib.itemsOf(shows), anchors = anchors)
        val both = listOf(plain, holder)
        val thinned = Selection.select(lib.snapshot(), both, setOf(holder.id), setOf(holder.id), room = 3)
        assertEquals(setOf(holder.id), thinned.on)
        assertEquals(ShelfReason.TooThin, thinned.shelf.getValue(plain.id))
    }

    // A homing that counts how often it is asked, to see how many rounds a selection took.
    private class CountingHoming(
        private val inner: Homing,
    ) : Homing {
        var calls = 0

        override fun homes(channels: Set<ChannelId>?): Homes {
            calls++
            return inner.homes(channels)
        }
    }

    // The ranking oracle (a one-round check: it reuses channelScore and CHANNEL_ORDER, so it pins the ranking rule,
    // not the settling of the rounds, which the scripted test above covers): with the homing never dropping a channel
    // (the property above), the result is the pinned
    // channels plus the free ones in the top room of one order of every qualifying channel, pinned and free alike,
    // the pinned opt-in ones left out of it. Seeded, over generated libraries and pin sets.
    @Test
    fun theResultMatchesOneOrderOfPinnedAndFreeChannelsAndIsDeterministic() {
        var inTopCases = 0
        var outsideCases = 0
        for (seed in 1..120) {
            val random = Random(seed)
            val lib = Library()
            val genres = listOf("Drama", "Comedy", "Mystery", "Crime")
            val shows = List(40) { lib.show(eps = 1 + random.nextInt(3), genres = listOf(genres[random.nextInt(4)])) }
            val channels =
                List(9) { i ->
                    val picked = shows.filter { random.nextInt(3) != 0 }.take(2 + random.nextInt(30))
                    channel("ch-$i", 100 + i, listOf(genres[i % genres.size]), lib.itemsOf(picked.distinct()))
                }
            val snap = lib.snapshot()
            val pinned = channels.filter { random.nextInt(3) == 0 }.mapTo(HashSet()) { it.id }
            val optIn = channels.filter { it.id in pinned && random.nextBoolean() }.mapTo(HashSet()) { it.id }
            val room = random.nextInt(1, 4)

            var counting: CountingHoming? = null
            val got =
                Selection.select(snap, channels, pinned, optIn, room) { s, c ->
                    CountingHoming(Curation.homing(s, c)).also { counting = it }
                }
            // One round: the homing over everything and, unless everything was chosen, over the chosen set.
            assertTrue(checkNotNull(counting).calls <= 2, "seed $seed: more than one round")

            val homes = Curation.homes(snap, channels)
            val ranked =
                channels
                    .filter { it.id !in optIn && homes.stats(it.id).qualifies }
                    .sortedWith { a, b ->
                        val scoreA = Selection.channelScore(homes.stats(a.id))
                        val byScore = Selection.channelScore(homes.stats(b.id)).compareTo(scoreA)
                        if (byScore != 0) byScore else CHANNEL_ORDER.compare(a, b)
                    }
            val top = ranked.take(room).map { it.id }.toSet()
            assertEquals(pinned + (top - pinned), got.on, "seed $seed")
            val inTopPins = top.count { it in pinned }
            if (inTopPins > 0) inTopCases++
            if (ranked.drop(room).any { it.id in pinned }) outsideCases++
            assertTrue((got.on - pinned).size + inTopPins <= room, "seed $seed: free picks and in-top pins in the room")
            for (shuffle in 1..3) {
                val again = Selection.select(snap, channels.shuffled(Random(seed * 31 + shuffle)), pinned, optIn, room)
                assertEquals(got.on, again.on, "seed $seed deterministic")
                assertEquals(got.shelf, again.shelf, "seed $seed deterministic")
            }
        }
        // The generator must reach both sides of the top, or the oracle proves little.
        assertTrue(inTopCases > 10, "seeds with a pinned channel in the top: $inTopCases")
        assertTrue(outsideCases > 10, "seeds with a pinned channel outside the top: $outsideCases")
    }

    @Test
    fun aGuestOnlyChannelCanBeSelected() {
        val lib = Library()
        val shows = List(6) { lib.show(eps = 3, genres = listOf("Drama")) }
        val pool = lib.itemsOf(shows)
        val decade = channel("decade", 300, emptyList(), pool, extraSources = listOf(Source.YearRange(1980, 1989)))
        val picked = Selection.select(lib.snapshot(), listOf(decade), emptySet(), emptySet(), room = 5)
        assertEquals(setOf(decade.id), picked.on)
    }

    @Test
    fun selectionIsDeterministic() {
        val lib = Library()
        val channels =
            List(12) { i ->
                // Shared shows make homing matter; equal sizes make ties.
                channel("ch-$i", 100 + i, listOf("G${i % 3}"), qualifying(lib, "G${i % 3}", titles = 6 + i % 2))
            }
        val snap = lib.snapshot()
        val pinned = setOf(channels[5].id)
        val optIn = setOf(channels[2].id)
        val want = Selection.select(snap, channels, pinned, optIn, room = 4)
        for (seed in 1..5) {
            val got = Selection.select(snap, channels.shuffled(Random(seed)), pinned, optIn, room = 4)
            assertEquals(want.on, got.on)
            assertEquals(want.shelf, got.shelf)
        }
    }

    @Test
    fun aChannelWithNothingAirableShelvesAsEmpty() {
        val lib = Library()
        val tiny =
            channel(
                "tiny",
                100,
                listOf("Drama"),
                lib.itemsOf(List(8) { lib.show(eps = 2, epMs = 30_000L, genres = listOf("Drama")) }),
            )
        val picked = Selection.select(lib.snapshot(), listOf(tiny), emptySet(), emptySet(), room = 5)
        assertEquals(mapOf(tiny.id to ShelfReason.Empty), picked.shelf)
    }

    @Test
    fun aChannelWhoseTitlesAreAllAnimeTurnedAwayShelvesAsEmpty() {
        val lib = Library()
        val pool = lib.itemsOf(List(8) { lib.show(eps = 3, genres = listOf("Anime")) })
        val anime = channel("plain", 100, listOf("Anime"), pool)
        val picked = Selection.select(lib.snapshot(), listOf(anime), emptySet(), emptySet(), room = 5)
        assertEquals(mapOf(anime.id to ShelfReason.Empty), picked.shelf)
    }

    @Test
    fun aDecadeChannelStillQualifiesWhenARegularChannelTakesEveryHome() {
        val lib = Library()
        val shows = List(7) { lib.show(eps = 3, genres = listOf("Drama")) }
        val pool = lib.itemsOf(shows)
        val regular = channel("regular", 100, listOf("Drama"), pool)
        val decade = channel("decade", 300, emptyList(), pool, extraSources = listOf(Source.YearRange(1980, 1989)))
        val snap = lib.snapshot()
        val homes = Curation.homes(snap, listOf(regular, decade))
        assertTrue(shows.all { homes.homeOf(it) == regular.id })
        assertTrue(homes.stats(decade.id).qualifies)
        val picked = Selection.select(snap, listOf(regular, decade), emptySet(), emptySet(), room = 5)
        assertEquals(setOf(regular.id, decade.id), picked.on)
    }

    @Test
    fun severalKidChannelsShareTheKidPoolAndEachQualifies() {
        val lib = Library()
        val pool = lib.itemsOf(List(5) { lib.show(eps = 3, genres = listOf("Family"), rating = "G") })
        val kids = List(3) { channel("kid-$it", 10 + it, listOf("Family"), pool, kid = true) }
        val snap = lib.snapshot()
        val homes = Curation.homes(snap, kids)
        assertTrue(kids.all { homes.stats(it.id).qualifies })
        assertEquals(kids.map { it.id }.toSet(), Selection.select(snap, kids, emptySet(), emptySet(), 5).on)
    }

    @Test
    fun theResultsAreInChannelNumberOrder() {
        val lib = Library()
        val channels =
            List(5) { i ->
                channel("ch-$i", 100 + i, listOf("G$i"), qualifying(lib, "G$i", titles = if (i == 4) 2 else 6))
            }
        val picked = Selection.select(lib.snapshot(), channels.reversed(), emptySet(), setOf(channels[0].id), room = 2)
        assertEquals(listOf(channels[1].id, channels[2].id), picked.on.toList())
        assertEquals(listOf(channels[0].id, channels[3].id, channels[4].id), picked.shelf.keys.toList())
    }

    // The homing property (see Selection): over any subset holding a channel, its titles and capped hours never fall,
    // and everything select chooses still qualifies over the chosen set. Seeded, over generated libraries.
    @Test
    fun removingChannelsNeverShrinksAChannelAndSelectionNeverDrops() {
        for (seed in 1..40) {
            val random = Random(seed)
            val lib = Library()
            val genres = listOf("Drama", "Comedy", "Mystery", "Crime")
            val showGenres = HashMap<CanonicalId, List<String>>()
            val shows =
                List(40) { _ ->
                    val own = genres.shuffled(random).take(1 + random.nextInt(2))
                    val eps = 1 + random.nextInt(4)
                    val epMs = HOUR / (1 + random.nextInt(2))
                    lib.show(eps = eps, epMs = epMs, genres = own).also { id -> showGenres[id] = own }
                }
            val kidShows = List(8) { lib.show(eps = 3, genres = listOf("Family"), rating = "G") }
            val byGenre = genres.associateWith { g -> shows.filter { g in showGenres.getValue(it) } }
            val channels = ArrayList<CurationChannel>()
            for (i in 0 until 7) {
                val g = genres[i % genres.size]
                val picked = byGenre.getValue(g) + shows.filter { random.nextInt(3) == 0 }
                val anchored = random.nextBoolean() && picked.isNotEmpty()
                val anchors = if (anchored) listOf(Source.Series(picked.first())) else emptyList()
                val decade = i == 6
                channels +=
                    channel(
                        "ch-$i",
                        100 + i,
                        if (decade) emptyList() else listOf(g),
                        lib.itemsOf(picked.distinct()),
                        anchors = anchors,
                        extraSources = if (decade) listOf(Source.YearRange(1980, 1989)) else emptyList(),
                    )
            }
            channels += channel("kid-a", 10, listOf("Family"), lib.itemsOf(kidShows), kid = true)
            channels += channel("kid-b", 11, listOf("Family"), lib.itemsOf(kidShows.take(6)), kid = true)
            val snap = lib.snapshot()
            val whole = Curation.homes(snap, channels)
            val homing = Curation.homing(snap, channels)
            repeat(6) { _ ->
                val subset = channels.filter { random.nextBoolean() }
                val ids = subset.mapTo(HashSet()) { it.id }
                val over = homing.homes(ids)
                val fresh = Curation.homes(snap, subset)
                for (c in subset) {
                    assertEquals(fresh.stats(c.id), over.stats(c.id), "seed $seed: reused lanes equal a fresh run")
                    assertTrue(over.stats(c.id).titles >= whole.stats(c.id).titles, "seed $seed titles")
                    assertTrue(over.stats(c.id).cappedMs >= whole.stats(c.id).cappedMs, "seed $seed hours")
                }
            }
            val room = random.nextInt(1, 6)
            val chosen = Selection.select(snap, channels, emptySet(), emptySet(), room).on
            val over = Curation.homes(snap, channels.filter { it.id in chosen })
            assertTrue(chosen.all { over.stats(it).qualifies }, "seed $seed: a chosen channel still qualifies")
        }
    }
}
