// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

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
