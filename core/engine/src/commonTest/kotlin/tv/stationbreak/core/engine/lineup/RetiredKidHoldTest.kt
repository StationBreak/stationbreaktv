// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A retired kid recipe's channel becomes Custom on migration but stays kids-only by its ID's key (#1044). */
class RetiredKidHoldTest {
    private val kid = idOf(0x9001)
    private val noEvidence = idOf(0x9002)
    private val unrated = idOf(0x9003)
    private val familyPg = idOf(0x9004)
    private val library =
        snapshot(
            listOf(
                movie(kid, rating = "G", genres = listOf("Animation", "Family")),
                movie(noEvidence, rating = "G", genres = listOf("Animation")),
                movie(unrated, genres = listOf("Animation", "Family")),
                movie(familyPg, rating = "PG", genres = listOf("Animation", "Family")),
            ),
        )

    private fun stored(
        key: String,
        number: Int,
        leftovers: Boolean = false,
    ): LineupChannel {
        val source =
            if (leftovers) {
                ChannelSource.Leftovers(key, "PG")
            } else {
                ChannelSource.Starter(key, false, rules(listOf(Source.Genre("Animation")), maxRating = "PG"))
            }
        val id = if (leftovers) LineupIds.leftovers(key) else LineupIds.starter(key)
        return LineupChannel(id, number, null, null, ChannelState.On, source, 5L)
    }

    private fun migrated(channel: LineupChannel): LineupChannel {
        val old = Lineup(SERVER, USER, StarterCatalog.VERSION - 1, 0, true, listOf(channel))
        return planOf(old, library, StarterCatalog).channels.first { it.id == channel.id }
    }

    @Test
    fun aMigratedRetiredKidChannelIsKidsOnlyAtEveryOldNumber() {
        val cases = listOf("lights-out" to 44, "lights-out" to 20, "lights-out" to 150, "tall-tales-club" to 72)
        for ((key, number) in cases) {
            val now = migrated(stored(key, number))
            val custom = assertIs<ChannelSource.Custom>(now.source, "$key at $number")
            assertTrue(KidStatus.isKidChannel(now), "$key at $number")
            val policy = KidStatus.basePolicyOf(now)
            assertEquals(PoolPolicy.KidsOnly, policy, "$key at $number")
            val kept =
                PoolEvaluator
                    .evaluate(custom.rules, library, policy)
                    .items
                    .map { it.id }
                    .toSet()
            assertEquals(setOf(kid), kept, "$key at $number")
            // The hold is what excludes them: an open custom channel would air more.
            val open =
                PoolEvaluator
                    .evaluate(custom.rules, library, PoolPolicy.Custom)
                    .items
                    .map { it.id }
                    .toSet()
            assertTrue(noEvidence in open || familyPg in open, "$key at $number")
        }
    }

    @Test
    fun aKidKeyedLeftoversIdIsKidByKeyWhenCustom() {
        val rules = rules(listOf(Source.Genre("Animation")))
        val id = LineupIds.leftovers("kids-odds-and-ends")
        val kidOdds = LineupChannel(id, 150, null, null, ChannelState.On, ChannelSource.Custom(rules), 5L)
        assertTrue(KidStatus.isKidChannel(kidOdds))
        assertEquals(PoolPolicy.KidsOnly, KidStatus.basePolicyOf(kidOdds))
    }

    // #1052: a Family-tier former kid key keeps its PG cap once its recipe is retired and the stored copy is Custom,
    // wherever it is numbered and whatever ceiling it stored.
    @Test
    fun aRetiredFormerKidFamilyKeyKeepsItsPgCapWhateverItStored() {
        val retiredKeys = StarterCatalog.KIDS_KEYS_NOW_FAMILY
        assertTrue(retiredKeys.isNotEmpty())
        for (key in retiredKeys) {
            for (number in listOf(150, 640)) {
                for (stored in listOf(null, "R", "TV-MA")) {
                    val channel =
                        LineupChannel(
                            LineupIds.starter(key),
                            number,
                            null,
                            null,
                            ChannelState.On,
                            ChannelSource.Custom(rules(listOf(Source.Genre("Animation")), maxRating = stored)),
                            5L,
                        )
                    assertEquals("PG", ChannelCeilings.of(channel, StarterCatalog), "$key at $number storing $stored")
                }
            }
        }
    }

    @Test
    fun aRetiredGrownUpKeyAndAViewersOwnChannelKeepTheirStoredCeiling() {
        for (id in listOf(LineupIds.starter("alpha"), ChannelId("custom:x"))) {
            val channel =
                LineupChannel(
                    id,
                    150,
                    null,
                    null,
                    ChannelState.On,
                    ChannelSource.Custom(rules(listOf(Source.Genre("Drama")), maxRating = "R")),
                    5L,
                )
            assertEquals("R", ChannelCeilings.of(channel, StarterCatalog), id.value)
        }
    }

    @Test
    fun recipeKeyReadsTheKeyFromTheSourceOrTheIdAndNothingForAViewersChannel() {
        val rules = rules(listOf(Source.Genre("Animation")))

        fun custom(id: ChannelId) = LineupChannel(id, 150, null, null, ChannelState.On, ChannelSource.Custom(rules), 5L)
        assertEquals("lights-out", stored("lights-out", 44).recipeKey())
        assertEquals("kids-odds-and-ends", stored("kids-odds-and-ends", 44, leftovers = true).recipeKey())
        assertEquals("lights-out", custom(LineupIds.starter("lights-out")).recipeKey())
        assertEquals("odds", custom(LineupIds.leftovers("odds")).recipeKey())
        assertEquals(null, custom(ChannelId("custom:x")).recipeKey())
    }

    @Test
    fun aCustomChannelWithANonKidKeyStaysCustom() {
        val rules = rules(listOf(Source.Genre("Animation")))
        val ids = listOf(LineupIds.starter("alpha"), LineupIds.leftovers("odds-and-ends"), ChannelId("custom:x"))
        for (id in ids) {
            val channel = LineupChannel(id, 150, null, null, ChannelState.On, ChannelSource.Custom(rules), 5L)
            assertFalse(KidStatus.isKidChannel(channel), id.value)
            assertEquals(PoolPolicy.Custom, KidStatus.basePolicyOf(channel), id.value)
        }
    }
}
