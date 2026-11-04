// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.CurationTables
import tv.stationbreak.core.engine.lineup.FormatPromise
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EIGHTIES = Source.YearRange(1980, 1989)

class CurationTest {
    private fun ids(items: List<ScheduleItem>): Set<CanonicalId> = items.mapTo(HashSet()) { it.id }

    private fun CuratedPool.titles(): Set<CanonicalId> = items.mapTo(HashSet()) { it.groupId }

    @Test
    fun versionIsTheTablesVersion() {
        assertEquals("curation-1", Curation.VERSION_NAME)
        assertEquals(CurationTables.VERSION_NAME, Curation.VERSION_NAME)
    }

    @Test
    fun aTitleHasOneHome() {
        val lib = Library()
        val s = lib.show(eps = 4, genres = listOf("Drama", "Mystery"))
        val a = channel("ch-a", 100, listOf("Drama"), lib.itemsOf(listOf(s)))
        val b = channel("ch-b", 200, listOf("Drama", "Mystery"), lib.itemsOf(listOf(s)))
        val snap = lib.snapshot()
        assertEquals(b.id, Curation.homes(snap, listOf(a, b)).homeOf(s))
        val pools = Curation.pools(snap, listOf(a, b))
        assertEquals(setOf(s), pools.getValue(b.id).titles())
        assertEquals(2, pools.getValue(b.id).weights[s])
        // A has no homes, so a guest would be all of its airtime: none stays.
        assertTrue(pools.getValue(a.id).items.isEmpty())
    }

    @Test
    fun anAnchorWinsTheHome() {
        val lib = Library()
        val s = lib.show(eps = 4, genres = listOf("Drama", "Mystery"))
        val a = channel("ch-a", 100, listOf("Comedy"), lib.itemsOf(listOf(s)), anchors = listOf(Source.Series(s)))
        val b = channel("ch-b", 200, listOf("Drama", "Mystery"), lib.itemsOf(listOf(s)))
        val snap = lib.snapshot()
        assertEquals(a.id, Curation.homes(snap, listOf(a, b)).homeOf(s))
        assertEquals(3, Curation.pools(snap, listOf(a, b)).getValue(a.id).weights[s])
    }

    @Test
    fun aCollectionItemOrStudioAnchorWinsTheHome() {
        val lib = Library()
        val inCollection = lib.film(genres = listOf("Drama"))
        val byItem = lib.film(genres = listOf("Drama"))
        val byStudio = lib.film(genres = listOf("Drama"), studios = listOf("Studio Q"))
        val box = idOf(0x0F00)
        lib.collections += collection(box, movies = setOf(inCollection))
        val all = lib.itemsOf(listOf(inCollection, byItem, byStudio))
        val theme = Theme(listOf(Anchor(AnchorKind.Studio, NameMatcher.Exact("Studio Q"))), emptyList(), null, false)
        val a =
            channel(
                "ch-a",
                300,
                listOf("Comedy"),
                all,
                anchors = listOf(Source.Collection(box), Source.Item(byItem)),
                theme = theme,
            )
        val b = channel("ch-b", 200, listOf("Drama"), all)
        val homes = Curation.homes(lib.snapshot(), listOf(a, b))
        assertEquals(a.id, homes.homeOf(inCollection))
        assertEquals(a.id, homes.homeOf(byItem))
        assertEquals(a.id, homes.homeOf(byStudio))
        assertEquals(3, homes.stats(a.id).anchors)
    }

    @Test
    fun aThemeStudioBeatsAGenreMatch() {
        val lib = Library()
        val f = lib.film(genres = listOf("Drama"), studios = listOf("Studio A"))
        val studioTheme = Theme(emptyList(), listOf(NameMatcher.Contains("Studio A")), null, false)
        val a = channel("ch-a", 300, listOf("Comedy"), lib.itemsOf(listOf(f)), theme = studioTheme)
        val b = channel("ch-b", 200, listOf("Drama"), lib.itemsOf(listOf(f)))
        assertEquals(a.id, Curation.homes(lib.snapshot(), listOf(a, b)).homeOf(f))
    }

