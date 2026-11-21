// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.FreeTier
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupCodec.Decoded.Loaded
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.SharedSnapshots
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.channel
import tv.stationbreak.core.domain.lineup.fillingLibrary
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.starter
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.AdultMark
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A copy of After Hours is always adult content, Pro only, and no draft can make it otherwise (#984). */
class AfterHoursCopyTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { }, TestCatalog)
    private val pro = FakeProEntitlement(initial = true)
    private var ids = 0
    private val builder =
        ChannelBuildManager(
            service,
            pro,
            { Seed(1uL) },
            { "id${++ids}" },
            { _, sources -> sources.associateWith { it.key } },
        ) { _, _ -> emptyMap() }

    private val afterHours = LineupIds.starter("after-hours")
    private val laughs = LineupIds.starter("laughs")

    private suspend fun seedWith(customs: Int = 0) {
        val channels =
            listOf(
                starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                starter("after-hours", 590, ChannelState.Shelf, rules("After Hours", Source.Tag("late"))),
            ) +
                (1..customs).map {
                    channel(
                        "custom:c$it",
                        null,
                        ChannelState.On,
                        ChannelSource.Custom(rules("C$it", Source.Genre("Comedy"))),
                    ).copy(numberOverride = 600 + it)
                }
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(*channels.toTypedArray())))
    }

    private fun stored(): Lineup {
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        return checkNotNull((LineupCodec.decode(bytes) as? Loaded)?.lineup)
    }

    private fun Lineup.customRules(id: ChannelId): ChannelRules {
        val channel = channels.first { it.id == id }
        return (channel.source as ChannelSource.Custom).rules
    }

    private suspend fun copyOf(id: ChannelId): ChannelDraft {
        val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = true))
        return opened.draft
    }

    private suspend fun savedCopy(): ChannelId =
        assertIs<BuildResult.Done>(builder.create(ACCOUNT, copyOf(afterHours).copy(name = "Mine"))).id

    @Test
    fun aCopyOfAfterHoursOpensAsAnAdultDraft() =
        runBlocking<Unit> {
            seedWith()

            assertTrue(copyOf(afterHours).adult)
        }

    @Test
    fun aCopyOfAnotherBuiltInIsNotAdult() =
        runBlocking<Unit> {
            seedWith()

            assertFalse(copyOf(laughs).adult)
            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, copyOf(laughs))).id
            assertFalse(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun theSavedCopyIsAdult() =
        runBlocking<Unit> {
            seedWith()

            val id = savedCopy()

            assertTrue(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun aDraftThatSaysNotAdultStillMakesAnAdultCopy() =
        runBlocking<Unit> {
            seedWith()
            val draft = copyOf(afterHours).copy(adult = false)

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id

            assertTrue(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun aCopyDroppedOfItsKeptSettingsIsStillAdult() =
        runBlocking<Unit> {
            seedWith()
            val draft = copyOf(afterHours).copy(kept = null)

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id

            assertTrue(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun editingASavedCopyKeepsItAdult() =
        runBlocking<Unit> {
            seedWith()
            val id = savedCopy()
            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft
            assertTrue(opened.adult)

            val edited = opened.copy(name = "Renamed", adult = false, kept = null, origin = null)
            assertIs<BuildResult.Done>(builder.save(ACCOUNT, id, edited))

            val rules = stored().customRules(id)
            assertEquals("Renamed", rules.name)
            assertTrue(AdultMark.isMarked(rules))
        }

    @Test
    fun theMarkDoesNotShowAsAKeptSetting() =
        runBlocking<Unit> {
            seedWith()
            val id = savedCopy()

            val opened = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, id, copy = false)).draft

            assertFalse(
                opened.kept
                    ?.items
                    .orEmpty()
                    .contains(KeptItem.OtherSettings),
            )
        }

    @Test
    fun aFreeViewerCannotOpenACopyOfAfterHours() =
        runBlocking<Unit> {
            seedWith()
            pro.set(false)

            assertEquals(OpenResult.NeedsPro, builder.open(ACCOUNT, afterHours, copy = true))
        }

    @Test
    fun aFreeViewerCannotCreateACopyOfAfterHoursEvenWithADraftInHand() =
        runBlocking<Unit> {
            seedWith()
            val draft = copyOf(afterHours)
            pro.set(false)
            val before = store.inner.peek(ACCOUNT)

            assertEquals(BuildResult.Refused(BuildRefusal.NeedsPro), builder.create(ACCOUNT, draft))
            assertContentEquals(before, store.inner.peek(ACCOUNT))
        }

    @Test
    fun aFreeViewerStillCopiesAnotherBuiltIn() =
        runBlocking<Unit> {
            seedWith()
            pro.set(false)

            assertIs<OpenResult.Opened>(builder.open(ACCOUNT, laughs, copy = true))
        }

    @Test
    fun proMayCopyAfterHoursAndMakeMoreThanTheFreeCap() =
        runBlocking<Unit> {
            seedWith(customs = FreeTier.CUSTOM_CHANNELS)

            val id = savedCopy()

            assertTrue(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun theFreeCapStillAppliesToACopyOfAfterHoursOnceProEnds() =
        runBlocking<Unit> {
            seedWith(customs = FreeTier.CUSTOM_CHANNELS)
            val draft = copyOf(afterHours)
            pro.set(false)

            // Pro is the gate first: a free viewer gets no copy at all, whatever the count.
            assertEquals(BuildResult.Refused(BuildRefusal.NeedsPro), builder.create(ACCOUNT, draft))
        }

    private fun markedCustom(): LineupChannel =
        channel(
            "custom:adult",
            null,
            ChannelState.On,
            ChannelSource.Custom(AdultMark.marked(rules("Night", Source.Tag("late")))),
        ).copy(numberOverride = 601)

    private suspend fun seedMarkedCustom() {
        val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs, markedCustom())))
    }

    private val adultCustom = ChannelId("custom:adult")

    @Test
    fun aCopyOfAMarkedCustomChannelIsMarked() =
        runBlocking<Unit> {
            seedMarkedCustom()
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, adultCustom, copy = false)).draft

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft.copy(adult = false))).id

            assertTrue(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun aFreeCreateFromAMarkedCustomOriginKeepsTheMark() =
        runBlocking<Unit> {
            seedMarkedCustom()
            val draft = assertIs<OpenResult.Opened>(builder.open(ACCOUNT, adultCustom, copy = false)).draft
            pro.set(false)

            val id = assertIs<BuildResult.Done>(builder.create(ACCOUNT, draft)).id

            assertTrue(AdultMark.isMarked(stored().customRules(id)))
        }

    @Test
    fun theMarkSurvivesTheLineupCodec() =
        runBlocking<Unit> {
            seedMarkedCustom()

            assertTrue(AdultMark.isMarked(stored().customRules(adultCustom)))
            val again = LineupCodec.encode(stored())
            val decoded = checkNotNull((LineupCodec.decode(again) as? Loaded)?.lineup)
            assertTrue(AdultMark.isMarked(decoded.customRules(adultCustom)))
        }

    @Test
    fun anOpenedCopyOfAfterHoursHasNoOtherSettingsKeptItem() =
        runBlocking<Unit> {
            seedWith()

            assertFalse(
                copyOf(afterHours)
                    .kept
                    ?.items
                    .orEmpty()
                    .contains(KeptItem.OtherSettings),
            )
        }

    private val previews =
        BuiltInChannelPreviews(service, library, SharedSnapshots(CoroutineScope(SupervisorJob())), pro) {
            setOf(ItemId("lib"))
        }

    @Test
    fun aPreviewOfACopyOfAfterHoursIsMadeForProAndUnavailableForFree() =
        runBlocking<Unit> {
            library.delegate.useAccount(ACCOUNT)
            seedWith()
            val draft = copyOf(afterHours)

            assertTrue(previews.preview(ACCOUNT, draft, NOON, null) !== ChannelPreview.Unavailable)
            pro.set(false)
            assertEquals(ChannelPreview.Unavailable, previews.preview(ACCOUNT, draft, NOON, null))
        }
}
