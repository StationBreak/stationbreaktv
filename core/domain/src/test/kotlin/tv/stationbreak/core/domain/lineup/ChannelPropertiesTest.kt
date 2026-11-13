// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.MinuteWindow
import tv.stationbreak.core.domain.lineup.builder.OnlyKind
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.domain.lineup.builder.ShownOrder
import tv.stationbreak.core.domain.lineup.builder.StudioNames
import tv.stationbreak.core.engine.lineup.Anchor
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.Theme
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private typealias Labels = suspend (LibraryAccount, List<DraftSource>) -> Map<DraftSource, String>

/** A channel's properties (#631), over the five kinds of channel the lineup holds. */
class ChannelPropertiesTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val schedule = FakeSchedule()
    private var labelsFail = false

    private val noir =
        Recipe(
            "noir",
            "Noir",
            "Rain on the glass.",
            Band.Drama,
            230,
            optIn = false,
            sources = listOf(Source.Genre("Crime")),
            exclusions = emptyList(),
            maxRating = null,
            ordering = OrderingMode.Curated,
            collectionHints = emptyList(),
            theme =
                Theme(
                    anchors =
                        listOf(
                            Anchor(AnchorKind.Collection, NameMatcher.Exact("Noir Classics")),
                            Anchor(AnchorKind.Title, NameMatcher.Prefix("The Long")),
                            Anchor(AnchorKind.Studio, NameMatcher.Word("Studio X")),
                        ),
                    themeStudios = emptyList(),
                    format = null,
                    animeOnly = false,
                ),
        )

    private val catalog: StarterCatalogView =
        object : StarterCatalogView by TestCatalog {
            override val recipes: List<Recipe> = TestCatalog.recipes + noir
        }

    private val service = LineupService(store, library, clock, { 0 }, {}, catalog)

    private val reader =
        BuiltInChannelProperties(
            service,
            library,
            schedule,
            labels = { _, sources ->
                check(!labelsFail) { "the library is unreachable" }
                sources.associateWith { "Label ${it.key}" }
            },
            studioNames = { _, names -> names.associateWith { "Studio ${it.uppercase()}" } },
            holdings = ChannelHoldingsReader.None,
        )

    private fun timing(
        start: String? = "06:00",
        block: Int? = null,
    ) = Timing(block, null, null, null, null, null, BroadcastDay(0, start))

    private val noirRules =
        ChannelRules(
            3,
            "Noir",
            Seed(7uL),
            listOf(Source.Genre("Crime")),
            null,
            null,
            Ordering(OrderingMode.Curated),
            timing(),
            null,
        )

    private val mineRules =
        ChannelRules(
            3,
            "Mine",
            Seed(42uL),
            listOf(Source.Genre("Comedy"), Source.Collection(id(5)), Source.Studio("acme")),
            listOf(Source.Tag("late")),
            "PG",
            Ordering(OrderingMode.Rotation, OrderingCaps(2, 1)),
            timing(start = "04:00", block = 3),
            null,
            Filters(
                null,
                1990,
                1999,
                20,
                45,
                FilterKind.Episode,
                notGenres = listOf("Horror"),
                studios = listOf("acme"),
            ),
        )

    private suspend fun seed(vararg channels: tv.stationbreak.core.engine.lineup.LineupChannel) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*channels)))
        library.delegate.useAccount(ACCOUNT)
    }

    private val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
    private val themed = starter("noir", 230, ChannelState.On, noirRules)
    private val mine =
        channel("custom:mine", null, ChannelState.On, ChannelSource.Custom(mineRules)).copy(numberOverride = 600)
    private val kidsLeftovers = leftovers("kids-odds-and-ends", 99, "PG")
    private val grownUpLeftovers = leftovers("odds-and-ends", 199, null)
    private val unreadable = channel("starter:farm", 160, ChannelState.On, ChannelSource.Unusable("{}"))

    private suspend fun propertiesOf(id: ChannelId) = checkNotNull(reader.read(id, NOON)) { "no properties for $id" }

    @Test
    fun aStarterWithAThemeShowsItsPersonalityAnchorsAndProgrammedOrder() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)

            val props = propertiesOf(themed.id)

            assertEquals("Rain on the glass.", props.personality)
            assertEquals(
                listOf(
                    ThemeAnchor(ThemeAnchorKind.Collection, "Noir Classics"),
                    ThemeAnchor(ThemeAnchorKind.Title, "The Long"),
                    ThemeAnchor(ThemeAnchorKind.Studio, "Studio X"),
                ),
                props.anchors,
            )
            val description = checkNotNull(props.description)
            assertEquals(ShownOrder.Kept(KeptOrder.Curated), description.order)
            assertEquals(listOf("Label Crime"), description.sources.map { it.label })
            assertEquals(ChannelKind.Starter, props.summary.kind)
        }

    @Test
    fun aStarterWithoutAThemeHasAPersonalityAndNoAnchors() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)

            val props = propertiesOf(laughs.id)

            assertEquals(TestCatalog.recipes.first { it.key == "laughs" }.personality, props.personality)
            assertEquals(emptyList(), props.anchors)
            val description = checkNotNull(props.description)
            assertEquals(ShownOrder.Play(PlayOrder.Shuffle), description.order)
            assertEquals(listOf(DraftSource.Genre("Comedy", "Label Comedy")), description.sources)
            assertTrue(description.narrowing.isEmpty)
            assertEquals("06:00", props.timing?.dayStart)
            assertNull(props.timing?.blockLength)
        }

    @Test
    fun aCustomChannelShowsItsSourcesFiltersAndOrderInPlainTerms() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)

            val props = propertiesOf(mine.id)

            assertNull(props.personality, "a custom channel has no recipe")
            assertEquals(emptyList(), props.anchors)
            val description = checkNotNull(props.description)
            assertEquals(listOf("Label Comedy", "Label ${id(5).value}"), description.sources.map { it.label })
            assertEquals(listOf("Label late"), description.leaveOut.map { it.label })
            assertEquals("PG", description.ceiling)
            assertEquals(ShownOrder.Play(PlayOrder.Rotation), description.order)
            assertTrue(description.showsCaps)
            assertEquals(2, description.seriesCap)
            assertEquals(1, description.filmCap)
            val studios = description.kept.filterIsInstance<KeptItem.StudioSource>()
            assertEquals(listOf(KeptItem.StudioSource("Studio ACME")), studios)
            val narrowing = description.narrowing
            assertEquals(listOf("Horror"), narrowing.notGenres)
            assertEquals(1990, narrowing.yearFrom)
            assertEquals(1999, narrowing.yearTo)
            assertEquals(MinuteWindow(20, 45), narrowing.runtime)
            assertEquals(OnlyKind.Shows, narrowing.only)
            assertEquals(listOf("Studio ACME"), narrowing.studios, "a filter studio is spelled like the others")
            assertTrue(description.kept.none { it is KeptItem.BlockLength }, "the block length is the timing's")
            assertEquals(ChannelTiming("04:00", 3, scheduleCodeOf(Seed(42uL))), props.timing)
            assertEquals(ChannelKind.Custom, props.summary.kind)
        }

    @Test
    fun aLeftoversChannelHasNoRulesAndNamesItsCeiling() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)

            val kids = propertiesOf(kidsLeftovers.id)
            val grownUp = propertiesOf(grownUpLeftovers.id)

            for (props in listOf(kids, grownUp)) {
                assertNull(props.description)
                assertNull(props.timing)
                assertNull(props.personality)
                assertEquals(ChannelKind.Leftovers, props.summary.kind)
            }
            assertEquals("PG", kids.leftoversCeiling)
            assertNull(grownUp.leftoversCeiling)
        }

    @Test
    fun anUnusableChannelHasNoRulesToShow() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)

            val props = propertiesOf(unreadable.id)

            assertEquals(ChannelKind.Unusable, props.summary.kind)
            assertNull(props.description)
            assertNull(props.timing)
            assertNull(props.personality)
            assertEquals(emptyList(), props.anchors)
            assertNull(props.leftoversCeiling)
        }

    @Test
    fun theSummaryIsTheOneChannelManagementShows() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)

            val listed = service.channels(ACCOUNT).first().associateBy { it.id }

            for (channel in listOf(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)) {
                assertEquals(listed.getValue(channel.id), propertiesOf(channel.id).summary)
            }
        }

    @Test
    fun theHiddenReasonIsTheSchedulesAndOnlyForTheChannelItNames() =
        runBlocking<Unit> {
            seed(laughs, themed, mine, kidsLeftovers, grownUpLeftovers, unreadable)
            schedule.channel(mine.id, 600, emptyList(), hidden = HiddenReason.NoMatches)
            schedule.channel(laughs.id, 110, emptyList())

            assertEquals(HiddenReason.NoMatches, propertiesOf(mine.id).hidden)
            assertNull(propertiesOf(laughs.id).hidden)
            assertNull(propertiesOf(themed.id).hidden, "a channel the schedule does not list is not hidden")
        }

    @Test
    fun aSourceKeepsItsKeyWhenTheLookupFails() =
        runBlocking<Unit> {
            seed(laughs, mine)
            labelsFail = true

            val description = checkNotNull(propertiesOf(laughs.id).description)

            assertEquals(listOf("Comedy"), description.sources.map { it.label })
        }

    @Test
    fun anUnknownChannelAndASignedOutReaderGiveNothing() =
        runBlocking<Unit> {
            seed(laughs)
            assertNull(reader.read(ChannelId("starter:nope"), NOON))

            val noLabels: Labels = { _, _ -> emptyMap() }
            val noStudios: StudioNames = { _, _ -> emptyMap() }
            val signedOut = BuiltInChannelProperties(service, CountingLibrary(), schedule, noLabels, noStudios, ChannelHoldingsReader.None)
            assertNull(signedOut.read(laughs.id, NOON))
        }

    @Test
    fun theScheduleCodeIsEightUpperCaseHexDigitsOfTheSeedAndChangesWithIt() {
        val code = scheduleCodeOf(Seed(42uL))

        assertTrue(Regex("[0-9A-F]{4}-[0-9A-F]{4}").matches(code), code)
        assertEquals(code, scheduleCodeOf(Seed(42uL)))
        // Pinned, worked out apart from the code (FNV-1a 64 of "42", high 32 bits): two TVs must never disagree.
        assertEquals("07EE-7E07", code)
        assertNotEquals(code, scheduleCodeOf(Seed(43uL)))
    }
}