    @Test
    fun aFormatPromiseBreaksATie() {
        val lib = Library()
        val f = lib.film(genres = listOf("Drama"))
        val s = lib.show(eps = 2, genres = listOf("Drama"))
        val both = lib.itemsOf(listOf(f, s))
        val films = channel("ch-f", 300, listOf("Drama"), both, theme = theme(FormatPromise.Films))
        val shows = channel("ch-s", 301, listOf("Drama"), both, theme = theme(FormatPromise.ShortSeries))
        val plain = channel("ch-p", 200, listOf("Drama"), both)
        val homes = Curation.homes(lib.snapshot(), listOf(films, shows, plain))
        assertEquals(films.id, homes.homeOf(f))
        assertEquals(shows.id, homes.homeOf(s))
    }

    @Test
    fun aGuestOnlyChannelHomesNothingWhenAnotherFits() {
        val lib = Library()
        val s = lib.show(eps = 4, genres = listOf("Drama"))
        // The decade channel even holds an anchor, and still takes no home while another channel fits.
        val decade =
            channel(
                "ch-d",
                100,
                listOf("Drama"),
                lib.itemsOf(listOf(s)),
                anchors = listOf(Source.Series(s)),
                extraSources = listOf(Source.YearRange(1980, 1989)),
            )
        val b = channel("ch-b", 200, listOf("Comedy"), lib.itemsOf(listOf(s)))
        assertEquals(b.id, Curation.homes(lib.snapshot(), listOf(decade, b)).homeOf(s))
    }

    @Test
    fun aGuestOnlyChannelHomesWhenItIsTheOnlyFit() {
        val lib = Library()
        val s = lib.show(eps = 4, genres = listOf("Drama"))
        val other = lib.film(genres = listOf("Comedy"))
        val decade =
            channel("ch-d", 100, listOf("Drama"), lib.itemsOf(listOf(s)), extraSources = listOf(EIGHTIES))
        val b = channel("ch-b", 200, listOf("Comedy"), lib.itemsOf(listOf(other)))
        assertEquals(decade.id, Curation.homes(lib.snapshot(), listOf(decade, b)).homeOf(s))
    }

    @Test
    fun tiesGoToTheLowerNumber() {
        val lib = Library()
        val s = lib.show(eps = 4, genres = listOf("Drama"))
        val high = channel("ch-a", 300, listOf("Drama"), lib.itemsOf(listOf(s)))
        val low = channel("ch-z", 200, listOf("Drama"), lib.itemsOf(listOf(s)))
        assertEquals(low.id, Curation.homes(lib.snapshot(), listOf(high, low)).homeOf(s))
    }

    @Test
    fun kidChannelsShareTheirPool() {
        val lib = Library()
        val shows = List(4) { lib.show(eps = 3, genres = listOf("Kids"), rating = "TV-Y") }
        val all = lib.itemsOf(shows)
        val k1 = channel("kid-a", 10, listOf("Kids"), all, kid = true)
        val k2 = channel("kid-b", 20, listOf("Kids"), all, kid = true)
        val snap = lib.snapshot()
        val pools = Curation.pools(snap, listOf(k1, k2))
        assertEquals(ids(all), ids(pools.getValue(k1.id).items))
        assertEquals(ids(all), ids(pools.getValue(k2.id).items))
        val homes = Curation.homes(snap, listOf(k1, k2))
        for (s in shows) assertEquals(k1.id, homes.homeOf(s))
        // k2 homes nothing, yet it counts the shared pool: 12 hours of four titles, under a 3-hour cap.
        assertEquals(ChannelStats(4, 12 * HOUR, 12 * HOUR, 0, true), homes.stats(k2.id))
        for (s in shows) assertEquals(1, pools.getValue(k2.id).weights[s])
    }

