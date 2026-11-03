// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Left
import tv.stationbreak.feature.channels.PanelMove.Right

/** The final review's fixes: the draft's own pool, inputs before it is ready, Back while saving, and the edit pages. */
class BuilderFinalFixesTest {
    private val lib1 = DraftSource.Library("lib1", "Library 1")

    private fun BuilderRig.openDecadeCopy() {
        val kept = KeptSettings("Eighties", listOf(KeptItem.YearsSource(1980, 1989)))
        builder.openResult =
            OpenResult.Opened(draftOf("Eighties", emptyList(), origin = BUILT_IN, kept = kept), "Eighties")
        vm.openCopy(BUILT_IN) { "$it (copy)" }
        settle()
    }

    @Test
    fun aCopyOfADecadeChannelCanGoNextAndSave() =
        builderTest { rig ->
            rig.openDecadeCopy()

            assertTrue(
                rig.ui.draft.sources
                    .isEmpty(),
            )
            assertTrue(rig.ui.nextEnabled)
            rig.vm.next()
            assertEquals(BuilderStep.Play, rig.ui.step)
            rig.vm.next()
            assertEquals(BuilderStep.Name, rig.ui.step)
            rig.vm.save(andWatch = false)
            rig.advance(1)
            assertEquals(1, rig.builder.created.size)
            assertEquals(listOf(BuilderEvent.Saved(ChannelCreated, false)), rig.events)
        }

    @Test
    fun aFailedOpenShowsItsMessageAndOnlyBackWorks() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.NotFound
            rig.vm.openEdit(CUSTOM)
            rig.settle()
            assertFalse(rig.ui.ready)
            assertEquals(BuilderMessage.NotFound, rig.ui.message)

            rig.press(Down, Down, Right)
            rig.select()
            rig.vm.type("typed")
            rig.vm.next()
            rig.vm.save(andWatch = true)
            rig.vm.delete()
            rig.vm.reshuffle()
            rig.settle()

