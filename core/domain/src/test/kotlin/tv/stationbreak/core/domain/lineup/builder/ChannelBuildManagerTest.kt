// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.LIBRARIES
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupCodec.Decoded.Loaded
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.fillingLibrary
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The port the channel builder works through (spec 22), in the lineup's lock. */
class ChannelBuildManagerTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val events = mutableListOf<LineupEvent>()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { events += it }, TestCatalog)

    private var drawn = 0
    private var ids = 0
    private val builder =
        ChannelBuildManager(
            service,
            { Seed(1_000uL + drawn++.toULong()) },
            { "id${++ids}" },
            { _, sources -> sources.associateWith { "Label ${it.key}" } },
        )

    private val laughs = LineupIds.starter("laughs")
    private val toons = LineupIds.starter("toons")

    private fun baseChannels(): List<LineupChannel> =
        listOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
        )

    private fun custom(
        name: String,
        number: Int,
        state: ChannelState = ChannelState.On,
        seed: ULong = 7uL,
    ) = channel(
        "custom:$name",
        null,
        state,
        ChannelSource.Custom(rules(name, Source.Genre("Comedy")).copy(seed = Seed(seed))),
    ).copy(numberOverride = number)

    private suspend fun seed(vararg extra: LineupChannel) {
        seedLineup(lineupOf(*(baseChannels() + extra).toTypedArray()))
    }

    private suspend fun seedLineup(lineup: Lineup) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
        store.calls.clear()
    }

    private fun stored(): Lineup {
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        return checkNotNull((LineupCodec.decode(bytes) as? Loaded)?.lineup)
    }

    private suspend fun freeIn(
        kids: Boolean,
        forChannel: ChannelId? = null,
    ): List<Int> = assertIs<Listed.Ok<List<Int>>>(builder.freeNumbers(ACCOUNT, kids, forChannel)).value

    private suspend fun firstFree(kids: Boolean): Int? = freeIn(kids).firstOrNull()

    private fun Lineup.channel(id: ChannelId) = channels.first { it.id == id }

    private fun Lineup.customRules(id: ChannelId) = (channel(id).source as ChannelSource.Custom).rules

    private fun named(
        draft: ChannelDraft,
        name: String = "Mine",
    ) = draft.copy(name = name, sources = listOf(DraftSource.Genre("Comedy", "Comedy")))

    private suspend fun built(
        name: String = "Mine",
        kids: Boolean = false,
    ): ChannelId {
        val result = builder.create(ACCOUNT, named(builder.blank(ACCOUNT, kids), name))
        return assertIs<BuildResult.Done>(result).id
    }

    @Test
    fun createAddsAnOnCustomChannelAtTheFirstFreeNumber() =
        runBlocking<Unit> {
            seed()

            val id = built()

            assertEquals(ChannelId("custom:id1"), id)
            val channel = stored().channel(id)
            assertEquals(600, channel.number)
            assertEquals(ChannelState.On, channel.state)
            assertIs<ChannelSource.Custom>(channel.source)
            assertEquals("Mine", stored().customRules(id).name)
        }

    @Test
    fun createForKidsTakesTheFirstFreeKidsNumber() =
        runBlocking<Unit> {
            seed()

            val id = built(kids = true)

            assertEquals(1, stored().channel(id).number)
        }

    @Test
    fun createWithAFullKidsBandIsRefused() =
        runBlocking<Unit> {
            val fillers = (1..99).map { custom("k$it", it, ChannelState.UserHidden) }
            seedLineup(lineupOf(baseChannels()[0], *fillers.toTypedArray()))
            val before = store.inner.peek(ACCOUNT)
            val draft = named(builder.blank(ACCOUNT, kids = true))
            assertNull(draft.number)

            val result = builder.create(ACCOUNT, draft)

            assertEquals(BuildResult.Refused(BuildRefusal.KidsBandFull), result)
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aNewChannelGetsAFreshSeedEachTime() =
        runBlocking<Unit> {
            seed()

            val first = builder.blank(ACCOUNT, kids = false)
            val second = builder.blank(ACCOUNT, kids = false)

            assertNotEquals(first.seed, second.seed)
        }

    @Test
    fun blankDrawsAFreshSeed() =
        runBlocking<Unit> {
            seed()

            val draft = builder.blank(ACCOUNT, kids = false)

            assertEquals(Seed(1_000uL), draft.seed)
            assertEquals(600, draft.number)
            assertEquals("", draft.name)
            assertTrue(!draft.numberChosen && !draft.kids && draft.sources.isEmpty())
        }

    @Test
    fun openingACopyDrawsAFreshSeedAndOpeningAnEditKeepsTheChannels() =
        runBlocking<Unit> {
            seed(custom("Mine", 610, seed = 77uL))
            val before = stored()

            val copy = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, laughs, copy = true))
            val edit = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, ChannelId("custom:Mine"), copy = false))

            assertEquals(laughs, copy.draft.origin, "a copy is of the built-in channel")
            assertEquals(ChannelId("custom:Mine"), edit.draft.origin, "an edit is of the channel itself")
            assertEquals(Seed(1_000uL), copy.draft.seed, "a copy draws a fresh seed")
            assertEquals(Seed(77uL), edit.draft.seed, "an edit keeps the channel's own")
            assertEquals(610, edit.draft.number)
            assertEquals(1, drawn, "only the copy drew")
            assertEquals(before, stored(), "nothing was written")
        }

    @Test
    fun createAirsTheSeedThePreviewUsed() =
        runBlocking<Unit> {
            seed()
            val draft = named(builder.blank(ACCOUNT, kids = false))
            val drawnBefore = drawn

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id

            assertEquals(draft.seed, stored().customRules(id).seed)
            assertEquals(drawnBefore, drawn, "create draws no seed")
        }

    @Test
    fun aPickedNumberThatIsTakenByNowIsNumberTaken() =
        runBlocking<Unit> {
            seed(custom("Holder", 610))

            val draft = named(builder.blank(ACCOUNT, false)).copy(number = 610, numberChosen = true)

            val result = builder.create(ACCOUNT, draft)

            val taken = BuildRefusal.NumberTaken(ChannelId("custom:Holder"))
            assertEquals(BuildResult.Refused(taken), result)
        }

    @Test
    fun anUnpickedNumberIsChosenInsideTheLock() =
        runBlocking<Unit> {
            seed()
            val preview = named(builder.blank(ACCOUNT, kids = false), "Slow")
            assertEquals(600, preview.number)
            built("Quick")

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, preview)).id

            assertEquals(601, stored().channel(id).number)
        }

    @Test
    fun saveKeepsTheSeed() =
        runBlocking<Unit> {
            seed(custom("Mine", 610, seed = 77uL))
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, ChannelId("custom:Mine"), copy = false)).draft

            val result = builder.save(ACCOUNT, ChannelId("custom:Mine"), draft.copy(name = "Renamed", seed = Seed(5uL)))

            assertEquals(BuildResult.Done(ChannelId("custom:Mine")), result)
            val rules = stored().customRules(ChannelId("custom:Mine"))
            assertEquals("Renamed", rules.name)
            assertEquals(Seed(77uL), rules.seed)
            assertEquals(610, stored().channel(ChannelId("custom:Mine")).number)
        }

    @Test
    fun reshuffleChangesOnlyTheSeed() =
        runBlocking<Unit> {
            seed(custom("Mine", 610, seed = 77uL))
            val before = stored().channel(ChannelId("custom:Mine"))

            val result = builder.reshuffle(ACCOUNT, ChannelId("custom:Mine"))

            assertEquals(BuildResult.Done(ChannelId("custom:Mine")), result)
            val after = stored().channel(ChannelId("custom:Mine"))
            val rules = (after.source as ChannelSource.Custom).rules
            assertEquals(Seed(1_000uL), rules.seed)
            assertEquals((before.source as ChannelSource.Custom).rules.copy(seed = Seed(1_000uL)), rules)
            assertEquals(before.number, after.number)
            assertEquals(before.state, after.state)
        }

    @Test
    fun deleteRemovesTheChannel() =
        runBlocking<Unit> {
            seed(custom("Mine", 610))

            assertEquals(BuildResult.Done(ChannelId("custom:Mine")), builder.delete(ACCOUNT, ChannelId("custom:Mine")))
            assertTrue(stored().channels.none { it.id.value == "custom:Mine" })
        }

    @Test
    fun deleteRefusesTheLastOn() =
        runBlocking<Unit> {
            val only = custom("Only", 620)
            seedLineup(lineupOf(only, baseChannels()[0].copy(state = ChannelState.Shelf)))

            assertEquals(BuildResult.Refused(BuildRefusal.LastOn), builder.delete(ACCOUNT, only.id))
            assertTrue(stored().channels.any { it.id == only.id })
        }

    @Test
    fun deleteAStarterIsNotCustom() =
        runBlocking<Unit> {
            seed()

            assertEquals(BuildResult.Refused(BuildRefusal.NotCustom), builder.delete(ACCOUNT, laughs))
            val none = builder.delete(ACCOUNT, ChannelId("custom:none"))
            assertEquals(BuildResult.Refused(BuildRefusal.UnknownChannel), none)
        }

    @Test
    fun openACopyOfABuiltInReturnsItsNameAndKeepsItsSettings() =
        runBlocking<Unit> {
            val inOrder = Ordering(OrderingMode.RandomInOrder)
            val ordered = rules("Westerns", Source.Genre("Western")).copy(ordering = inOrder)
            seed(starter("westerns", 220, ChannelState.On, ordered))
            val id = LineupIds.starter("westerns")

            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = true))

            assertEquals("Westerns", opened.builtInName)
            assertEquals("Westerns", opened.draft.name)
            assertEquals(600, opened.draft.number)
            assertTrue(!opened.draft.numberChosen)
            assertEquals(
                "Label Western",
                opened.draft.sources
                    .single()
                    .label,
            )
            val kept = assertNotNull(opened.draft.kept)
            assertTrue(KeptItem.Order(KeptOrder.RandomInOrder) in kept.items)

            val before = stored().channel(id)
            val copied = opened.draft.copy(name = "Westerns (copy)")
            val copyId = assertIs<BuildResult.Done>(builder.create(ACCOUNT, copied)).id

            val order = stored().customRules(copyId).orderingMode
            assertEquals(OrderingMode.RandomInOrder, order, "the kept order carried over")
            assertEquals(before, stored().channel(id), "the built-in is untouched")
        }

    @Test
    fun aFoldedStudioOfACopiedStarterIsShownWithTheHeldSpelling() =
        runBlocking<Unit> {
            val plain = rules("Westerns", Source.Genre("Western"))
            val withStudio = plain.copy(sources = plain.sources + Source.Studio("studio a"), formatVersion = 3)
            seed(starter("westerns", 220, ChannelState.On, withStudio))
            val shown =
                ChannelBuildManager(service, { Seed(1uL) }, { "x" }, { _, _ -> emptyMap() }) { _, names ->
                    names.associateWith { if (it == "studio a") "Studio A" else it }
                }

            val opened = assertIs<OpenResult.Opened>(shown.open(ACCOUNT, LineupIds.starter("westerns"), copy = true))

            assertTrue(KeptItem.StudioSource("Studio A") in assertNotNull(opened.draft.kept).items)
            val unknown = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, LineupIds.starter("westerns"), copy = true))
            assertTrue(KeptItem.StudioSource("studio a") in assertNotNull(unknown.draft.kept).items, "shown as stored")
        }

    // #560: the stored rules of a copy carry the copy's own name, never the original's, so an edit names nobody.
    @Test
    fun editingASavedCopyDoesNotNameTheCopyAsWhereItsSettingsWereKeptFrom() =
        runBlocking<Unit> {
            val inOrder = Ordering(OrderingMode.RandomInOrder)
            val ordered = rules("Westerns", Source.Genre("Western")).copy(ordering = inOrder)
            seed(starter("westerns", 220, ChannelState.On, ordered))
            val copy = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, LineupIds.starter("westerns"), copy = true))
            assertEquals("Westerns", assertNotNull(copy.draft.kept).fromName, "a copy names the built-in")
            val named = copy.draft.copy(name = "Westerns (copy)")
            val copyId = assertIs<BuildResult.Done>(builder.create(ACCOUNT, named)).id

            val edit = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, copyId, copy = false))

            val kept = assertNotNull(edit.draft.kept)
            assertTrue(KeptItem.Order(KeptOrder.RandomInOrder) in kept.items, "the kept settings are still listed")
            assertEquals(null, kept.fromName, "and the copy is not named as where they came from")
        }

    @Test
    fun aCopyOfAKidsStarterIsKidsUntilTurnedOff() =
        runBlocking<Unit> {
            seed()

            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, toons, copy = true))

            assertTrue(opened.draft.kids)
            assertEquals(1, opened.draft.number)
            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, opened.draft)).id
            assertEquals(1, stored().channel(id).number)
            assertTrue(KidStatus.isKidChannel(stored().channel(id)))

            val grownUp = opened.draft.copy(kids = false, number = firstFree(kids = false))
            val off = assertIs<BuildResult.Done>(builder.create(ACCOUNT, grownUp)).id
            assertEquals(600, stored().channel(off).number)
            assertFalse(KidStatus.isKidChannel(stored().channel(off)), "turned off, it is a grown-up channel")
        }

    @Test
    fun aCopyOfAKidsStarterWithAFullKidsBandStaysKidsAndIsRefused() =
        runBlocking<Unit> {
            val fillers = (1..99).filter { it != 10 }.map { custom("k$it", it, ChannelState.UserHidden) }
            seedLineup(lineupOf(baseChannels()[0], baseChannels()[2], *fillers.toTypedArray()))

            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, toons, copy = true))

            assertTrue(opened.draft.kids)
            assertNull(opened.draft.number)
            val result = builder.create(ACCOUNT, opened.draft)
            assertEquals(BuildResult.Refused(BuildRefusal.KidsBandFull), result)
        }

    @Test
    fun turningKidsOffAndSavingMovesToTheFirstFreeNumberFrom600() =
        runBlocking<Unit> {
            seed(custom("Kid", 5))
            val id = ChannelId("custom:Kid")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft
            assertTrue(draft.kids)

            val number = firstFree(kids = false)
            val result = builder.save(ACCOUNT, id, draft.copy(kids = false, number = number))

            assertEquals(600, number)
            assertEquals(BuildResult.Done(id), result)
            assertEquals(600, stored().channel(id).number)
        }

    @Test
    fun turningKidsOnMovesToTheFirstFreeKidsNumber() =
        runBlocking<Unit> {
            seed(custom("Mine", 610))
            val id = ChannelId("custom:Mine")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, draft.copy(kids = true)))

            assertEquals(1, stored().channel(id).number)
        }

    @Test
    fun turningKidsOnWithAFullBandIsKidsBandFull() =
        runBlocking<Unit> {
            val fillers = (1..99).filter { it != 10 }.map { custom("k$it", it, ChannelState.UserHidden) }
            seedLineup(lineupOf(baseChannels()[0], baseChannels()[2], custom("Mine", 610), *fillers.toTypedArray()))
            val id = ChannelId("custom:Mine")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            assertNull(firstFree(kids = true))
            val result = builder.save(ACCOUNT, id, draft.copy(kids = true))
            assertEquals(BuildResult.Refused(BuildRefusal.KidsBandFull), result)
            assertEquals(610, stored().channel(id).number)
        }

    @Test
    fun numberTakenNamesTheHolder() =
        runBlocking<Unit> {
            seed(custom("Mine", 610), custom("Other", 620))
            val id = ChannelId("custom:Mine")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            val result = builder.save(ACCOUNT, id, draft.copy(number = 620, numberChosen = true))

            val taken = BuildRefusal.NumberTaken(ChannelId("custom:Other"))
            assertEquals(BuildResult.Refused(taken), result)
        }

    @Test
    fun aPickedNumberOnTheWrongSideOfTheKidsBandIsADraftProblem() =
        runBlocking<Unit> {
            seed()
            val kids = named(builder.blank(ACCOUNT, kids = true)).copy(number = 700, numberChosen = true)
            val grownUp = named(builder.blank(ACCOUNT, kids = false)).copy(number = 5, numberChosen = true)

            assertEquals(
                BuildResult.Refused(BuildRefusal.Invalid(DraftProblem.KidsNumberOutsideBand)),
                builder.create(ACCOUNT, kids),
            )
            assertEquals(
                BuildResult.Refused(BuildRefusal.Invalid(DraftProblem.GrownUpNumberInKidsBand)),
                builder.create(ACCOUNT, grownUp),
            )
            assertTrue(LineupEvent.BuildFailed !in events)
        }

    @Test
    fun reshuffleThenSaveFromTheSameSheetKeepsTheNewSeed() =
        runBlocking<Unit> {
            seed(custom("Mine", 610, seed = 77uL))
            val id = ChannelId("custom:Mine")
            val sheet = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft
            builder.reshuffle(ACCOUNT, id)

            builder.save(ACCOUNT, id, sheet.copy(name = "Renamed"))

            assertEquals(Seed(1_000uL), stored().customRules(id).seed)
            assertEquals("Renamed", stored().customRules(id).name)
        }

    @Test
    fun anInvalidDraftIsRefusedWithItsProblem() =
        runBlocking<Unit> {
            seed()

            val result = builder.create(ACCOUNT, builder.blank(ACCOUNT, kids = false))

            assertEquals(BuildResult.Refused(BuildRefusal.Invalid(DraftProblem.NameEmpty)), result)
        }

    @Test
    fun aNewerAppsFileIsNotEditable() =
        runBlocking<Unit> {
            store.inner.save(ACCOUNT, """{"version":3,"whatever":"a newer app wrote"}""".encodeToByteArray())
            service.refresh(ACCOUNT, LIBRARIES)

            val result = builder.create(ACCOUNT, named(builder.blank(ACCOUNT, kids = false)))

            assertEquals(BuildResult.NotEditable, result)
        }

    @Test
    fun aFailedSaveIsSaveFailedAndNothingChanges() =
        runBlocking<Unit> {
            seed()
            service.loaded(ACCOUNT)
            val before = store.inner.peek(ACCOUNT)
            store.inner.failWrites = true

            val result = builder.create(ACCOUNT, named(builder.blank(ACCOUNT, kids = false)))

            assertEquals(BuildResult.SaveFailed, result)
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aStoreThatThrowsIsFailedAndReported() =
        runBlocking<Unit> {
            seed()
            val broken =
                LineupService(
                    object : tv.stationbreak.core.domain.LineupStore by store {
                        override suspend fun load(account: tv.stationbreak.core.domain.LibraryAccount) = error("boom")
                    },
                    library,
                    clock,
                    { 0 },
                    { events += it },
                    TestCatalog,
                )
            val failing = ChannelBuildManager(broken, { Seed(1uL) }, { "x" }, { _, _ -> emptyMap() })

            val result = failing.delete(ACCOUNT, laughs)

            assertEquals(BuildResult.Failed, result)
            assertTrue(LineupEvent.BuildFailed in events)
        }

    @Test
    fun anOpenThatCannotReadTheLineupIsFailedNotNotFound() =
        runBlocking<Unit> {
            val broken =
                LineupService(
                    object : tv.stationbreak.core.domain.LineupStore by store {
                        override suspend fun load(account: tv.stationbreak.core.domain.LibraryAccount) = error("boom")
                    },
                    library,
                    clock,
                    { 0 },
                    { events += it },
                    TestCatalog,
                )
            val failing = ChannelBuildManager(broken, { Seed(1uL) }, { "x" }, { _, _ -> emptyMap() })

            assertEquals(OpenResult.Failed, failing.open(ACCOUNT, laughs, copy = true))
            seed()
            val missing = builder.open(ACCOUNT, ChannelId("custom:none"), copy = false)
            assertEquals(OpenResult.NotFound, missing)
        }

    @Test
    fun aKeptDraftWithoutItsOriginIsRefused() =
        runBlocking<Unit> {
            seed()
            val settings = KeptSettings("Laughs", listOf(KeptItem.NoCaps))
            val kept = named(builder.blank(ACCOUNT, kids = false)).copy(kept = settings)

            assertEquals(BuildResult.Refused(BuildRefusal.UnknownChannel), builder.create(ACCOUNT, kept))
        }

    @Test
    fun freeNumbersAreEveryNumberTheNextCreateCouldTakeInOrder() =
        runBlocking<Unit> {
            seed(custom("Mine", 600), custom("Other", 603))

            val free = assertIs<Listed.Ok<List<Int>>>(builder.freeNumbers(ACCOUNT, kids = false)).value

            assertEquals(listOf(601, 602, 604), free.take(3))
            assertEquals(firstFree(kids = false), free.first())
        }

    @Test
    fun freeNumbersSkipARetiredNumberAndARenumberedChannelsDefault() =
        runBlocking<Unit> {
            val retiring =
                object : StarterCatalogView by TestCatalog {
                    override val retiredNumbers: Set<Int> = setOf(1, 600)
                }
            val retired =
                ChannelBuildManager(
                    LineupService(store, library, clock, { 0 }, { events += it }, retiring),
                    { Seed(1uL) },
                    { "x" },
                    { _, _ -> emptyMap() },
                )
            // Toons default to 10 and have been moved to 20; 601 is a custom channel's.
            val toons = starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation")))
            val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
            seedLineup(lineupOf(toons.copy(numberOverride = 20), laughs, custom("Mine", 601)))

            val kids = assertIs<Listed.Ok<List<Int>>>(retired.freeNumbers(ACCOUNT, kids = true)).value
            val grownUp = assertIs<Listed.Ok<List<Int>>>(retired.freeNumbers(ACCOUNT, kids = false)).value

            assertTrue(1 !in kids && 10 !in kids && 20 !in kids, "retired, default and override are skipped")
            assertEquals(2, kids.first())
            assertEquals(listOf(602, 603), grownUp.take(2))
        }

    @Test
    fun anEditsOwnNumberCountsAsFreeInItsBandOnly() =
        runBlocking<Unit> {
            seed(custom("Mine", 603), custom("Other", 605))
            val mine = ChannelId("custom:Mine")

            val plain = freeIn(kids = false)
            val own = freeIn(kids = false, forChannel = mine)
            val kids = freeIn(kids = true, forChannel = mine)
            val unknown = freeIn(kids = false, forChannel = ChannelId("nope"))

            assertTrue(603 !in plain)
            assertEquals((plain + 603).sorted(), own)
            assertEquals(listOf(600, 601, 602, 603, 604), own.take(5))
            assertTrue(603 !in kids)
            assertEquals(plain, unknown)
        }

    @Test
    fun freeNumbersAreUnavailableWithoutALineup() =
        runBlocking<Unit> {
            assertEquals(Listed.Unavailable, builder.freeNumbers(ACCOUNT, kids = false))
        }

    @Test
    fun aKidsChannelOpensWithACeilingTheKidsHoldKnows() =
        runBlocking<Unit> {
            val loose = rules("Kid", Source.Genre("Comedy"), maxRating = "R")
            seed(channel("custom:Kid", null, ChannelState.On, ChannelSource.Custom(loose)).copy(numberOverride = 5))

            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, ChannelId("custom:Kid"), copy = false))

            assertTrue(opened.draft.kids)
            assertEquals("PG", opened.draft.filters.maxRating)
        }

    @Test
    fun createAndSaveHoldAKidsDraftToACeilingTheKidsHoldKnows() =
        runBlocking<Unit> {
            seed()
            val kid = named(builder.blank(ACCOUNT, kids = true)).copy(filters = DraftFilters(maxRating = "R"))

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, kid)).id
            assertEquals("PG", stored().customRules(id).maxRating)

            val again = kid.copy(filters = DraftFilters(maxRating = "TV-14"))
            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, again))
            assertEquals("PG", stored().customRules(id).maxRating)
        }

    @Test
    fun reshuffleRefusesARotationAndAKeptOrderToo() =
        runBlocking<Unit> {
            for (mode in listOf(OrderingMode.Rotation, OrderingMode.Chronological, OrderingMode.RandomInOrder)) {
                val rules = rules("Mine", Source.Genre("Comedy")).copy(formatVersion = 2, ordering = Ordering(mode))
                val mine = channel("custom:Mine", null, ChannelState.On, ChannelSource.Custom(rules))
                seed(mine.copy(numberOverride = 610))

                val result = builder.reshuffle(ACCOUNT, ChannelId("custom:Mine"))

                assertEquals(BuildResult.Refused(BuildRefusal.NotShuffle), result, "$mode")
            }
        }

    @Test
    fun reshuffleRefusesAChannelThatIsNotAShuffle() =
        runBlocking<Unit> {
            val inOrder =
                rules("Mine", Source.Genre("Comedy")).copy(formatVersion = 2, ordering = Ordering(OrderingMode.InOrder))
            val mine = channel("custom:Mine", null, ChannelState.On, ChannelSource.Custom(inOrder))
            seed(mine.copy(numberOverride = 610))
            val before = store.inner.peek(ACCOUNT)

            val result = builder.reshuffle(ACCOUNT, ChannelId("custom:Mine"))

            assertEquals(BuildResult.Refused(BuildRefusal.NotShuffle), result)
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aNewIdIs128RandomBitsAsLowerCaseHex() {
        val id = newCustomId()

        assertTrue(Regex("[0-9a-f]{32}").matches(id), id)
        assertNotEquals(id, newCustomId())
    }
}