    @Test
    fun aKidTitleNeverGuestsOnAGrownUpChannel() {
        val lib = Library()
        val kidShow = lib.show(eps = 1, genres = listOf("Kids"), rating = "TV-Y")
        val control = lib.show(eps = 1, genres = listOf("Drama"))
        // The guest channel's own homes are all of the kid tone, so only the kid rule keeps the kid show off it.
        val kidHomes = List(3) { lib.show(eps = 4, genres = listOf("Kids"), rating = "TV-Y") }
        val adultHomes = List(3) { lib.show(eps = 4, genres = listOf("Drama")) }
        val home =
            channel(
                "ch-h",
                100,
                listOf("Drama"),
                lib.itemsOf(listOf(kidShow, control)),
                anchors = listOf(Source.Series(kidShow), Source.Series(control)),
            )
        val kidMix = channel("ch-k", 200, listOf("Kids"), lib.itemsOf(listOf(kidShow) + kidHomes))
        val adultMix = channel("ch-g", 201, listOf("Drama"), lib.itemsOf(listOf(control) + adultHomes))
        val pools = Curation.pools(lib.snapshot(), listOf(home, kidMix, adultMix))
        assertFalse(kidShow in pools.getValue(kidMix.id).titles())
        assertTrue(control in pools.getValue(adultMix.id).titles())

        // Homed on a kid channel, it guests nowhere either.
        val kidChannel = channel("kid-a", 10, listOf("Kids"), lib.itemsOf(listOf(kidShow)), kid = true)
        val pools2 = Curation.pools(lib.snapshot(), listOf(kidChannel, kidMix))
        assertFalse(kidShow in pools2.getValue(kidMix.id).titles())
    }

    @Test
    fun animeLeavesNonAnimeChannelsAndOnlyAnimeStaysOnAnimeChannels() {
        val lib = Library()
        val anime = lib.show(eps = 3, genres = listOf("Anime", "Drama"))
        val live = lib.show(eps = 3, genres = listOf("Drama"))
        val both = lib.itemsOf(listOf(anime, live))
        val grownUp = channel("ch-g", 200, listOf("Drama"), both)
        val animeOnly =
            channel("ch-x", 300, listOf("Anime"), both, theme = Theme(emptyList(), emptyList(), null, animeOnly = true))
        val snap = lib.snapshot()
        val homes = Curation.homes(snap, listOf(grownUp, animeOnly))
        assertEquals(animeOnly.id, homes.homeOf(anime))
        assertEquals(grownUp.id, homes.homeOf(live))
        val pools = Curation.pools(snap, listOf(grownUp, animeOnly))
        assertEquals(setOf(live), pools.getValue(grownUp.id).titles())
        assertEquals(setOf(anime), pools.getValue(animeOnly.id).titles())
        // Alone, the grown-up channel still drops anime.
        assertNull(Curation.homes(snap, listOf(grownUp)).homeOf(anime))
    }

    @Test
    fun anAnimeTitleStaysOnAKidChannel() {
        val lib = Library()
        val anime = lib.show(eps = 3, genres = listOf("Anime", "Kids"), rating = "TV-Y")
        val kid = channel("kid-a", 10, listOf("Kids"), lib.itemsOf(listOf(anime)), kid = true)
        val pools = Curation.pools(lib.snapshot(), listOf(kid))
        assertEquals(setOf(anime), pools.getValue(kid.id).titles())
    }

    @Test
    fun atMostTwoGuestSpots() {
        val lib = Library()
        val t = lib.show(eps = 1, genres = listOf("Drama"))
        val home = channel("ch-h", 100, listOf("Drama"), lib.itemsOf(listOf(t)), anchors = listOf(Source.Series(t)))
        val guests =
            List(3) { i ->
                val own = lib.show(eps = 10, genres = listOf("Drama"))
                channel("ch-g$i", 203 - i, listOf("Drama"), lib.itemsOf(listOf(t, own)))
            }
        val pools = Curation.pools(lib.snapshot(), listOf(home) + guests)
        // Equal fits: the two lowest numbers (202 and 201) take the guest spots.
        assertFalse(t in pools.getValue(guests[0].id).titles())
        assertTrue(t in pools.getValue(guests[1].id).titles())
        assertTrue(t in pools.getValue(guests[2].id).titles())
        assertEquals(1, pools.getValue(guests[2].id).weights[t])
    }

