// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The builder with rules format 3 (spec 23): a copy keeps what it cannot show, and takes it back out on request. */
class DraftRulesV3Test {
    private val offset = 60
    private val seed = Seed(7uL)

    private val timing = Timing(null, false, 24, null, null, null, BroadcastDay(offset, "06:00"))

    private fun rules(
        sources: List<Source> = listOf(Source.Genre("Comedy")),
        ordering: Ordering? = Ordering(OrderingMode.Curated),
        filters: Filters? = null,
        exclusions: List<Source>? = null,
    ) = ChannelRules(
        formatVersion = 3,
        name = "Copy",
        seed = seed,
        sources = sources,
        exclusions = exclusions,
        maxRating = null,
        ordering = ordering,
        timing = timing,
        filler = null,
        filters = filters,
    ).let { it.copy(formatVersion = it.requiredVersion.coerceAtLeast(1)) }

    private fun fromRules(rules: ChannelRules) = DraftRules.fromRules(rules, null) { it.key }

    private fun toRules(
        draft: ChannelDraft,
        previous: ChannelRules? = null,
    ) = DraftRules.toRules(draft, offset, previous)

    // What "Use the builder's settings instead" does in the view model.
    private fun ChannelDraft.useBuildersSettings() =
        copy(
            order = PlayOrder.Shuffle,
            seriesCap = ChannelDraft.DEFAULT_SERIES_CAP,
            filmCap = ChannelDraft.DEFAULT_FILM_CAP,
        )

    @Test
    fun aCopyOfACuratedChannelKeepsItsOrderAndShowsTheLine() {
        val curated = rules(ordering = Ordering(OrderingMode.Curated, repeatGapMinutes = 120, pairUpToMinutes = 30))

        val draft = fromRules(curated)

        val kept = assertNotNull(draft.kept)
        assertTrue(KeptItem.Order(KeptOrder.Curated) in kept.items)
        assertTrue(KeptItem.OtherSettings in kept.items, "the tunables have no control")
        assertNull(draft.order)
        assertTrue(draft.keepsReplaceable())
        assertEquals(curated, toRules(draft, curated))
        assertEquals(3, toRules(draft, curated).formatVersion)
    }

    @Test
    fun studioSourcesAndThemeFiltersAreKeptAsOtherSettings() {
        val themed =
            rules(
                sources = listOf(Source.Studio("Studio A"), Source.Genre("Comedy")),
                ordering = null,
                filters =
                    Filters(
                        genres = listOf("Comedy"),
                        yearFrom = null,
                        yearTo = null,
                        minRuntimeMinutes = null,
                        maxRuntimeMinutes = null,
                        kind = null,
                        tags = listOf("tag a"),
                        notGenres = listOf("Horror"),
                        episodeMaxRuntimeMinutes = 40,
                    ),
                exclusions = listOf(Source.Studio("Studio B")),
            )

        val draft = fromRules(themed)

        val items = assertNotNull(draft.kept).items
        assertTrue(KeptItem.OtherSettings in items, "the theme filters have no control")
        assertTrue(KeptItem.StudioSource("Studio A") in items)
        assertTrue(KeptItem.StudioExclusion("Studio B") in items)
        assertEquals(listOf<DraftSource>(DraftSource.Genre("Comedy", "Comedy")), draft.sources)
        assertTrue(draft.hasPool(), "a studio source is a pool the builder cannot show")
        val back = toRules(draft, themed)
        assertEquals(themed, back)
        assertEquals(3, back.formatVersion)
        // The builder's own genre edit does not touch the parts it cannot show.
        val edited = toRules(draft.copy(filters = draft.filters.copy(genres = listOf("Drama"))), themed)
        assertEquals(listOf("tag a"), edited.filters?.tags)
        assertEquals(listOf("Horror"), edited.filters?.notGenres)
        assertEquals(40, edited.filters?.episodeMaxRuntimeMinutes)
        assertEquals(listOf<Source>(Source.Studio("Studio B")), edited.exclusions)
        assertEquals(Source.Studio("Studio A"), edited.sources.first())
    }

