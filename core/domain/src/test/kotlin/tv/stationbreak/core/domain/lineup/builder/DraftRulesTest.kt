// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filler
import tv.stationbreak.core.rules.FillerMode
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftRulesTest {
    private val offset = 60
    private val seed = Seed(42uL)
    private val comedy = DraftSource.Genre("Comedy", "Comedy")

    private fun draft(
        name: String = "Funny Films",
        sources: List<DraftSource> = listOf(comedy),
        order: PlayOrder = PlayOrder.Shuffle,
    ) = ChannelDraft(name, sources, emptyList(), DraftFilters(), order = order, kids = false, seed = seed)

    // An edit of [previous] when it is set (the saved channel keeps its version), else a new channel (written at 5).
    private fun toRules(
        draft: ChannelDraft,
        previous: ChannelRules? = null,
    ) = DraftRules.toRules(draft, offset, previous?.let { RulesTarget.Edit(it) } ?: RulesTarget.NewChannel(null))

    private fun fromRules(
        rules: ChannelRules,
        number: Int? = null,
    ) = DraftRules.fromRules(rules, number) { it.key }

    @Test
    fun aDefaultDraftWritesShuffleWithCaps3And1AndVersion5() {
        val rules = toRules(draft())

        assertEquals(2, rules.requiredVersion)
        assertEquals(5, rules.formatVersion, "a new channel has the floor of 5 (Task 6)")
        assertEquals(OrderingMode.Shuffle, rules.orderingMode)
        assertEquals(3, rules.effectiveCaps?.series)
        assertEquals(1, rules.effectiveCaps?.film)
    }

    @Test
    fun inOrderWritesNoCapsAndVersion5() {
        val rules = toRules(draft(order = PlayOrder.InOrder))

        assertEquals(OrderingMode.InOrder, rules.orderingMode)
        assertNull(rules.ordering?.caps)
        assertEquals(2, rules.requiredVersion)
        assertEquals(5, rules.formatVersion, "a new channel has the floor of 5 (Task 6)")
    }

    private val keptRules =
        ChannelRules(
            formatVersion = 1,
            name = "Western",
            seed = seed,
            sources = listOf(Source.Genre("Western"), Source.YearRange(1960, 1969)),
            exclusions = null,
            maxRating = "PG",
            ordering = Ordering(OrderingMode.RandomInOrder),
            timing = Timing(3, null, null, null, null, null, BroadcastDay(offset, "06:00")),
            filler = Filler(FillerMode.PadToGrid, null, Source.Tag("filler")),
            extras = JsonObject(mapOf("note" to kotlinx.serialization.json.JsonPrimitive("x"))),
        )

    @Test
    fun aDraftWithoutAnyV2FeatureWritesVersion1() {
        val chronological = keptRules.copy(ordering = Ordering(OrderingMode.Chronological), filler = null)

        val rules = toRules(fromRules(chronological), chronological)

        assertEquals(1, rules.formatVersion)
        assertEquals(chronological, rules)
    }

    @Test
    fun timingIsBackToBackAndA24HourEpoch() {
        val timing = toRules(draft()).timing

        assertEquals(false, timing.alignToGrid)
        assertEquals(24, timing.epochHours)
        assertEquals(BroadcastDay(offset, "06:00"), timing.broadcastDay)
        assertNull(timing.blockLength)
        assertNull(timing.graceHours)
        assertNull(timing.gridMinutes)
        assertNull(timing.minRuntimeSeconds)
    }

    @Test
    fun filtersAreOmittedWhenEmpty() {
        assertNull(toRules(draft()).filters)
        val filtered = draft().copy(filters = DraftFilters(genres = listOf("Drama"), only = OnlyKind.Films))
        assertEquals(listOf("Drama"), toRules(filtered).filters?.genres)
    }

    @Test
    fun aNameOfOnlySpacesIsRefused() {
        assertEquals(DraftProblem.NameEmpty, draft(name = "   ").problem())
    }

    @Test
    fun aNameOf101CodePointsIsRefusedAnd100WithEmojiPasses() {
        assertEquals(DraftProblem.NameTooLong, draft(name = "a".repeat(101)).problem())
        assertNull(draft(name = "a".repeat(99) + "😀").problem())
    }

    @Test
    fun trimsTheName() {
        assertEquals("Funny Films", toRules(draft(name = "  Funny Films ")).name)
    }

    private val plainShuffle = keptRules.copy(ordering = null, filler = null, extras = JsonObject(emptyMap()))

    private val bareShuffle =
        plainShuffle.copy(
            sources = listOf(Source.Genre("Western")),
            timing = Timing(null, false, null, null, null, null, BroadcastDay(offset, "06:00")),
        )

    @Test
    fun aCopiedShuffleWithNoCapsStaysUncappedAtVersion1() {
        val draft = fromRules(plainShuffle)

        assertTrue(KeptItem.NoCaps in assertNotNull(draft.kept).items)
        val rules = toRules(draft, plainShuffle)
        assertEquals(1, rules.formatVersion)
        assertEquals(plainShuffle, rules)
    }

    @Test
    fun turningCapsOnOrTakingTheBuildersSettingsAppliesCaps() {
        val draft = fromRules(plainShuffle)
        val withoutNoCaps = draft.copy(seriesCap = 3, filmCap = 1)

        for (capped in listOf(toRules(withoutNoCaps, plainShuffle), toRules(draft.copy(kept = null), plainShuffle))) {
            assertEquals(3, capped.effectiveCaps?.series)
            assertEquals(1, capped.effectiveCaps?.film)
            assertEquals(2, capped.formatVersion)
        }
    }

    @Test
    fun anEpochBelow24IsKeptUntilCapsAreOnOrTheBuildersSettingsAreTaken() {
        val short = plainShuffle.copy(timing = plainShuffle.timing.copy(epochHours = 12))
        val draft = fromRules(short)

        assertTrue(KeptItem.EpochHours(12) in assertNotNull(draft.kept).items)
        assertEquals(short, toRules(draft, short))
        val capsOn = draft.copy(seriesCap = 3, filmCap = 1)
        assertEquals(24, toRules(capsOn, short).timing.epochHours)
        assertEquals(24, toRules(draft.copy(kept = null), short).timing.epochHours)
    }

    @Test
    fun aYearOnlyCopyHasASourceAndSaves() {
        val yearOnly = plainShuffle.copy(sources = listOf(Source.YearRange(1950, 1959)))
        val draft = fromRules(yearOnly)

        assertTrue(draft.sources.isEmpty())
        assertNull(draft.problem())
        assertEquals(yearOnly, toRules(draft, yearOnly))
        assertEquals(DraftProblem.NoSources, draft.copy(kept = null).problem())
    }

    @Test
    fun unknownFieldsOnSourcesAndExclusionsSurviveACopy() {
        val extra = JsonObject(mapOf("futureSourceField" to kotlinx.serialization.json.JsonPrimitive(true)))
        val other = JsonObject(mapOf("futureExclusionField" to kotlinx.serialization.json.JsonPrimitive("x")))
        val old =
            plainShuffle.copy(
                sources = listOf(Source.Genre("Drama", extra)),
                exclusions = listOf(Source.Tag("Skip", other)),
            )

        val rules = toRules(fromRules(old), old)

        assertEquals(old, rules)
        assertEquals(extra, rules.sources.single().extras)
        assertEquals(other, rules.exclusions?.single()?.extras)
    }

    @Test
    fun duplicateSourcesKeepTheirOwnExtras() {
        fun flag(n: Int) = JsonObject(mapOf("future" to kotlinx.serialization.json.JsonPrimitive(n)))
        val old =
            plainShuffle.copy(
                sources = listOf(Source.Genre("Drama", flag(1)), Source.Tag("x"), Source.Genre("Drama", flag(2))),
                exclusions = listOf(Source.Tag("Skip", flag(3)), Source.Tag("Skip", flag(4))),
            )

        val rules = toRules(fromRules(old), old)

        assertEquals(old, rules)
    }

    @Test
    fun keptSettingsNeedTheirRules() {
        assertFailsWith<IllegalArgumentException> { toRules(fromRules(keptRules)) }
    }

    @Test
    fun choosingAnOrderOrCapsTakesTheKeptItemsPlace() {
        val short = bareShuffle.copy(timing = bareShuffle.timing.copy(epochHours = 12))
        val kept = fromRules(short)
        assertEquals(listOf(KeptItem.NoCaps, KeptItem.EpochHours(12)), kept.keptInForce)
        assertTrue(kept.seriesCap == null && kept.filmCap == null)

        val capped = kept.copy(seriesCap = 2, filmCap = 2)
        assertTrue(capped.keptInForce.none { it == KeptItem.NoCaps || it is KeptItem.EpochHours })
        assertEquals(2, toRules(capped, short).effectiveCaps?.series)

        val chronological = fromRules(keptRules.copy(ordering = Ordering(OrderingMode.Chronological), filler = null))
        assertNull(chronological.order)
        val keptOrders = chronological.keptInForce.filterIsInstance<KeptItem.Order>()
        assertEquals(listOf(KeptItem.Order(KeptOrder.Chronological)), keptOrders)
        val chosen = chronological.copy(order = PlayOrder.Rotation)
        assertTrue(chosen.keptInForce.none { it is KeptItem.Order })
        assertEquals(OrderingMode.Rotation, toRules(chosen, keptRules).orderingMode)
    }

    @Test
    fun changingTheOrderOfADraftWithAKeptOrderSavesTheNewOrder() {
        val stored = keptRules.copy(ordering = Ordering(OrderingMode.Chronological), filler = null)
        val chronological = fromRules(stored)

        // Any copy(...) that gives the order a value is the viewer's choice; the kept order cannot win by mistake.
        for (order in PlayOrder.entries) {
            val expected =
                when (order) {
                    PlayOrder.Shuffle -> OrderingMode.Shuffle
                    PlayOrder.Rotation -> OrderingMode.Rotation
                    PlayOrder.InOrder -> OrderingMode.InOrder
                }
            assertEquals(expected, toRules(chronological.copy(order = order), stored).orderingMode, "$order")
        }
        assertEquals(OrderingMode.Chronological, toRules(chronological, stored).orderingMode, "left alone, it stays")
        assertEquals(OrderingMode.Chronological, toRules(chronological.copy(name = "Other"), stored).orderingMode)
    }

    @Test
    fun droppingTheLastKeptItemLeavesNothingInForce() {
        val draft = fromRules(bareShuffle)
        assertEquals(listOf<KeptItem>(KeptItem.NoCaps), draft.kept?.items)

        assertEquals(emptyList(), draft.copy(seriesCap = 3, filmCap = 1).keptInForce)
    }

    @Test
    fun aDraftAtTheFormatsLimitsWritesRulesThatReadBackUnchanged() {
        val filters =
            DraftFilters(
                genres = List(256) { "Genre $it" },
                yearFrom = 1,
                yearTo = 9999,
                minMinutes = 1,
                maxMinutes = 1440,
                only = OnlyKind.Films,
            )
        val left = List(256) { DraftSource.Tag("Left $it", "Left $it") }
        val extreme =
            draft(sources = List(256) { DraftSource.Genre("Source $it", "Source $it") })
                .copy(filters = filters, leaveOut = left, seriesCap = 1, filmCap = 10)
        assertNull(extreme.problem())

        val rules = toRules(extreme)
        val text = RulesWriter.write(rules)

        assertEquals(rules, (RulesReader.read(text) as RulesResult.Ok).rules)
    }

    @Test
    fun capsGivenForOneOfSeriesAndFilmsAreIncomplete() {
        assertEquals(DraftProblem.CapsIncomplete, draft().copy(seriesCap = null).problem())
        assertNull(draft(order = PlayOrder.InOrder).copy(seriesCap = null).problem())
    }

    @Test
    fun anInOrderDraftAndAKeptOrderDraftWriteNoCaps() {
        assertTrue(!draft(order = PlayOrder.InOrder).writesCaps())
        assertNull(toRules(draft(order = PlayOrder.InOrder)).ordering?.caps)

        val stored = keptRules.copy(ordering = Ordering(OrderingMode.Chronological), filler = null)
        val kept = fromRules(stored)
        assertTrue(!kept.writesCaps())
        assertNull(toRules(kept, stored).ordering?.caps)
        assertNull(kept.copy(seriesCap = 11).problem(), "caps nobody writes are not checked")

        // An order left null without a kept order is Shuffle, and Shuffle writes caps.
        val plain = draft().copy(order = null)
        assertTrue(plain.writesCaps())
        assertEquals(DraftProblem.CapOutOfRange, plain.copy(seriesCap = 11).problem())
        assertEquals(3, toRules(plain).effectiveCaps?.series)
    }

    @Test
    fun keptYearRangesCountTowardTheSourceLimit() {
        val years = KeptSettings("x", listOf(KeptItem.YearsSource(1950, 1959)))
        val full = List(256) { DraftSource.Genre("G$it", "G$it") }

        assertEquals(DraftProblem.TooManySources, draft(sources = full).copy(kept = years).problem())
        assertNull(draft(sources = full.take(255)).copy(kept = years).problem())
    }

    @Test
    fun theFormatsListLimitsAreProblems() {
        val many = List(257) { DraftSource.Genre("G$it", "G$it") }
        assertEquals(DraftProblem.TooManySources, draft(sources = many).problem())
        assertNull(draft(sources = many.take(256)).problem())
        assertEquals(DraftProblem.TooManyLeftOut, draft().copy(leaveOut = many).problem())
        val genres = DraftFilters(genres = List(257) { "G$it" })
        assertEquals(DraftProblem.TooManyGenres, draft().copy(filters = genres).problem())
        assertNull(draft().copy(filters = DraftFilters(genres = List(256) { "G$it" })).problem())
    }

    @Test
    fun twoSecureDrawsDiffer() {
        val source = NewSeed.secure()

        assertTrue(source.next() != source.next())
    }

    @Test
    fun otherProblemsAreNamed() {
        assertEquals(DraftProblem.NoSources, draft(sources = emptyList()).problem())
        assertEquals(DraftProblem.CapOutOfRange, draft().copy(seriesCap = 11).problem())
        assertNull(draft(order = PlayOrder.InOrder).copy(seriesCap = 11).problem())

        fun problemOf(filters: DraftFilters) = draft().copy(filters = filters).problem()
        assertEquals(DraftProblem.YearsReversed, problemOf(DraftFilters(yearFrom = 2000, yearTo = 1990)))
        assertEquals(DraftProblem.YearsIncomplete, problemOf(DraftFilters(yearFrom = 2000)))
        assertEquals(DraftProblem.YearsIncomplete, problemOf(DraftFilters(yearTo = 2000)))
        assertEquals(DraftProblem.YearsOutOfRange, problemOf(DraftFilters(yearFrom = 0, yearTo = 2000)))
        assertEquals(DraftProblem.YearsOutOfRange, problemOf(DraftFilters(yearFrom = 2000, yearTo = 10000)))
        assertEquals(DraftProblem.MinutesReversed, problemOf(DraftFilters(minMinutes = 90, maxMinutes = 60)))
        assertEquals(DraftProblem.MinutesOutOfRange, problemOf(DraftFilters(minMinutes = 0)))
        assertEquals(DraftProblem.MinutesOutOfRange, problemOf(DraftFilters(maxMinutes = 1441)))
    }

    @Test
    fun fromRulesRoundTripsToRules() {
        val random = Random(0x5EED)
        repeat(50) {
            val built = toRules(randomDraft(random))

            val again = toRules(fromRules(built))

            assertEquals(built, again)
        }
    }

    private fun randomDraft(random: Random): ChannelDraft {
        val pool =
            listOf<DraftSource>(
                DraftSource.Library(id(1).value, "L"),
                DraftSource.Collection(id(2).value, "C"),
                DraftSource.Tag("late", "late"),
                comedy,
                DraftSource.Show(id(3).value, "S"),
                DraftSource.Pick(id(4).value, "P"),
            )
        val years = random.nextBoolean()
        val from = random.nextInt(1950, 2000)
        val min = random.nextInt(1, 100).takeIf { random.nextBoolean() }
        return ChannelDraft(
            name = "Channel ${random.nextInt(100)}",
            sources = pool.shuffled(random).take(random.nextInt(1, 4)),
            leaveOut = pool.shuffled(random).take(random.nextInt(0, 2)),
            filters =
                DraftFilters(
                    genres = listOf("Drama", "Crime").take(random.nextInt(0, 3)),
                    yearFrom = from.takeIf { years },
                    yearTo = (from + random.nextInt(0, 30)).takeIf { years },
                    minMinutes = min,
                    maxMinutes = min?.let { it + random.nextInt(0, 100) },
                    only = listOf(null, OnlyKind.Films, OnlyKind.Shows).random(random),
                    maxRating = listOf(null, "G", "PG").random(random),
                ),
            order = PlayOrder.entries.random(random),
            kids = false,
            seriesCap = random.nextInt(1, 11),
            filmCap = random.nextInt(1, 11),
            seed = Seed(random.nextLong().toULong()),
        )
    }

    @Test
    fun aCopyKeepsWhatTheBuilderCannotShow() {
        val draft = fromRules(keptRules)

        val kept = assertNotNull(draft.kept)
        assertEquals("Western", kept.fromName)
        assertTrue(KeptItem.Order(KeptOrder.RandomInOrder) in kept.items)
        assertTrue(KeptItem.BlockLength(3) in kept.items)
        assertTrue(KeptItem.YearsSource(1960, 1969) in kept.items)
        assertTrue(KeptItem.Filler in kept.items)
        assertNull(draft.order, "a kept order is no order of the builder's")
        assertEquals(listOf<DraftSource>(DraftSource.Genre("Western", "Western")), draft.sources)
        assertEquals(keptRules, toRules(draft, keptRules))
    }

    @Test
    fun useTheBuildersSettingsDropsTheKeptOnes() {
        val draft = fromRules(keptRules).copy(kept = null)

        val rules = toRules(draft, keptRules)

        assertEquals(OrderingMode.Shuffle, rules.orderingMode)
        assertEquals(listOf<Source>(Source.Genre("Western")), rules.sources)
        assertNull(rules.timing.blockLength)
        assertEquals(false, rules.timing.alignToGrid)
        assertNull(rules.filler)
        assertTrue(rules.extras.isEmpty())
        assertEquals(2, rules.formatVersion)
    }

    @Test
    fun useTheBuildersSettingsDropsKeptFieldsOnSave() {
        val busy = keptRules.copy(timing = keptRules.timing.copy(graceHours = 2, gridMinutes = 15, epochHours = 12))

        val rules = toRules(fromRules(busy).copy(kept = null), busy)

        val plain = draft("Western", listOf(DraftSource.Genre("Western", "Western")))
        // The new channel is at 5; the saved one keeps the lowest version its rules need.
        assertEquals(toRules(plain).copy(maxRating = "PG", formatVersion = 2), rules)
        assertNull(rules.timing.graceHours)
        assertNull(rules.timing.gridMinutes)
        assertEquals(24, rules.timing.epochHours)
    }

    @Test
    fun theDraftCarriesTheSeedIntoTheRules() {
        assertEquals(Seed(9uL), toRules(draft().copy(seed = Seed(9uL))).seed)
        assertEquals(keptRules.seed, fromRules(keptRules).seed)
    }

    @Test
    fun aCopyWithAFiltersExtraSavesWithRealFilters() {
        val stale = JsonObject(mapOf("filters" to JsonObject(emptyMap())))
        val old = keptRules.copy(ordering = null, filler = null, extras = stale)
        val withYears = fromRules(old).copy(filters = DraftFilters(yearFrom = 1990, yearTo = 1999))

        val rules = toRules(withYears, old)

        assertEquals(2, rules.formatVersion)
        assertEquals(1990, rules.filters?.yearFrom)
        assertFalse("filters" in rules.extras)
    }

    @Test
    fun aStaleFiltersExtraSurvivesAVersion1Save() {
        val stale = JsonObject(mapOf("filters" to JsonObject(emptyMap())))
        val old = keptRules.copy(ordering = Ordering(OrderingMode.Chronological), filler = null, extras = stale)

        assertEquals(old, toRules(fromRules(old), old))
    }

    @Test
    fun newSeedReadsEightBytesFromTheGivenRandomAsUnsigned() {
        val bytes = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 1)
        val random =
            object : SecureRandom() {
                override fun nextBytes(out: ByteArray) {
                    bytes.copyInto(out)
                }
            }

        assertEquals(Seed(9223372036854775809uL), NewSeed.secure(random).next())
    }
}