    @Test
    fun guestsNeedTheirToneInTheChannelsMix() {
        val lib = Library()
        val t = lib.show(eps = 1, genres = listOf("Animation"), rating = "TV-MA")
        val home = channel("ch-h", 100, listOf("Drama"), lib.itemsOf(listOf(t)), anchors = listOf(Source.Series(t)))
        val liveOnly = lib.show(eps = 10, genres = listOf("Drama"))
        val g1 = channel("ch-a", 200, listOf("Drama"), lib.itemsOf(listOf(t, liveOnly)))
        val live = lib.show(eps = 3, genres = listOf("Drama"))
        val toon = lib.show(eps = 1, genres = listOf("Animation"), rating = "TV-MA")
        // Exactly a quarter of g2's home hours share the guest's tone.
        val g2 = channel("ch-b", 201, listOf("Drama"), lib.itemsOf(listOf(t, live, toon)))
        val pools = Curation.pools(lib.snapshot(), listOf(home, g1, g2))
        assertFalse(t in pools.getValue(g1.id).titles())
        assertTrue(t in pools.getValue(g2.id).titles())
    }

    @Test
    fun guestsStayUnderThirtyPercent() {
        val lib = Library()
        val own = lib.show(eps = 7, genres = listOf("Drama", "Mystery"))
        val strong1 = lib.show(eps = 3, epMs = HALF_HOUR, genres = listOf("Drama", "Mystery"))
        val strong2 = lib.show(eps = 3, epMs = HALF_HOUR, genres = listOf("Drama", "Mystery"))
        val weak = lib.show(eps = 3, epMs = HALF_HOUR, genres = listOf("Drama"))
        val homesOf =
            listOf(strong1, strong2, weak).mapIndexed { i, s ->
                channel("ch-h$i", 100 + i, listOf("Comedy"), lib.itemsOf(listOf(s)), anchors = listOf(Source.Series(s)))
            }
        val g = channel("ch-g", 200, listOf("Drama", "Mystery"), lib.itemsOf(listOf(own, strong1, strong2, weak)))
        val pool = Curation.pools(lib.snapshot(), homesOf + g).getValue(g.id)
        assertEquals(setOf(own, strong1, strong2), pool.titles())
        val guestMs = pool.items.filter { it.groupId != own }.sumOf { it.runtimeMs }
        assertTrue(guestMs * 7 <= 7 * HOUR * 3)
    }

    @Test
    fun keepsHoldTheShareCapForEveryOverCapSeries() {
        val lib = Library()
        val s1 = lib.show(eps = 12, genres = listOf("Drama"))
        val s2 = lib.show(eps = 12, genres = listOf("Drama"))
        val s3 = lib.show(eps = 2, genres = listOf("Drama"))
        val film = lib.film(ms = 10 * HOUR, genres = listOf("Drama"))
        val c = channel("ch-a", 200, listOf("Drama"), lib.itemsOf(listOf(s1, s2, s3, film)))
        val pool = Curation.pools(lib.snapshot(), listOf(c)).getValue(c.id)
        val cap = 9 * HOUR // A quarter of 36 hours.
        assertEquals(listOf(s1 to cap, s2 to cap), pool.keeps.entries)
        assertNull(pool.keeps[film])
    }

    @Test
    fun statsAndKeepsUseTheSameShareCap() {
        val lib = Library()
        val big = lib.show(eps = 12, genres = listOf("Drama"))
        val small = List(5) { lib.show(eps = 2, genres = listOf("Drama")) }
        val c = channel("ch-a", 200, listOf("Drama"), lib.itemsOf(listOf(big) + small))
        val snap = lib.snapshot()
        val stats = Curation.homes(snap, listOf(c)).stats(c.id)
        val pool = Curation.pools(snap, listOf(c)).getValue(c.id)
        assertEquals(22 * HOUR, stats.totalMs)
        val cap = CurationDefaults.shareCapMs(stats.totalMs)
        assertEquals(cap, pool.keeps[big])
        assertEquals(stats.cappedMs, cap + 10 * HOUR)

        // With a guest, keeps take the cap of every member's hours, not of the homes alone.
        val lib3 = Library()
        val big3 = lib3.show(eps = 12, genres = listOf("Drama"))
        val small3 = List(5) { lib3.show(eps = 2, genres = listOf("Drama")) }
        val guest = lib3.show(eps = 6, genres = listOf("Drama"))
        val anchored = listOf<Source>(Source.Series(guest))
        val away = channel("ch-e", 100, listOf("Comedy"), lib3.itemsOf(listOf(guest)), anchors = anchored)
        val c3 = channel("ch-c", 202, listOf("Drama"), lib3.itemsOf(listOf(big3, guest) + small3))
        val pool3 = Curation.pools(lib3.snapshot(), listOf(away, c3)).getValue(c3.id)
        assertTrue(guest in pool3.titles())
        assertEquals(CurationDefaults.shareCapMs(28 * HOUR), pool3.keeps[big3])

        // The one-hour floor applies to both.
        val lib2 = Library()
        val short = lib2.show(eps = 3, epMs = HALF_HOUR, genres = listOf("Drama"))
        val tiny = lib2.film(ms = HALF_HOUR, genres = listOf("Drama"))
        val c2 = channel("ch-b", 201, listOf("Drama"), lib2.itemsOf(listOf(short, tiny)))
        val snap2 = lib2.snapshot()
        assertEquals(HOUR, Curation.pools(snap2, listOf(c2)).getValue(c2.id).keeps[short])
        assertEquals(HOUR + HALF_HOUR, Curation.homes(snap2, listOf(c2)).stats(c2.id).cappedMs)
    }

