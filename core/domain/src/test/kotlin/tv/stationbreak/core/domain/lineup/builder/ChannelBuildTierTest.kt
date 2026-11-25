// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.kids.KidsLockState
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupCodec.Decoded.Loaded
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.fillingLibrary
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelFloors
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** #1152: a custom channel's tier is its number band; the builder creates, opens, moves and refuses by it. */
class ChannelBuildTierTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    private var drawn = 0
    private var ids = 0
    private val builder =
        ChannelBuildManager(
            service,
            FakeProEntitlement(),
            { Seed(1_000uL + drawn++.toULong()) },
            { "id${++ids}" },
            { _, sources -> sources.associateWith { "Label ${it.key}" } },
            { _, _ -> emptyMap() },
        )

    private fun baseChannels(): List<LineupChannel> =
        listOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
        )

    private fun custom(
        name: String,
        number: Int,
        state: ChannelState = ChannelState.On,
    ) = channel(
        "custom:$name",
        null,
        state,
        ChannelSource.Custom(rules(name, Source.Genre("Comedy")).copy(seed = Seed(7uL))),
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
        tier: FamilyTier?,
        forChannel: ChannelId? = null,
    ): List<Int> = assertIs<Listed.Ok<List<Int>>>(builder.freeNumbers(ACCOUNT, tier, forChannel)).value

    private suspend fun firstFree(tier: FamilyTier?): Int? = freeIn(tier).firstOrNull()

    private fun Lineup.channel(id: ChannelId) = channels.first { it.id == id }

    private fun Lineup.customRules(id: ChannelId) = (channel(id).source as ChannelSource.Custom).rules

    private fun named(
        draft: ChannelDraft,
        name: String = "Mine",
    ) = draft.copy(name = name, sources = listOf(DraftSource.Genre("Comedy", "Comedy")))

    private fun Lineup.floorOf(id: ChannelId) = ChannelFloors.of(channel(id), TestCatalog)

    private fun shown(
        cap: KidsCap,
        number: Int,
        floor: Int,
    ) = KidsLockState(enabled = true, cap = cap, pinSet = false).allows(number, floor)

    @Test
    fun aBlankKidsOrTeensDraftTakesTheFirstFreeNumberOfItsBandAndAHeldCeiling() =
        runBlocking<Unit> {
            seed(custom("A", 34), custom("B", 67))

            val kids = builder.blank(ACCOUNT, FamilyTier.Kids)
            val teens = builder.blank(ACCOUNT, FamilyTier.Teens)

            assertEquals(FamilyTier.Kids, kids.tier)
            assertEquals(35, kids.number)
            assertEquals("PG", kids.filters.maxRating)
            assertEquals(FamilyTier.Teens, teens.tier)
            assertEquals(68, teens.number)
            assertEquals("TV-14", teens.filters.maxRating)
        }

    @Test
    fun freeNumbersAreTheBandOfTheTier() =
        runBlocking<Unit> {
            seed(custom("A", 34), custom("B", 67))

            assertEquals((35..66).toList(), freeIn(FamilyTier.Kids))
            assertEquals((68..99).toList(), freeIn(FamilyTier.Teens))
            assertEquals(listOf(34) + (35..66).toList(), freeIn(FamilyTier.Kids, ChannelId("custom:A")))
            assertEquals(1, freeIn(FamilyTier.Tots).first())
        }

    @Test
    fun aKidsOrTeensChannelIsCreatedInItsBandAtItsCeilingAndIsNotAKidChannel() =
        runBlocking<Unit> {
            seed()
            val cases = listOf(Triple(FamilyTier.Kids, 34, "PG"), Triple(FamilyTier.Teens, 67, "TV-14"))
            for ((tier, first, ceiling) in cases) {
                val draft = named(builder.blank(ACCOUNT, tier), "In ${tier.name}")

                val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id

                assertEquals(first, stored().channel(id).number, "$tier")
                assertEquals(ceiling, stored().customRules(id).maxRating, "$tier")
                assertFalse(KidStatus.isKidChannel(stored().channel(id)), "$tier is held by its ceiling")
                assertEquals(PoolPolicy.Custom, KidStatus.basePolicyOf(stored().channel(id)), "$tier")
            }
        }

    @Test
    fun aTeensChannelKeepsAPgCeilingTheViewerChose() =
        runBlocking<Unit> {
            seed()
            val blank = named(builder.blank(ACCOUNT, FamilyTier.Teens))
            val draft = blank.copy(filters = DraftFilters(maxRating = "PG-13"))

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id

            assertEquals("PG-13", stored().customRules(id).maxRating)
        }

    @Test
    fun eachBandRunsOutOnItsOwnAndSaysWhichTier() =
        runBlocking<Unit> {
            val family = (34..66).map { custom("f$it", it, ChannelState.UserHidden) }
            val teen = (67..99).map { custom("t$it", it, ChannelState.UserHidden) }
            seedLineup(lineupOf(*(baseChannels() + family + teen).toTypedArray()))

            val kids = named(builder.blank(ACCOUNT, FamilyTier.Kids))
            val teens = named(builder.blank(ACCOUNT, FamilyTier.Teens))

            assertNull(kids.number)
            assertEquals(BuildResult.Refused(BuildRefusal.TierBandFull(FamilyTier.Kids)), builder.create(ACCOUNT, kids))
            val teensFull = BuildRefusal.TierBandFull(FamilyTier.Teens)
            assertEquals(BuildResult.Refused(teensFull), builder.create(ACCOUNT, teens))
            // The Tots band is the one with room.
            assertIs<BuildResult.Done>(builder.create(ACCOUNT, named(builder.blank(ACCOUNT, FamilyTier.Tots))))
        }

    @Test
    fun anEditedChannelOpensAtTheTierOfItsNumber() =
        runBlocking<Unit> {
            seed(custom("Low", 5), custom("Mid", 40), custom("High", 80), custom("Open", 700))

            fun tierOf(name: String): FamilyTier? {
                val opened = runBlocking { builder.open(ACCOUNT, ChannelId("custom:$name"), copy = false) }
                return assertIs<OpenResult.Opened>(opened).draft.tier
            }

            assertEquals(FamilyTier.Tots, tierOf("Low"))
            assertEquals(FamilyTier.Kids, tierOf("Mid"))
            assertEquals(FamilyTier.Teens, tierOf("High"))
            assertNull(tierOf("Open"))
        }

    @Test
    fun changingTheTierMovesTheChannelToTheFirstFreeNumberOfTheNewBand() =
        runBlocking<Unit> {
            seed(custom("Mine", 40), custom("Taken", 67))
            val id = ChannelId("custom:Mine")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            val toTeens = draft.withTier(FamilyTier.Teens, firstFree(FamilyTier.Teens))
            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, toTeens))

            assertEquals(68, stored().channel(id).number)
            // The ceiling it had still holds it (a Teens channel may be PG); it is not loosened by the move.
            assertEquals("PG", stored().customRules(id).maxRating)

            val toTots = toTeens.withTier(FamilyTier.Tots, firstFree(FamilyTier.Tots))
            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, toTots))
            assertEquals(1, stored().channel(id).number)
            assertTrue(KidStatus.isKidChannel(stored().channel(id)))

            val off = toTots.withTier(null, firstFree(null))
            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, off))
            assertEquals(600, stored().channel(id).number)
        }

    @Test
    fun savingAKidsChannelWithTheNumberLeftAloneKeepsIt() =
        runBlocking<Unit> {
            seed(custom("Mine", 50))
            val id = ChannelId("custom:Mine")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, draft.copy(name = "Renamed")))

            assertEquals(50, stored().channel(id).number)
        }

    @Test
    fun aKidsOrTeensChannelCanBeFreedOnlyByTurningFamilyOff() =
        runBlocking<Unit> {
            seed(custom("Mine", 50))
            val id = ChannelId("custom:Mine")
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            // The number the viewer did not pick is the first free grown-up one, never a young band one it holds.
            assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, draft.copy(tier = null, number = null)))

            assertEquals(600, stored().channel(id).number)
        }

    @Test
    fun aKidsOrTeensChannelIsHiddenUnderAFamilyModeBelowItsTier() =
        runBlocking<Unit> {
            seed()
            val ids =
                listOf(FamilyTier.Tots, FamilyTier.Kids, FamilyTier.Teens).associateWith { tier ->
                    val draft = named(builder.blank(ACCOUNT, tier), tier.name)
                    assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id
                }

            fun shownUnder(cap: KidsCap) =
                ids
                    .filter { (_, id) -> shown(cap, stored().channel(id).number!!, stored().floorOf(id)) }
                    .keys

            assertEquals(setOf(FamilyTier.Tots), shownUnder(KidsCap.Kids))
            assertEquals(setOf(FamilyTier.Tots, FamilyTier.Kids), shownUnder(KidsCap.Family))
            assertEquals(setOf(FamilyTier.Tots, FamilyTier.Kids, FamilyTier.Teens), shownUnder(KidsCap.Teen))
            // Family Mode off: every channel.
            val off = KidsLockState(enabled = false, cap = KidsCap.Kids, pinSet = false)
            assertTrue(ids.values.all { off.allows(stored().channel(it).number, stored().floorOf(it)) })
        }

    // A retired kid recipe's channel (#1044): Custom, keeps its starter ID, so it is a kid channel at any number.
    private fun migrated(number: Int) =
        channel(
            "starter:lights-out",
            number,
            ChannelState.On,
            ChannelSource.Custom(rules("Lights Out", Source.Genre("Animation"), maxRating = "PG")),
        )

    private suspend fun renameKeepsTheMovedNumber(
        tier: FamilyTier,
        first: Int,
    ) {
        val id = ChannelId("starter:lights-out")
        seed(migrated(150))
        val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft
        assertEquals(FamilyTier.Tots, opened.tier)
        assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, opened.withTier(tier, firstFree(tier))))
        assertEquals(first, stored().channel(id).number)

        val reopened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft
        assertEquals(tier, reopened.tier)
        assertEquals(BuildResult.Done(id), builder.save(ACCOUNT, id, reopened.copy(name = "Renamed")))

        assertEquals(first, stored().channel(id).number)
        assertEquals(tier, assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft.tier)
    }

    @Test
    fun aKidChannelByIdMovedToKidsKeepsItsNumber() {
        runBlocking { renameKeepsTheMovedNumber(FamilyTier.Kids, 34) }
    }

    @Test
    fun aKidChannelByIdMovedToTeensKeepsItsNumber() {
        runBlocking { renameKeepsTheMovedNumber(FamilyTier.Teens, 67) }
    }

    @Test
    fun aMigratedKidChannelOpensAtItsBandAndAsTotsOutsideTheYoungBands() {
        for ((number, tier) in listOf(150 to FamilyTier.Tots, 44 to FamilyTier.Kids, 80 to FamilyTier.Teens)) {
            val fresh = ChannelBuildTierTest()
            val opened = runBlocking { fresh.openMigrated(number) }
            assertEquals(tier, opened.draft.tier, "at $number")
        }
    }

    private suspend fun openMigrated(number: Int): OpenResult.Opened {
        seed(migrated(number))
        return assertIs<OpenResult.Opened>(builder.open(ACCOUNT, ChannelId("starter:lights-out"), copy = false))
    }
}
