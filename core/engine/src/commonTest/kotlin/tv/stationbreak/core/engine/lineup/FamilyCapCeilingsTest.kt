// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.LIB
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.RatingMap
import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #1046: a custom channel's family cap is its number band (Tots 1 to 33, Kids 34 to 66, Teens 67 to 99; #1152), and the
 * cap holds what it airs. The ceilings: Tots TV-Y7 (level 1), Kids PG / TV-PG (level 2), Teens PG-13 / TV-14 (level 4).
 * Every title a channel airs is within its cap on every rating it carries, and a title with no readable rating (none,
 * blank, `NR`, `Approved`, a name the map does not know) never airs under any cap. A thin pool is a thin channel: a
 * library of nothing but above-cap titles airs nothing. The cap is a property of the channel (its number and its own
 * ceiling), so two TVs reading the same lineup schedule the same channel (ADR-0017).
 */
class FamilyCapCeilingsTest {
    private data class Cap(
        val label: String,
        val number: Int,
        val level: Int,
        val ceilings: List<String>,
    )

    private val ladderNames = RatingLadder.tiers.map { it.first() }

    // Each cap with the ceilings its builder offers, plus every ladder name at or below the cap: a stored ceiling the
    // viewer cannot pick now (PG on a Tots channel, an old file) is held to the cap all the same.
    private val caps =
        listOf(
            Cap("Tots", 12, 1, listOf("G", "TV-Y7", "PG")),
            Cap("Kids", 40, 2, ladderNames.take(checkNotNull(RatingLadder.rankOf("PG")) + 1)),
            Cap("Teens", 80, 4, ladderNames.take(checkNotNull(RatingLadder.rankOf("TV-14")) + 1)),
        )

    private val rated =
        listOf(
            "G",
            "TV-G",
            "TV-Y",
            "TV-Y7",
            "TV-Y7-FV",
            "PG",
            "TV-PG",
            "PG-13",
            "TV-14",
            "R",
            "TV-MA",
            "NC-17",
            "X",
            "DE-0",
            "DE-12",
            "DE-16",
            "AU-PG",
            "AU-M",
        )
    private val unreadable = listOf(null, "", " ", "NR", "Not Rated", "Unrated", "Approved", "Passed", "NOT-A-RATING")

    private val kidsGenre = listOf("Children", "Animation", "Family")

    // Movies at every rating, with and without made-for-kids evidence; every unreadable rating the same way.
    private val movies: List<RuleItem> =
        (rated + unreadable).flatMapIndexed { index, rating ->
            listOf(
                movie(idOf(0x100 + index * 2), rating = rating),
                movie(idOf(0x101 + index * 2), rating = rating, genres = kidsGenre),
            )
        }

    // Episodes: the series' rating and the episode's own differ both ways, a series missing from the snapshot, an
    // episode of no series, all with a kids genre so a Tots channel can take what its rating allows.
    private val shows =
        listOf(
            series(idOf(0x500), rating = "G", genres = kidsGenre),
            series(idOf(0x501), rating = "TV-MA", genres = kidsGenre),
            series(idOf(0x502), rating = "TV-14", genres = kidsGenre),
            series(idOf(0x503), rating = null, genres = kidsGenre),
            series(idOf(0x504), rating = "Approved", genres = kidsGenre),
        )
    private val episodes: List<RuleItem> =
        listOf(
            episode(idOf(0x600), idOf(0x500), rating = "TV-Y7", genres = kidsGenre),
            episode(idOf(0x601), idOf(0x500), rating = null, genres = kidsGenre),
            episode(idOf(0x602), idOf(0x500), rating = "TV-MA", genres = kidsGenre),
            episode(idOf(0x603), idOf(0x501), rating = "G", genres = kidsGenre),
            episode(idOf(0x604), idOf(0x501), rating = null, genres = kidsGenre),
            episode(idOf(0x605), idOf(0x502), rating = "TV-14", genres = kidsGenre),
            episode(idOf(0x606), idOf(0x502), rating = null, genres = kidsGenre),
            episode(idOf(0x607), idOf(0x503), rating = "TV-Y7", genres = kidsGenre),
            episode(idOf(0x608), idOf(0x503), rating = null, genres = kidsGenre),
            episode(idOf(0x609), idOf(0x504), rating = "TV-Y", genres = kidsGenre),
            episode(idOf(0x60A), idOf(0x7FF), rating = "G", genres = kidsGenre),
            episode(idOf(0x60B), null, rating = "G", genres = kidsGenre),
            episode(idOf(0x60C), null, rating = null, genres = kidsGenre),
        )
    private val everything = (movies + episodes).sortedBy { it.item.id.toString() }
    private val library = snapshot(items = everything, series = shows)

    private fun custom(
        number: Int,
        ceiling: String?,
    ) = LineupChannel(
        ChannelId("custom:$number"),
        null,
        number,
        null,
        ChannelState.On,
        ChannelSource.Custom(rules(listOf(Source.Library(LIB)), maxRating = ceiling)),
        5L,
    )

    // The pool a channel airs: the ceiling and the policy the day build gives it.
    private fun airs(
        channel: LineupChannel,
        snap: tv.stationbreak.core.model.RuleSnapshot = library,
    ): List<RuleItem> {
        val own = (channel.source as ChannelSource.Custom).rules
        val ruled = own.copy(maxRating = ChannelCeilings.of(channel, StarterCatalog))
        val ids =
            PoolEvaluator
                .evaluate(ruled, snap, KidStatus.basePolicyOf(channel))
                .items
                .map { it.id }
                .toSet()
        return snap.items.filter { it.item.id in ids }
    }