    @Test
    fun unairableItemsAreNeitherCuratedNorClaimed() {
        val lib = Library()
        val s = lib.show(eps = 3, genres = listOf("Drama"))
        val tooShort = lib.film(ms = 30_000L, genres = listOf("Drama"))
        val tooLong = lib.film(ms = 25 * HOUR, genres = listOf("Drama"))
        val c = channel("ch-a", 200, listOf("Drama"), lib.itemsOf(listOf(s, tooShort, tooLong)))
        val snap = lib.snapshot()
        val pool = Curation.pools(snap, listOf(c)).getValue(c.id)
        assertEquals(setOf(s), pool.titles())
        assertEquals(ids(pool.items), ids(pool.claimed))
        val homes = Curation.homes(snap, listOf(c))
        assertNull(homes.homeOf(tooShort))
        assertNull(homes.homeOf(tooLong))
        assertEquals(1, homes.stats(c.id).titles)
    }

    @Test
    fun trimmedItemsStayClaimed() {
        val lib = Library()
        val big = lib.show(eps = 12, genres = listOf("Drama"))
        val small = lib.show(eps = 2, genres = listOf("Drama"))
        val c = channel("ch-a", 200, listOf("Drama"), lib.itemsOf(listOf(big, small)))
        val pool = Curation.pools(lib.snapshot(), listOf(c)).getValue(c.id)
        assertEquals(CurationDefaults.shareCapMs(14 * HOUR), pool.keeps[big])
        assertEquals(ids(lib.itemsOf(listOf(big, small))), ids(pool.claimed))
        assertEquals(pool.items, pool.claimed)
    }

    @Test
    fun weightsAreThreeTwoOne() {
        val lib = Library()
        val anchor = lib.show(eps = 4, genres = listOf("Drama"))
        val home = lib.show(eps = 4, genres = listOf("Drama"))
        val guest = lib.show(eps = 1, genres = listOf("Drama"))
        val elsewhere =
            channel("ch-e", 100, listOf("Comedy"), lib.itemsOf(listOf(guest)), anchors = listOf(Source.Series(guest)))
        val c =
            channel(
                "ch-a",
                200,
                listOf("Drama"),
                lib.itemsOf(listOf(anchor, home, guest)),
                anchors = listOf(Source.Series(anchor)),
            )
        val pool = Curation.pools(lib.snapshot(), listOf(elsewhere, c)).getValue(c.id)
        assertEquals(setOf(anchor, home, guest), pool.titles())
        assertEquals(3, pool.weights[anchor])
        assertEquals(2, pool.weights[home])
        assertEquals(1, pool.weights[guest])
    }

    @Test
    fun qualifyNeedsTwelveUsableHoursAndSixTitles() {
        assertTrue(statsFor(List(6) { 2 * HOUR }).qualifies)
        assertFalse(statsFor(List(5) { 3 * HOUR }).qualifies)
        // 25 hours, but the share cap leaves 6.25 + 5 usable hours.
        val capped = statsFor(listOf(20 * HOUR) + List(5) { HOUR })
        assertEquals(CurationDefaults.shareCapMs(25 * HOUR) + 5 * HOUR, capped.cappedMs)
        assertFalse(capped.qualifies)
        assertTrue(statsFor(List(4) { 3 * HOUR }, kid = true).qualifies)
        assertFalse(statsFor(List(3) { 4 * HOUR }, kid = true).qualifies)
        assertFalse(statsFor(List(4) { 2 * HOUR }, kid = true).qualifies)
    }

