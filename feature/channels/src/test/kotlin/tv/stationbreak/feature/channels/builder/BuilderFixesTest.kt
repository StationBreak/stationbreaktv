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
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
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
    fun aKidsCeilingStopsAtPgAndKeepsAStoredPg() =
        builderTest { rig ->
            rig.ratingRow(DraftFilters(maxRating = "PG"), kids = true)
            rig.press(Right, Right)
            assertEquals("PG", rig.ui.draft.filters.maxRating)
            rig.press(Left)
            assertEquals("TV-Y7", rig.ui.draft.filters.maxRating)
            rig.press(Right, Right)
            rig.select()
            assertEquals("G", rig.ui.draft.filters.maxRating)
        }

    @Test
    fun newAndCopyStepOnlyOverFreeNumbers() =
        builderTest { rig ->
            // The domain's list: 601 and 602 are held, 605 is retired, 604 is a renumbered channel's default.
            rig.builder.free = Listed.Ok((600..610).toList() - setOf(601, 602, 604, 605))
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.press(Down, Right)
            assertEquals(603, rig.ui.draft.number)
            rig.press(Right)
            assertEquals(606, rig.ui.draft.number)
            rig.press(Left, Left)
            assertEquals(600, rig.ui.draft.number)
            assertEquals(1, rig.builder.calls.count { it == "freeNumbers" })
        }

    @Test
    fun theNumbersAreReadAgainAfterANumberTakenRefusal() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.NumberTaken(ChannelId("a"), swappable = false))
            rig.channels.list.value = listOf(summary(ChannelId("a"), 600, name = "A"))
            rig.builder.free = Listed.Ok(listOf(601, 603))
            rig.vm.save(andWatch = true)
            rig.advance(1)
            assertEquals(2, rig.builder.calls.count { it == "freeNumbers" })
            rig.press(Down, Right)
            assertEquals(601, rig.ui.draft.number)
        }

    @Test
    fun anEditStepsFromItsOwnNumberToo() =
        builderTest { rig ->
            rig.builder.free = Listed.Ok(listOf(601, 603))
            rig.openEdit(draftOf("Mine", listOf(lib1), number = 602, origin = CUSTOM))
            rig.press(Down, Down, Down, Down, Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Number), rig.ui.focus)
            rig.press(Right)
            assertEquals(603, rig.ui.draft.number)
            rig.press(Left)
            assertEquals(602, rig.ui.draft.number)
            rig.press(Left)
            assertEquals(601, rig.ui.draft.number)
        }

    @Test
    fun turningKidsOnReadsTheKidsNumbersAndTheNumberRowStepsOverThem() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.builder.number = 5
            rig.builder.free = Listed.Ok(listOf(5, 7, 8))
            rig.press(Down, Down, Down)
            rig.select()
            assertTrue(rig.ui.draft.kids)
            rig.vm.next()
            rig.press(Down, Right)
            assertEquals(7, rig.ui.draft.number)
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
    fun aSecondSaveOfANewChannelMakesNoSecondOne() =
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
            assertEquals(0, rig.builder.saved.size)
            assertTrue(rig.ui.saved)
            assertEquals(BuilderMode.New, rig.ui.mode)
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

    @Test
    fun theSwapConfirmSendsTheDraftThatWasRefusedAndInputWaitsWhileSaving() =
        builderTest { rig ->
            val holder = ChannelId("custom:other")
            rig.channels.list.value = listOf(summary(holder, 600, name = "Other"))
            rig.openEdit()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.NumberTaken(holder, swappable = true))
            val refused = rig.ui.draft
            rig.vm.save(andWatch = false)
            // Input is ignored while the save is out.
            rig.press(Down, Down, Down, Down, Down, Down, Down)
            rig.press(Right)
            rig.advance(1)
            assertEquals(refused.number, rig.ui.draft.number)
            // The confirm carries the draft it asked about.
            assertEquals(refused, (rig.ui.confirm as BuilderConfirm.Swap).draft)
            rig.builder.buildResult = null
            rig.press(Left)
            rig.select()
            assertEquals(
                refused,
                rig.builder.saved
                    .last()
                    .second,
            )
        }

    @Test
    fun aSaveBlockedByAnotherStepsProblemSaysWhichStep() =
        builderTest { rig ->
            rig.openEdit(draftOf("Mine", listOf(lib1), filters = DraftFilters(yearFrom = 2000)))
            rig.press(Down, Down, Down, Down, Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Number), rig.ui.focus)
            rig.select()
            assertEquals(BuilderStep.Name, rig.ui.step)
            assertNull(rig.ui.problem)
            rig.press(Down)
            rig.press(Down)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Save), rig.ui.focus)
            rig.select()
            assertEquals(DraftProblem.YearsIncomplete, rig.ui.problem)
            assertEquals(BuilderStep.Sources, rig.ui.problemStep)
            assertTrue(rig.builder.saved.isEmpty())
        }

    @Test
    fun switchingKidsOnRewritesACeilingToTheKidsChoiceNoLooser() =
        builderTest { rig ->
            val expected =
                mapOf(
                    "TV-PG" to "PG",
                    "R" to "PG",
                    "none" to "PG",
                    "TV-Y7" to "TV-Y7",
                    "TV-G" to "G",
                    "TV-Y" to "G",
                    "Bogus" to "G",
                )
            for (stored in listOf("TV-PG", "R", null, "TV-Y7", "TV-G", "TV-Y", "Bogus")) {
                rig.builder.number = 1
                rig.builder.openResult =
                    OpenResult.Opened(
                        draftOf("Copy", listOf(lib1), origin = BUILT_IN, filters = DraftFilters(maxRating = stored)),
                        null,
                    )
                rig.vm.openCopy(BUILT_IN) { it }
                rig.settle()
                rig.vm.next()
                rig.press(Down, Down, Down)
                rig.select()
                assertTrue(rig.ui.draft.kids)
                assertEquals(expected.getValue(stored ?: "none"), rig.ui.draft.filters.maxRating)
            }
        }

    @Test
    fun aKidsDraftIsShownAsTheBuilderOpenedItWithNothingUnsaved() =
        builderTest { rig ->
            rig.builder.openResult =
                OpenResult.Opened(
                    draftOf("Kids", listOf(lib1), true, origin = BUILT_IN, filters = DraftFilters(maxRating = "PG")),
                    null,
                )
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()
            assertEquals("PG", rig.ui.draft.filters.maxRating)
            assertEquals(false, rig.ui.dirty)
        }

    @Test
    fun aReshuffledKidsChannelIsReopenedAsTheBuilderOpenedIt() =
        builderTest { rig ->
            val kids = draftOf("Kids", listOf(lib1), true, origin = CUSTOM, filters = DraftFilters(maxRating = "PG"))
            rig.openEdit(kids)
            rig.builder.openResult = OpenResult.Opened(kids.copy(seed = Seed(2uL)), null)
            rig.press(Down, Down, Down, Down, Down, Down, Down, Down, Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Reshuffle), rig.ui.focus)
            rig.select()
            assertEquals(Seed(2uL), rig.ui.draft.seed)
            assertEquals("PG", rig.ui.draft.filters.maxRating)
            assertEquals(false, rig.ui.dirty)
        }
}