    private fun seriesOf(entry: RuleItem): RuleSeries? = entry.item.seriesId?.let { library.series[it] }

    // Every rating a title carries (its own, its known series') must be one the map reads, at or under the cap.
    private fun withinCap(
        entry: RuleItem,
        level: Int,
    ): Boolean {
        val ratings = listOf(entry.officialRating, seriesOf(entry)?.officialRating).filter { !it.isNullOrBlank() }
        return ratings.isNotEmpty() && ratings.all { RatingMap.levelOf(it)?.let { l -> l <= level } == true }
    }

    // A kids channel (Tots) lets a known, rated series decide for its episodes (kid-hold-5): same level, series first.
    private fun withinTotsCap(entry: RuleItem): Boolean {
        val seriesRating = seriesOf(entry)?.officialRating?.takeIf { it.isNotBlank() }
        val decisive = seriesRating ?: entry.officialRating
        return !decisive.isNullOrBlank() && RatingMap.levelOf(decisive)?.let { it <= 1 } == true
    }

    @Test
    fun noChannelAirsATitleAboveItsCapAtAnyOfItsCeilings() {
        for (cap in caps) {
            for (ceiling in cap.ceilings) {
                val aired = airs(custom(cap.number, ceiling))
                val label = "${cap.label} at $ceiling"
                for (entry in aired) {
                    val ok = if (cap.level == 1) withinTotsCap(entry) else withinCap(entry, cap.level)
                    assertTrue(ok, "$label aired ${entry.item.id} (${entry.officialRating}, ${seriesOf(entry)?.officialRating})")
                }
            }
        }
    }

    @Test
    fun noTitleWithoutAReadableRatingAirsUnderAnyCap() {
        val blank = unreadable.filter { it.isNullOrBlank() }
        for (cap in caps) {
            for (ceiling in cap.ceilings) {
                val aired = airs(custom(cap.number, ceiling))
                for (entry in aired) {
                    val own = entry.officialRating
                    val shows = seriesOf(entry)?.officialRating
                    val readable = listOf(own, shows).filter { !it.isNullOrBlank() }
                    assertTrue(readable.isNotEmpty(), "${cap.label} at $ceiling aired a title with no rating at all")
                    assertTrue(
                        readable.all { RatingMap.levelOf(it) != null },
                        "${cap.label} at $ceiling aired $own / $shows: a rating the map cannot read",
                    )
                }
            }
        }
        assertTrue(blank.isNotEmpty(), "the fixture holds unrated titles")
    }

    @Test
    fun everyCapAirsSomethingSoTheChecksAboveAreNotVacuous() {
        for (cap in caps) {
            val aired = airs(custom(cap.number, cap.ceilings.last()))
            assertTrue(aired.isNotEmpty(), "${cap.label} airs titles inside its cap")
        }
        // A higher cap airs at least what a lower one does (Kids and Teens read the same titles Tots' own do).
        val kids = airs(custom(40, "PG")).map { it.item.id }.toSet()
        val teens = airs(custom(80, "TV-14")).map { it.item.id }.toSet()
        assertTrue(teens.containsAll(kids), "a Teens channel airs what a Kids channel at PG airs")
    }

    @Test
    fun aThinPoolStaysThinAndNeverTakesAnAboveCapTitle() {
        val above = (0 until 20).map { movie(idOf(0x900 + it), rating = listOf("R", "TV-MA", "NC-17", null, "Approved")[it % 5]) }
        val thin = snapshot(items = above)
        for (cap in caps) {
            for (ceiling in cap.ceilings) {
                assertEquals(emptyList(), airs(custom(cap.number, ceiling), thin), "${cap.label} at $ceiling stays empty")
            }
        }
    }

    // The cap is the number band, so a ceiling above a band's cap puts the channel in a higher band: the band a viewer's
    // own channel is numbered in never holds a ceiling above its cap, and that is what keeps Family Mode (which reads
    // the number alone) and the content cap in step.
    @Test
    fun aCeilingAboveABandsCapNeedsAHigherBand() {
        val pairs = mapOf("PG" to Band.Family, "PG-13" to Band.Teen, "TV-14" to Band.Teen, "R" to Band.General)
        for ((ceiling, band) in pairs) {
            val floor = ChannelFloors.of(custom(700, ceiling), StarterCatalog)
            assertTrue(floor >= band.numbers.first, "$ceiling needs at least ${band.numbers.first}, got $floor")
        }
        assertNotEquals(ChannelFloors.of(custom(700, "TV-14"), StarterCatalog), ChannelFloors.of(custom(700, "PG"), StarterCatalog))
        assertTrue(ChannelFloors.of(custom(700, null), StarterCatalog) >= Band.General.numbers.first)
    }

    // ADR-0017: the cap lives on the channel (its number and its ceiling), not on the TV, so the same lineup gives the
    // same pool wherever it is read, and a Family Mode change moves no title in or out of a channel's pool.
    @Test
    fun theSameChannelAirsTheSameTitlesEveryTimeItIsRead() {
        for (cap in caps) {
            val channel = custom(cap.number, cap.ceilings.last())
            assertEquals(airs(channel), airs(channel.copy()), "${cap.label} reads the same twice")
        }
    }
}