    private fun statsFor(
        hours: List<Long>,
        kid: Boolean = false,
    ): ChannelStats {
        val lib = Library()
        val titles = hours.map { lib.show(eps = (it / HOUR).toInt(), genres = listOf("Kids"), rating = "TV-Y") }
        val all = lib.itemsOf(titles)
        val c = channel(if (kid) "kid-a" else "ch-a", if (kid) 10 else 200, listOf("Kids"), all, kid = kid)
        return Curation.homes(lib.snapshot(), listOf(c)).stats(c.id)
    }

    @Test
    fun curationNeverAddsAnItem() {
        repeat(200) { case ->
            val world = randomWorld(Random(case))
            val pools = Curation.pools(world.snapshot, world.channels)
            assertEquals(world.channels.map { it.id }.toSet(), pools.keys)
            for (c in world.channels) {
                val pool = pools.getValue(c.id)
                val airable = c.pool.filterTo(HashSet()) { c.settings.isAirable(it.runtimeMs, it.kind) }
                assertTrue(airable.containsAll(pool.items), "case $case, ${c.id}")
                assertTrue(airable.containsAll(pool.claimed), "case $case, ${c.id}")
                assertEquals(pool.items.map { it.id.value }.sorted(), pool.items.map { it.id.value }, "case $case")
                val members = pool.titles()
                for ((title, _) in pool.keeps.entries) assertTrue(title in members, "case $case")
                for ((title, _) in pool.weights.entries) assertTrue(title in members, "case $case")
            }
        }
    }

    @Test
    fun curationIsIndependentOfInputOrder() {
        repeat(20) { case ->
            val world = randomWorld(Random(1000 + case))
            val shuffle = Random(case)
            val snap2 = world.snapshot.copy(items = world.snapshot.items.shuffled(shuffle))
            val channels2 = world.channels.map { it.withPool(it.pool.shuffled(shuffle)) }.shuffled(shuffle)
            val a = Curation.pools(world.snapshot, world.channels)
            val b = Curation.pools(snap2, channels2)
            assertEquals(a.toList(), b.toList(), "case $case")
            val h1 = Curation.homes(world.snapshot, world.channels)
            val h2 = Curation.homes(snap2, channels2)
            for (title in world.titles) assertEquals(h1.homeOf(title), h2.homeOf(title), "case $case")
            for (c in world.channels) assertEquals(h1.stats(c.id), h2.stats(c.id), "case $case")
        }
    }

    private class World(
        val snapshot: tv.stationbreak.core.model.RuleSnapshot,
        val channels: List<CurationChannel>,
        val titles: List<CanonicalId>,
    )

    private val genrePool = listOf("Drama", "Comedy", "Mystery", "Animation", "Anime", "Kids", "Family", "Crime")
    private val ratingPool = listOf(null, "TV-Y", "TV-PG", "TV-14", "TV-MA", "G", "R")