    @Test
    fun aStudioOnlyCopyCanBeSaved() {
        val onlyStudio = rules(sources = listOf(Source.Studio("Studio A")), ordering = null)

        val draft = fromRules(onlyStudio)

        assertNull(draft.problem())
        assertEquals(onlyStudio, toRules(draft, onlyStudio))
    }

    @Test
    fun studioSourcesAndExclusionsCountTowardTheLimits() {
        val sources = (1..256).map { Source.Studio("Studio $it") }
        val full = fromRules(rules(sources = sources, ordering = null))
        assertEquals(emptyList(), full.sources)
        assertNull(full.problem())
        val genre = listOf(DraftSource.Genre("Comedy", "Comedy"))
        assertEquals(DraftProblem.TooManySources, full.copy(sources = genre).problem())
        val left = fromRules(rules(ordering = null, exclusions = sources))
        assertNull(left.problem())
        val over = left.copy(leaveOut = listOf(DraftSource.Genre("Horror", "Horror")))
        assertEquals(DraftProblem.TooManyLeftOut, over.problem())
    }

    @Test
    fun aCuratedCopySwitchedToShuffleIsWrittenAsV2WithItsOrderingExtrasKept() {
        val extras = JsonObject(mapOf("future" to JsonPrimitive(1)))
        val curated = rules(ordering = Ordering(OrderingMode.Curated, repeatGapMinutes = 90, extras = extras))

        val written = toRules(fromRules(curated).copy(order = PlayOrder.Shuffle), curated)

        assertEquals(2, written.formatVersion)
        assertEquals(OrderingMode.Shuffle, written.orderingMode)
        assertNull(written.ordering?.repeatGapMinutes)
        assertEquals(extras, written.ordering?.extras)
        assertEquals(ChannelDraft.DEFAULT_SERIES_CAP, written.ordering?.caps?.series)
    }

    @Test
    fun useTheBuildersSettingsDropsThemAndWritesV2() {
        val curated = rules(ordering = Ordering(OrderingMode.Curated, repeatGapMinutes = 120, pairUpToMinutes = 30))

        val written = toRules(fromRules(curated).useBuildersSettings(), curated)

        assertEquals(OrderingMode.Shuffle, written.orderingMode)
        assertNull(written.ordering?.repeatGapMinutes)
        assertNull(written.ordering?.pairUpToMinutes)
        assertEquals(2, written.formatVersion)
    }

    @Test
    fun useTheBuildersSettingsKeepsTheStudioSourceAndStaysV3() {
        val curated = rules(sources = listOf(Source.Studio("Studio A")))

        val written = toRules(fromRules(curated).useBuildersSettings(), curated)

        assertEquals(OrderingMode.Shuffle, written.orderingMode)
        assertEquals(listOf<Source>(Source.Studio("Studio A")), written.sources)
        assertEquals(3, written.formatVersion)
    }

    @Test
    fun aPlainBuilderChannelStillWritesV2() {
        val draft =
            ChannelDraft(
                "Funny",
                listOf(DraftSource.Genre("Comedy", "Comedy")),
                emptyList(),
                DraftFilters(),
                order = PlayOrder.Shuffle,
                kids = false,
                seed = seed,
            )

        assertEquals(2, toRules(draft).formatVersion)
    }

    @Test
    fun tunablesOfAKeptNonCuratedOrderSurviveACopy() {
        val inOrder = rules(ordering = Ordering(OrderingMode.InOrder, repeatGapMinutes = 90))

        val draft = fromRules(inOrder)

        assertTrue(KeptItem.OtherSettings in assertNotNull(draft.kept).items)
        assertEquals(inOrder, toRules(draft, inOrder))
    }
}
