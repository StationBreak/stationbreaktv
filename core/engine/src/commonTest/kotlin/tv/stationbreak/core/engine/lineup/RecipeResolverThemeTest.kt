// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeResolverThemeTest {
    private val collectionNames =
        CollectionNames(mapOf(idOf(5) to "Box Set 1 Collection", idOf(4) to "Box Set 2", idOf(6) to "Unrelated"))

    private val library =
        snapshot(
            items =
                listOf(
                    movie(idOf(40), studios = listOf("Studio B")).copy(name = "Film 1"),
                    movie(idOf(41), studios = listOf("Studio A")),
                ),
            series = listOf(series(idOf(20)).copy(name = "Show A")),
        )

    private fun themed(
        theme: Theme,
        sources: List<Source> = listOf(Source.Genre("Comedy")),
        filters: Filters? = null,
    ) = Recipe(
        key = "themed",
        name = "Themed",
        personality = "Synthetic.",
        band = Band.General,
        number = 120,
        optIn = false,
        sources = sources,
        exclusions = emptyList(),
        maxRating = null,
        ordering = OrderingMode.Curated,
        collectionHints = emptyList(),
        filters = filters,
        theme = theme,
    )

    private fun resolve(
        recipe: Recipe,
        names: CollectionNames = collectionNames,
        snap: tv.stationbreak.core.model.RuleSnapshot = library,
    ) = RecipeResolver.resolve(recipe, SERVER, 0, names, LibraryNames(snap, names))

    @Test
    fun anchorsAndThemeStudiosBecomeSources() {
        val theme =
            Theme(
                anchors =
                    listOf(
                        Anchor(AnchorKind.Collection, NameMatcher.Prefix("box set")),
                        Anchor(AnchorKind.Title, NameMatcher.Exact("Show A")),
                        Anchor(AnchorKind.Studio, NameMatcher.Exact("Studio A")),
                        Anchor(AnchorKind.Title, NameMatcher.Exact("show a")),
                    ),
                themeStudios = listOf(NameMatcher.Contains("studio b")),
                format = null,
                animeOnly = false,
            )
        val rules = resolve(themed(theme))
        assertEquals(
            listOf(
                Source.Genre("Comedy"),
                Source.Collection(idOf(4)),
                Source.Collection(idOf(5)),
                Source.Series(idOf(20)),
                Source.Studio("studio a"),
                Source.Studio("studio b"),
            ),
            rules.sources,
        )
    }

    @Test
    fun householdCollectionsStayInTheRules() {
        val theme =
            Theme(listOf(Anchor(AnchorKind.Collection, NameMatcher.Exact("box set 2"))), emptyList(), null, false)
        assertEquals(listOf(Source.Genre("Comedy"), Source.Collection(idOf(4))), resolve(themed(theme)).sources)
    }

    @Test
    fun aThemedRecipeWritesFormatThreeAndCurated() {
        val filters = Filters(genres = listOf("Comedy"), null, null, null, null, null, tags = listOf("tag a"))
        val rules = resolve(themed(Theme(emptyList(), emptyList(), FormatPromise.Films, false), filters = filters))
        assertEquals(3, rules.formatVersion)
        assertEquals(OrderingMode.Curated, rules.orderingMode)
        assertEquals(filters, rules.filters)
    }

    // ADR-0026: a starter with a studio source is written at 3 while the gate is off, and at 4 once it has passed; one
    // without a studio feature stays at the lowest version it needs either way.
    @Test
    fun aStudioStarterIsWrittenAt4OnlyOnceTheGateHasPassed() {
        val theme = Theme(listOf(Anchor(AnchorKind.Studio, NameMatcher.Exact("Studio A"))), emptyList(), null, false)
        val recipe = themed(theme)
        val names = LibraryNames(library, collectionNames)
        val before = RecipeResolver.resolve(recipe, SERVER, 0, collectionNames, names, gatePassed = false)
        assertTrue(before.sources.any { it is Source.Studio })
        assertEquals(3, before.formatVersion)
        val after = RecipeResolver.resolve(recipe, SERVER, 0, collectionNames, names, gatePassed = true)
        assertEquals(4, after.formatVersion)
        assertEquals(after, resolve(recipe), "the app's default: the gate has passed")
        assertEquals(before.copy(formatVersion = 4), after, "only the version differs")
        val plain = themed(Theme(emptyList(), emptyList(), FormatPromise.Films, false))
        val plainAfter = RecipeResolver.resolve(plain, SERVER, 0, collectionNames, names, gatePassed = true)
        assertEquals(3, plainAfter.formatVersion)
    }

    // What the rules take in the lineup file: the rules text as a JSON string.
    private fun fileBytes(rules: ChannelRules): Int {
        val text = JsonPrimitive(RulesWriter.write(rules)).toString()
        return text.encodeToByteArray().size
    }

    private val plainTheme = Theme(emptyList(), emptyList(), null, false)
    private val comedy = listOf<Source>(Source.Genre("Comedy"))

    private val manyCollections = (1..300).associate { idOf(0x1000 + it) to "Box Set  Collection" }
    private val boxSets = Anchor(AnchorKind.Collection, NameMatcher.Prefix("box set"))

    @Test
    fun aRecipeThatResolvesToMoreSourcesThanTheBudgetKeepsTheFirstOnesThatFit() {
        val theme = Theme(listOf(boxSets), emptyList(), null, false)
        val rules = resolve(themed(theme), CollectionNames(manyCollections))
        val kept = rules.sources.size
        println("collection-ID sources that fit the budget: $kept")
        assertTrue(kept in 2 until RecipeResolver.MAX_STARTER_SOURCES, "the byte budget binds before the count: $kept")
        assertEquals(Source.Genre("Comedy"), rules.sources.first())
        assertEquals(Source.Collection(idOf(0x1000 + 1)), rules.sources[1])
        assertEquals(Source.Collection(idOf(0x1000 + kept - 1)), rules.sources.last())
        assertTrue(fileBytes(rules) <= RecipeResolver.STARTER_RULES_BUDGET_BYTES)
        // One more would not fit: the stop is the budget's, not an early one.
        val one = rules.copy(sources = rules.sources + Source.Collection(idOf(0x1000 + kept)))
        assertTrue(fileBytes(one) > RecipeResolver.STARTER_RULES_BUDGET_BYTES)
    }

    @Test
    fun thirtyCharacterStudioNamesFitTheBudgetAndAreCutBeforeAnchors() {
        val studios = (1..100).map { Source.Studio("Studio ${it.toString().padStart(3, '0')} ".padEnd(30, 'x')) }
        val theme = Theme(listOf(boxSets), emptyList(), null, false)
        val rules = resolve(themed(theme, sources = comedy + studios), CollectionNames(manyCollections))
        println("30-character studio sources that fit: ${rules.sources.count { it is Source.Studio }}")
        assertTrue(fileBytes(rules) <= RecipeResolver.STARTER_RULES_BUDGET_BYTES)
        assertTrue(rules.sources.size <= RecipeResolver.MAX_STARTER_SOURCES)
        assertEquals(Source.Genre("Comedy"), rules.sources.first())
        // The recipe's own sources come first and use the budget; the collection anchors after them are cut.
        assertEquals(studios.take(rules.sources.size - 1), rules.sources.drop(1))
        val alone = resolve(themed(plainTheme, sources = comedy + studios))
        println("30-character studio sources alone: ${alone.sources.size - 1}")
        assertEquals(rules.sources, alone.sources)
    }

    @Test
    fun longNonAsciiStudioNamesStillFitTheBudget() {
        val long = (1..40).map { Source.Studio("é".repeat(200) + it) }
        val rules = resolve(themed(plainTheme, sources = comedy + long))
        assertTrue(rules.sources.size >= 1)
        assertTrue(fileBytes(rules) <= RecipeResolver.STARTER_RULES_BUDGET_BYTES, "${fileBytes(rules)} bytes")
    }

    // Each source is measured on its own: names the file escapes (quotes, backslashes) and non-ASCII still add up to
    // the exact size of the whole, so the cut is at the budget and not before it.
    @Test
    fun escapedNamesAreMeasuredSourceBySource() {
        val odd = (1..80).map { Source.Studio("Studio \"$it\" \\ é".padEnd(40, 'q')) }
        val rules = resolve(themed(plainTheme, sources = comedy + odd))
        val kept = rules.sources.size
        assertTrue(kept in 2 until odd.size, "kept $kept")
        assertTrue(fileBytes(rules) <= RecipeResolver.STARTER_RULES_BUDGET_BYTES, "${fileBytes(rules)} bytes")
        val one = rules.copy(sources = rules.sources + odd[kept - 1])
        assertTrue(fileBytes(one) > RecipeResolver.STARTER_RULES_BUDGET_BYTES, "${fileBytes(one)} bytes")
    }

    @Test
    fun theSourceCountStillBoundsTinySources() {
        val tiny = (1..100).map { Source.Tag("t$it") }
        val rules = resolve(themed(Theme(emptyList(), emptyList(), null, false), sources = tiny))
        assertTrue(rules.sources.size <= RecipeResolver.MAX_STARTER_SOURCES)
        assertTrue(fileBytes(rules) <= RecipeResolver.STARTER_RULES_BUDGET_BYTES)
    }

    @Test
    fun anUnthemedRecipeResolvesAsBefore() {
        val plain = recipeOf("plain", 110, hints = listOf("box set 2"))
        val rules = resolve(plain)
        assertEquals(1, rules.formatVersion)
        assertEquals(plain.sources + Source.Collection(idOf(4)), rules.sources)
        assertNull(rules.ordering)
        assertNull(rules.filters)
    }

    @Test
    fun rulesStayPinnedWhileNamesAreUnchanged() {
        val theme =
            Theme(
                listOf(
                    Anchor(AnchorKind.Collection, NameMatcher.Prefix("box set")),
                    Anchor(AnchorKind.Title, NameMatcher.Contains("a")),
                ),
                listOf(NameMatcher.Contains("studio")),
                null,
                false,
            )
        val recipe = themed(theme)
        val flipped = collectionNames.ids.reversed().associateWith { checkNotNull(collectionNames[it]) }
        val reversedNames = CollectionNames(flipped)
        val reversed = snapshot(items = library.items.reversed(), series = library.series.values.reversed())
        assertEquals(resolve(recipe), resolve(recipe, reversedNames, reversed))
    }

    @Test
    fun aThemedRecipeMustBeCuratedAndHaveNoHints() {
        val theme = Theme(emptyList(), emptyList(), null, false)
        assertFailsWith<IllegalArgumentException> { themed(theme).copy(ordering = OrderingMode.Shuffle) }
        assertFailsWith<IllegalArgumentException> { themed(theme).copy(collectionHints = listOf("x")) }
    }

    @Test
    fun themeGenresAndTagsComeFromSourcesThenFilters() {
        val filters =
            Filters(genres = listOf("Drama", "Comedy"), null, null, null, null, null, tags = listOf("t2", "t1"))
        val recipe =
            themed(
                Theme(emptyList(), emptyList(), null, false),
                sources =
                    listOf(
                        Source.Genre("Comedy"),
                        Source.Tag("t1"),
                        Source.Studio("Studio A"),
                        Source.YearRange(1980, 1989),
                    ),
                filters = filters,
            )
        assertEquals(listOf("Comedy", "Drama"), recipe.themeGenres)
        assertEquals(listOf("t1", "t2"), recipe.themeTags)
        assertEquals(true, recipe.guestOnly)
        assertEquals(true, recipe.curated)
    }

    @Test
    fun anotherSpellingOfAStudioLeavesTheRulesEqual() {
        val theme = Theme(emptyList(), listOf(NameMatcher.Contains("studio")), null, false)
        val recipe = themed(theme)
        val extra = movie(idOf(42), studios = listOf("STUDIO a"))
        val more = snapshot(items = library.items + extra, series = library.series.values.toList())
        assertEquals(resolve(recipe), resolve(recipe, snap = more))
    }

    @Test
    fun studioSourcesAreUniqueByFoldedName() {
        val theme = Theme(listOf(Anchor(AnchorKind.Studio, NameMatcher.Exact("studio a"))), emptyList(), null, false)
        val rules = resolve(themed(theme, sources = listOf(Source.Studio("Studio A"))))
        assertEquals(listOf<Source>(Source.Studio("Studio A")), rules.sources)
    }

    @Test
    fun anchorsSurviveTheCapWhenThemeStudiosOverflowIt() {
        val studios = (1..300).map { "Studio ${it.toString().padStart(3, '0')}" }
        val big = snapshot(items = studios.mapIndexed { i, s -> movie(idOf(0x100 + i), studios = listOf(s)) })
        val anchor = Anchor(AnchorKind.Collection, NameMatcher.Exact("box set 2"))
        val theme = Theme(listOf(anchor), listOf(NameMatcher.Prefix("studio ")), null, false)
        val rules = resolve(themed(theme), snap = big)
        assertTrue(rules.sources.size <= RecipeResolver.MAX_STARTER_SOURCES)
        assertTrue(fileBytes(rules) <= RecipeResolver.STARTER_RULES_BUDGET_BYTES)
        assertTrue(rules.sources.drop(2).all { it is Source.Studio }, "the anchor first, theme studios after")
        assertEquals(Source.Collection(idOf(4)), rules.sources[1])
    }
}
