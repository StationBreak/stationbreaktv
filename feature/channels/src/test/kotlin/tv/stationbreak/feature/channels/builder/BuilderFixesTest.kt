// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.ChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftProblem
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Left
import tv.stationbreak.feature.channels.PanelMove.Right
import tv.stationbreak.feature.channels.summary
import kotlin.time.Instant

class BuilderFixesTest {
    private val lib1 = DraftSource.Library("lib1", "Library 1")

    // Opens a copy with [filters] and goes to its Rating row.
    private fun BuilderRig.ratingRow(
        filters: DraftFilters,
        kids: Boolean = false,
    ) {
        builder.openResult =
            OpenResult.Opened(draftOf("Copy", listOf(lib1), kids = kids, origin = BUILT_IN, filters = filters), null)
        vm.openCopy(BUILT_IN) { it }
        settle()
        press(Right)
        select()
        press(Down, Down, Down, Down, Down)
        assertEquals(BuilderFocus.NarrowRow(NarrowRowKind.Rating), ui.focus)
    }

    @Test
    fun aStoredTvY7CeilingStepsToItsNeighbours() =
        builderTest { rig ->
            rig.ratingRow(DraftFilters(maxRating = "TV-Y7"))
            rig.press(Left)
            assertEquals("TV-Y", rig.ui.draft.filters.maxRating)
            rig.press(Left)
            assertEquals("TV-Y", rig.ui.draft.filters.maxRating)
            rig.press(Right, Right)
            assertEquals("TV-PG", rig.ui.draft.filters.maxRating)
        }

    @Test
    fun aCeilingNeverFallsToAnyFromTheLowestTier() =
        builderTest { rig ->
            rig.ratingRow(DraftFilters(maxRating = "TV-Y7-FV"))
            rig.press(Right)
            assertEquals("TV-PG", rig.ui.draft.filters.maxRating)
            rig.press(Left, Left, Left, Left)
            assertEquals("TV-Y", rig.ui.draft.filters.maxRating)
        }

    @Test
    fun anyIsAboveTheTopTierForGrownUps() =
        builderTest { rig ->
            rig.ratingRow(DraftFilters(maxRating = "TV-MA"))
            rig.press(Right, Right, Right)
            assertNull(rig.ui.draft.filters.maxRating)
            rig.press(Left)
            assertEquals("TV-X", rig.ui.draft.filters.maxRating)
        }

    @Test
    fun aKidsCeilingStopsAtTheKidsHoldAndHasNoAny() =
        builderTest { rig ->
            rig.ratingRow(DraftFilters(maxRating = "TV-PG"), kids = true)
            rig.press(Right, Right)
            assertEquals("TV-PG", rig.ui.draft.filters.maxRating)
            rig.select()
            assertEquals("TV-Y", rig.ui.draft.filters.maxRating)
        }

    @Test
    fun newAndCopyStepOnlyOverFreeNumbers() =
        builderTest { rig ->
            rig.channels.list.value =
                listOf(summary(ChannelId("a"), 601, name = "A"), summary(ChannelId("b"), 602, name = "B"))
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.press(Down, Right)
            assertEquals(603, rig.ui.draft.number)
            rig.press(Left)
            assertEquals(600, rig.ui.draft.number)
        }

    @Test
    fun aNumberTakenAtCreateIsOnTheDraftAndSaysPickAnother() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.NumberTaken(ChannelId("a"), swappable = false))
            rig.channels.list.value = listOf(summary(ChannelId("a"), 600, name = "A"))
            rig.vm.save(andWatch = true)
            rig.advance(1)
            assertEquals(BuilderMessage.NumberTaken, rig.ui.message)
            assertNull(rig.ui.confirm)
            assertEquals("A", rig.ui.numberHolder)
        }

    @Test
    fun reshuffleWithUnsavedChangesDoesNothing() =
        builderTest { rig ->
            rig.openEdit()
            rig.press(Down, Down, Down, Down, Right)
            assertTrue(rig.ui.dirty)
            rig.vm.reshuffle()
            rig.advance(1)
            assertTrue("reshuffle" !in rig.builder.calls)
            assertEquals(4, rig.ui.draft.seriesCap)
        }

    @Test
    fun aCopyReachesUseBuildersSettingsWithTheDpad() =
        builderTest { rig ->
            val kept = KeptSettings("Movies", listOf(KeptItem.Order(KeptOrder.Chronological)))
            rig.builder.openResult =
                OpenResult.Opened(draftOf("Movies", listOf(lib1), order = null, kept = kept, origin = BUILT_IN), null)
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()
            rig.vm.next()
            rig.press(Down, Down)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Kept), rig.ui.focus)
            rig.select()
            assertEquals(PlayOrder.Shuffle, rig.ui.draft.order)
            assertEquals(emptyList<KeptItem>(), rig.ui.draft.keptInForce)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Order), rig.ui.focus)
        }

    @Test
    fun theKeptRowOfTheSheetUsesBuildersSettings() =
        builderTest { rig ->
            val kept = KeptSettings("Mine", listOf(KeptItem.Order(KeptOrder.Chronological)))
            rig.openEdit(draftOf("Mine", listOf(lib1), order = null, kept = kept, origin = CUSTOM))
            rig.press(Down, Down, Down, Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Kept), rig.ui.focus)
            rig.select()
            assertEquals(emptyList<KeptItem>(), rig.ui.draft.keptInForce)
        }

    @Test
    fun aProblemShowsOnlyOnItsOwnStep() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            assertNull(rig.ui.problem)
            rig.vm.next()
            assertNull(rig.ui.problem)
            rig.vm.next()
            rig.vm.type("")
            assertEquals(DraftProblem.NameEmpty, rig.ui.problem)
        }

    @Test
    fun aSecondSaveOfANewChannelSavesInsteadOfCreatingAgain() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.vm.save(andWatch = false)
            rig.advance(1)
            rig.vm.save(andWatch = false)
            rig.advance(1)
            assertEquals(1, rig.builder.created.size)
            assertEquals(1, rig.builder.saved.size)
        }

    @Test
    fun aPreviewCancelledMidCallNeverLands() =
        builderTest(
            wrapPreviews = { _ ->
                object : ChannelPreviews {
                    override suspend fun preview(
                        account: LibraryAccount,
                        draft: ChannelDraft,
                        at: Instant,
                    ): ChannelPreview {
                        delay(1000)
                        return ChannelPreview.Ready(draft.sources.size, 1, emptyList(), emptyList())
                    }
                }
            },
        ) { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            rig.tickFirst()
            rig.advance(500)
            rig.press(Down)
            rig.select()
            rig.advance(5000)
            assertEquals(2, rig.ui.barCount)
        }
}