    // A seeded world: a few titles with random genres, ratings and runtimes (some unairable), and channels that each
    // hold a random subset of the items, some kid, some with anchors, studios or a decade.
    private fun randomWorld(random: Random): World {
        val lib = Library()
        val titles = ArrayList<CanonicalId>()
        repeat(random.nextInt(4, 30)) {
            val genres = genrePool.shuffled(random).take(random.nextInt(1, 3))
            val rating = ratingPool[random.nextInt(ratingPool.size)]
            val studios = if (random.nextInt(4) == 0) listOf("Studio A") else emptyList()
            val ms = if (random.nextInt(10) == 0) 30_000L else HALF_HOUR * random.nextInt(1, 8)
            titles +=
                if (random.nextBoolean()) {
                    lib.film(ms = ms, genres = genres, rating = rating, studios = studios)
                } else if (random.nextInt(3) == 0) {
                    // An unrated series whose episodes carry different ratings.
                    val eps = random.nextInt(2, 15)
                    val epRatings = List(3) { ratingPool[random.nextInt(ratingPool.size)] }
                    lib.show(eps = eps, epMs = ms, genres = genres, studios = studios, epRatings = epRatings)
                } else {
                    val eps = random.nextInt(1, 15)
                    lib.show(eps = eps, epMs = ms, genres = genres, rating = rating, studios = studios)
                }
        }
        val channels = ArrayList<CurationChannel>()
        repeat(random.nextInt(1, 7)) { i ->
            val kid = random.nextInt(4) == 0
            val picked = titles.filter { random.nextInt(3) != 0 }
            val anchors = picked.filter { random.nextInt(6) == 0 }.map { Source.Series(it) as Source }
            val theme =
                Theme(
                    emptyList(),
                    if (random.nextBoolean()) listOf(NameMatcher.Contains("Studio A")) else emptyList(),
                    listOf(null, FormatPromise.Films, FormatPromise.ShortSeries)[random.nextInt(3)],
                    animeOnly = random.nextInt(5) == 0,
                )
            val decade = if (!kid && random.nextInt(5) == 0) listOf(Source.YearRange(1980, 1989)) else emptyList()
            channels +=
                channel(
                    if (kid) "kid-$i" else "ch-$i",
                    if (kid) 10 + i else 200 + random.nextInt(3) * 10 + i,
                    genrePool.shuffled(random).take(random.nextInt(1, 3)),
                    lib.itemsOf(picked),
                    kid = kid,
                    anchors = anchors,
                    theme = theme,
                    extraSources = decade,
                )
        }
        return World(lib.snapshot(), channels, titles)
    }

    @Test
    fun aChannelWithNoHomesTakesNoGuestSpot() {
        val lib = Library()
        val t = lib.show(eps = 1, genres = listOf("Drama", "Mystery"))
        val home = channel("ch-h", 100, listOf("Comedy"), lib.itemsOf(listOf(t)), anchors = listOf(Source.Series(t)))
        // The best fit for t, but it homes nothing, so it must not use up one of t's two spots.
        val empty = channel("ch-e", 201, listOf("Drama", "Mystery"), lib.itemsOf(listOf(t)))
        val guests =
            List(2) { i ->
                val own = lib.show(eps = 10, genres = listOf("Drama"))
                channel("ch-g$i", 202 + i, listOf("Drama"), lib.itemsOf(listOf(t, own)))
            }
        val pools = Curation.pools(lib.snapshot(), listOf(home, empty) + guests)
        assertTrue(pools.getValue(empty.id).items.isEmpty())
        for (g in guests) assertTrue(t in pools.getValue(g.id).titles(), "${g.id}")
    }

    @Test
    fun aDecadeChannelAirsGuestsAndIsNotEmptiedByTheThirtyPercentCap() {
        val lib = Library()
        val shows = List(6) { lib.show(eps = 2, genres = listOf("Drama")) }
        // An adult-animation title: a twelfth of the decade's candidate hours, under a quarter, so it is no guest.
        val toon = lib.show(eps = 1, genres = listOf("Animation"), rating = "TV-MA")
        val all = shows + toon
        val home =
            channel("ch-h", 100, listOf("Comedy"), lib.itemsOf(all), anchors = all.map { Source.Series(it) as Source })
        val decade = channel("ch-d", 300, listOf("Drama"), lib.itemsOf(all), extraSources = listOf(EIGHTIES))
        val pool = Curation.pools(lib.snapshot(), listOf(home, decade)).getValue(decade.id)
        assertEquals(shows.toSet(), pool.titles())
        for (s in shows) assertEquals(1, pool.weights[s])
    }

    @Test
    fun decadeStatsQualifyFromItsCandidates() {
        val lib = Library()
        val shows = List(6) { lib.show(eps = 2, genres = listOf("Drama")) }
        val home =
            channel(
                "ch-h",
                100,
                listOf("Comedy"),
                lib.itemsOf(shows),
                anchors = shows.map { Source.Series(it) as Source },
            )
        val decade = channel("ch-d", 300, listOf("Drama"), lib.itemsOf(shows), extraSources = listOf(EIGHTIES))
        val stats = Curation.homes(lib.snapshot(), listOf(home, decade)).stats(decade.id)
        assertEquals(ChannelStats(6, 12 * HOUR, 12 * HOUR, 0, true), stats)
    }