            assertEquals(BuilderMessage.NotFound, rig.ui.message)
            assertFalse(rig.ui.dirty)
            assertNull(rig.ui.confirm)
            assertTrue(rig.builder.calls.none { it == "save" || it == "create" || it == "delete" })
            rig.back()
            assertEquals(listOf<BuilderEvent>(BuilderEvent.Closed), rig.events)
        }

    @Test
    fun keysBeforeTheDraftArrivesDoNothingAndArrivalStartsOnTheFirstTab() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.vm.openNew()
            // Nothing has run yet: the draft has not arrived.
            rig.vm.move(Down)
            rig.vm.select()
            rig.vm.move(Right)
            rig.vm.select()
            assertEquals(SourceKindTab.Libraries, rig.ui.tab)

            rig.settle()

            assertTrue(rig.ui.ready)
            assertEquals(SourceKindTab.Libraries, rig.ui.tab)
            assertEquals(BuilderFocus.Tab(SourceKindTab.Libraries), rig.ui.focus)
            assertFalse(rig.ui.dirty)
            assertNull(rig.ui.narrow)
        }

    @Test
    fun arrivalResetsWhatWasOpenBeforeIt() =
        builderTest { rig ->
            val before = BuilderModel(tab = SourceKindTab.Collections, narrowOpen = true, leavingOut = true)

            val arrived = before.arrived(rig.account, draftOf("Mine", listOf(lib1)))

            assertEquals(SourceKindTab.Libraries, arrived.tab)
            assertFalse(arrived.narrowOpen)
            assertFalse(arrived.leavingOut)
            assertEquals(0, arrived.genreCursor)
        }

    @Test
    fun backIsIgnoredWhileASaveIsOutAndTheSavedEventStillArrives() =
        builderTest { rig ->
            rig.openEdit()
            rig.press(Down, Down, Down, Right)
            assertTrue(rig.ui.dirty)

            rig.vm.save(andWatch = false)
            rig.vm.back()
            assertNull(rig.ui.confirm)
            assertTrue(rig.events.isEmpty())

            rig.settle()

            assertNull(rig.ui.confirm)
            assertEquals(listOf<BuilderEvent>(BuilderEvent.Saved(CUSTOM, false)), rig.events)
        }

    @Test
    fun backIsIgnoredWhileANewChannelIsBeingMade() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.vm.save(andWatch = false)
            rig.vm.back()
            assertEquals(BuilderStep.Name, rig.ui.step)
            rig.settle()
            assertEquals(1, rig.events.filterIsInstance<BuilderEvent.Saved>().size)
        }

    // The edit pages

    private fun BuilderRig.openPage(downs: Int) {
        openEdit()
        repeat(downs) { press(Down) }
        select()
    }

    @Test
    fun anEditPageHasNoNextAndDoneGoesBackToTheSameSheetRow() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openPage(downs = 1)
            assertFalse(rig.ui.onSheet)
            assertEquals(BuilderStep.Sources, rig.ui.step)
            assertTrue(rig.ui.donePage)

            rig.vm.next()
            assertEquals(BuilderStep.Sources, rig.ui.step)
            assertFalse(rig.ui.onSheet)

            // Right off the tabs and the list reaches the one action, Done.
            rig.press(Right, Down, Down)
            assertEquals(BuilderFocus.Next, rig.ui.focus)
            rig.select()

            assertTrue(rig.ui.onSheet)
            assertEquals(BuilderFocus.Sheet(SheetRow.Sources), rig.ui.focus)
            assertTrue(rig.builder.saved.isEmpty())
            assertTrue(rig.events.isEmpty())
        }

    @Test
    fun theNumberPageOfAnEditNeverReachesSaveAndWatch() =
        builderTest { rig ->
            rig.openPage(downs = 7)
            assertEquals(BuilderStep.Name, rig.ui.step)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Number), rig.ui.focus)

            rig.press(Down)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Done), rig.ui.focus)
            rig.press(Down, Down)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Done), rig.ui.focus)
            rig.select()

            assertTrue(rig.builder.saved.isEmpty())
            assertTrue(rig.builder.created.isEmpty())
            assertTrue(rig.events.isEmpty())
            assertTrue(rig.ui.onSheet)
            assertEquals(BuilderFocus.Sheet(SheetRow.Number), rig.ui.focus)
        }

    @Test
    fun theSheetsSaveIsStillTheOnlyWriteInEditMode() =
        builderTest { rig ->
            rig.openPage(downs = 7)
            rig.press(Down)
            rig.select()
            // Back on the sheet; Save writes, and only that.
            rig.press(Down, Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Save), rig.ui.focus)
            rig.select()
            assertEquals(1, rig.builder.saved.size)
        }

    @Test
    fun aNewChannelsPagesKeepNextAndSaveAndWatch() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            assertFalse(rig.ui.donePage)
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.press(Down, Down)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Save), rig.ui.focus)
        }

    // One Closed per visit

    @Test
    fun aLeaveAnswerClosesOnceAndALaterBackAsksNothing() =
        builderTest { rig ->
            rig.openEdit()
            rig.press(Down, Down, Down, Right)
            rig.back()
            assertEquals(BuilderConfirm.Leave, rig.ui.confirm)
            rig.press(Left)
            rig.select()
            rig.back()
            assertNull(rig.ui.confirm)
            assertEquals(listOf<BuilderEvent>(BuilderEvent.Closed), rig.events)
        }

    @Test
    fun aDeleteClosesOnceAndALaterBackAsksNothing() =
        builderTest { rig ->
            rig.openEdit()
            rig.vm.delete()
            rig.press(Left)
            rig.select()
            rig.back()
            assertNull(rig.ui.confirm)
            assertEquals(listOf<BuilderEvent>(BuilderEvent.Closed), rig.events)
        }

    // The sheet before the channel is open

    @Test
    fun theSheetListsNoActionRowsUntilTheChannelIsOpen() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Opened(draftOf("Mine", listOf(lib1)), null)
            rig.vm.openEdit(CUSTOM)
            val before = rig.ui.sheetRows
            assertTrue(SheetRow.Save !in before && SheetRow.Delete !in before && SheetRow.Reshuffle !in before)

            rig.settle()

            assertTrue(SheetRow.Save in rig.ui.sheetRows && SheetRow.Delete in rig.ui.sheetRows)
        }

    @Test
    fun aFailedOpenOfAnEditListsNoActionRows() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Failed
            rig.vm.openEdit(CUSTOM)
            rig.settle()

            assertTrue(SheetRow.Save !in rig.ui.sheetRows && SheetRow.Delete !in rig.ui.sheetRows)
        }

    // The kept action

    @Test
    fun aDraftKeepingOnlyABlockLengthShowsTheKeptLineButNoKeptAction() =
        builderTest { rig ->
            val kept = KeptSettings("Movies", listOf(KeptItem.BlockLength(4)))
            rig.openEdit(draftOf("Mine", listOf(lib1), kept = kept, origin = CUSTOM))
            assertEquals(listOf<KeptItem>(KeptItem.BlockLength(4)), rig.ui.draft.keptInForce)
            assertTrue(SheetRow.Kept !in rig.ui.sheetRows)
            assertTrue(PlayRowKind.Kept !in playRowsOf(rig.ui.draft))
        }

    @Test
    fun aDraftKeepingAnOrderShowsTheKeptLineAndTheKeptAction() =
        builderTest { rig ->
            val kept = KeptSettings("Movies", listOf(KeptItem.Order(KeptOrder.Chronological), KeptItem.BlockLength(4)))
            rig.openEdit(draftOf("Mine", listOf(lib1), order = null, kept = kept, origin = CUSTOM))
            assertEquals(2, rig.ui.draft.keptInForce.size)
            assertTrue(SheetRow.Kept in rig.ui.sheetRows)
            assertTrue(PlayRowKind.Kept in playRowsOf(rig.ui.draft))
            // The action replaces the order; the block length, which no control replaces, stays as information.
            rig.vm.useBuildersSettings()
            assertEquals(listOf<KeptItem>(KeptItem.BlockLength(4)), rig.ui.draft.keptInForce)
            assertTrue(SheetRow.Kept !in rig.ui.sheetRows)
        }

    // Closing the screen

    @Test
    fun closingTheBuilderStopsItsPreviewAndSearchButNotASaveInFlight() =
        builderTest { rig ->
            rig.previews.answer = ChannelPreview.Ready(1, 1, emptyList(), emptyList())
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.close()
            rig.advance(5000)
            assertTrue(rig.previews.drafts.isEmpty())

            rig.openEdit()
            rig.press(Down, Down, Down, Right)
            rig.vm.save(andWatch = false)
            rig.vm.close()
            rig.settle()
            assertEquals(1, rig.builder.saved.size)
            assertEquals(
                CUSTOM,
                rig.builder.saved
                    .single()
                    .first,
            )
        }

    private companion object {
        val ChannelCreated = ChannelId("custom:fake")
    }
}