    @Test
    fun aGrownUpTitleHomedOnAKidChannelStillGuests() {
        val lib = Library()
        val film = lib.film(ms = HOUR, genres = listOf("Drama"))
        val byItem = listOf<Source>(Source.Item(film))
        val kid =
            channel("kid-a", 10, listOf("Kids"), lib.itemsOf(listOf(film)), kid = true, anchors = byItem)
        val own = lib.show(eps = 10, genres = listOf("Drama"))
        val grownUp = channel("ch-g", 200, listOf("Drama"), lib.itemsOf(listOf(film, own)))
        val snap = lib.snapshot()
        assertEquals(kid.id, Curation.homes(snap, listOf(kid, grownUp)).homeOf(film))
        val pool = Curation.pools(snap, listOf(kid, grownUp)).getValue(grownUp.id)
        assertTrue(film in pool.titles())
        assertEquals(1, pool.weights[film])
    }

    @Test
    fun equalGuestsDropTheLaterTitleFirst() {
        val lib = Library()
        val own = lib.show(eps = 7, genres = listOf("Drama"))
        val g1 = lib.show(eps = 2, genres = listOf("Drama"))
        val g2 = lib.show(eps = 2, genres = listOf("Drama"))
        val homesOf =
            listOf(g1, g2).mapIndexed { i, s ->
                channel("ch-h$i", 100 + i, listOf("Comedy"), lib.itemsOf(listOf(s)), anchors = listOf(Source.Series(s)))
            }
        val g = channel("ch-g", 200, listOf("Drama"), lib.itemsOf(listOf(own, g1, g2)))
        // 4 guest hours on 7 home hours is over 3/7; dropping one equal guest leaves 2, which fits.
        val pool = Curation.pools(lib.snapshot(), homesOf + g).getValue(g.id)
        assertEquals(setOf(own, g1), pool.titles())
    }

    @Test
    fun removingAChannelChangesNoTitlesFormat() {
        val lib = Library()
        val s = lib.show(eps = 0)
        val animeEp = lib.addEpisode(s, genres = listOf("Anime"))
        val dramaEp = lib.addEpisode(s, genres = listOf("Drama"))
        val animeOnly = Theme(emptyList(), emptyList(), null, animeOnly = true)
        val x = channel("ch-x", 300, listOf("Anime"), listOf(animeEp), theme = animeOnly)
        val y = channel("ch-y", 200, listOf("Drama"), listOf(dramaEp))
        val snap = lib.snapshot()
        // The series is anime from the snapshot, whichever channels are curated.
        assertEquals(x.id, Curation.homes(snap, listOf(x, y)).homeOf(s))
        assertNull(Curation.homes(snap, listOf(y)).homeOf(s))
        assertTrue(
            Curation
                .pools(snap, listOf(y))
                .getValue(y.id)
                .items
                .isEmpty(),
        )
    }

    @Test
    fun removingAChannelChangesNoTitlesTone() {
        val lib = Library()
        // An unrated animated series: one episode rated TV-MA airs only on x, the other is TV-PG.
        val s = lib.show(eps = 0, genres = listOf("Animation"))
        val maEp = lib.addEpisode(s, genres = listOf("Animation"), rating = "TV-MA")
        val pgEp = lib.addEpisode(s, genres = listOf("Animation"), rating = "TV-PG")
        val live = lib.show(eps = 3, genres = listOf("Drama"))
        val toon = lib.show(eps = 1, genres = listOf("Animation"), rating = "TV-MA")
        val home = channel("ch-a", 100, listOf("Comedy"), listOf(pgEp), anchors = listOf(Source.Series(s)))
        // g's homes are a quarter adult animation, so s guests there only while its tone is adult animation.
        val g = channel("ch-g", 200, listOf("Drama"), listOf(pgEp) + lib.itemsOf(listOf(live, toon)))
        val x = channel("ch-x", 300, listOf("Comedy"), listOf(maEp))
        val snap = lib.snapshot()
        assertTrue(s in Curation.pools(snap, listOf(home, g, x)).getValue(g.id).titles())
        assertTrue(s in Curation.pools(snap, listOf(home, g)).getValue(g.id).titles())
    }
}
