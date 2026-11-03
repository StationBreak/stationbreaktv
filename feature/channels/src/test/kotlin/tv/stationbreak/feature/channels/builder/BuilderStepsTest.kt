// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.domain.TitleKind
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.CatalogPicker
import tv.stationbreak.core.domain.lineup.builder.ChannelPreview
import tv.stationbreak.core.domain.lineup.builder.DraftProblem
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.KeptItem
import tv.stationbreak.core.domain.lineup.builder.KeptOrder
import tv.stationbreak.core.domain.lineup.builder.KeptSettings
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Left
import tv.stationbreak.feature.channels.PanelMove.Right
import tv.stationbreak.feature.channels.PanelMove.Up
import tv.stationbreak.feature.channels.summary

class BuilderStepsTest {
    private val lib1 = DraftSource.Library("lib1", "Library 1")

    @Test
    fun capsStepBetween1And10AndAreHiddenForInOrder() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.press(Down)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.SeriesCap), rig.ui.focus)
            repeat(12) { rig.press(Right) }
            assertEquals(10, rig.ui.draft.seriesCap)
            repeat(12) { rig.press(Left) }
            assertEquals(1, rig.ui.draft.seriesCap)
            rig.press(Up, Right, Right, Down)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Kids), rig.ui.focus)
        }

    @Test
    fun choosingInOrderHidesCapsAndKeepsThem() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.press(Down, Right, Right)
            assertEquals(5, rig.ui.draft.seriesCap)
            rig.press(Up, Right, Right)
            assertEquals(PlayOrder.InOrder, rig.ui.draft.order)
            // Down skips the hidden caps and lands on the Kids switch.
            rig.press(Down)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Kids), rig.ui.focus)
            rig.press(Up, Left, Left)
            assertEquals(PlayOrder.Shuffle, rig.ui.draft.order)
            assertEquals(5, rig.ui.draft.seriesCap)
            rig.press(Down)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.SeriesCap), rig.ui.focus)
        }

    private fun BuilderRig.toKids() {
        picker.libraries = libraries(1)
        vm.openNew()
        settle()
        tickFirst()
        vm.next()
        press(Down, Down, Down)
    }

    @Test
    fun kidsOnTakesTheFirstFreeKidsNumber() =
        builderTest { rig ->
            rig.builder.free = Listed.Ok(listOf(1))
            rig.toKids()
            rig.select()
            assertTrue(rig.ui.draft.kids)
            assertEquals(1, rig.ui.draft.number)
        }

    @Test
    fun kidsBandFullLeavesTheSwitchOff() =
        builderTest { rig ->
            rig.builder.free = Listed.Ok(emptyList())
            rig.toKids()
            rig.select()
            assertFalse(rig.ui.draft.kids)
            assertEquals(BuilderMessage.KidsBandFull, rig.ui.message)
        }

    @Test
    fun turningKidsOffAsksFirstAndFocusesKeepItOn() =
        builderTest { rig ->
            rig.builder.free = Listed.Ok(listOf(1))
            rig.toKids()
            rig.select()
            rig.select()
            assertEquals(BuilderConfirm.KidsOff, rig.ui.confirm)
            assertEquals(BuilderFocus.Choice(1), rig.ui.focus)
            rig.press(Left)
            rig.builder.free = Listed.Ok(listOf(600))
            rig.select()
            assertNull(rig.ui.confirm)
            assertFalse(rig.ui.draft.kids)
            assertEquals(600, rig.ui.draft.number)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Kids), rig.ui.focus)
        }

    @Test
    fun keepingItOnChangesNothing() =
        builderTest { rig ->
            rig.builder.free = Listed.Ok(listOf(1))
            rig.toKids()
            rig.select()
            rig.select()
            rig.select()
            assertNull(rig.ui.confirm)
            assertTrue(rig.ui.draft.kids)
            assertEquals(1, rig.ui.draft.number)
        }

    @Test
    fun theSuggestedNameComesFromTheFirstSource() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            assertEquals(BuilderStep.Name, rig.ui.step)
            assertEquals("Library 1", rig.ui.draft.name)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Name), rig.ui.focus)
            rig.vm.type("Saturday films")
            assertEquals("Saturday films", rig.ui.draft.name)
        }

    @Test
    fun aNameOfOnlySpacesIsRefused() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.vm.type("   ")
            assertEquals(DraftProblem.NameEmpty, rig.ui.problem)
            assertFalse(rig.ui.nextEnabled)
            rig.vm.save(andWatch = true)
            rig.advance(1)
            assertTrue(rig.builder.created.isEmpty())
        }

    @Test
    fun theNumberStaysInTheChannelsBand() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.press(Down)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Number), rig.ui.focus)
            repeat(400) { rig.press(Right) }
            assertEquals(899, rig.ui.draft.number)
            repeat(400) { rig.press(Left) }
            assertEquals(600, rig.ui.draft.number)
            assertTrue(rig.ui.draft.numberChosen)
        }

    @Test
    fun aTakenNumberAtSaveOfAnEditSaysPickAnotherAndReadsTheFreeNumbersAgain() =
        builderTest { rig ->
            val holder = ChannelId("custom:other")
            rig.channels.list.value = listOf(summary(holder, 600, name = "Other"))
            rig.openEdit()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.NumberTaken(holder, swappable = true))
            rig.builder.free = Listed.Ok(listOf(601, 602))
            rig.vm.save(andWatch = false)
            rig.advance(1)
            assertNull(rig.ui.confirm)
            assertFalse(rig.ui.saving)
            assertEquals(BuilderMessage.NumberTaken, rig.ui.message)
            assertEquals("Other", rig.ui.numberHolder)
            assertEquals(2, rig.builder.calls.count { it == "freeNumbers" })
            assertEquals(listOf(null), rig.builder.saved.map { it.third })
            // The number rows now step over what is free.
            rig.press(Down, Down, Down, Down, Down, Down, Down, Right)
            assertEquals(601, rig.ui.draft.number)
        }

    @Test
    fun everyRefusalHasItsOwnMessage() =
        builderTest { rig ->
            val expected =
                mapOf(
                    BuildResult.Refused(BuildRefusal.KidsBandFull) to BuilderMessage.KidsBandFull,
                    BuildResult.Refused(BuildRefusal.KidsBand) to BuilderMessage.KidsBand,
                    BuildResult.Refused(BuildRefusal.NoFreeNumber) to BuilderMessage.NoFreeNumber,
                    BuildResult.Refused(BuildRefusal.OutOfRange) to BuilderMessage.OutOfRange,
                    BuildResult.Refused(BuildRefusal.LastOn) to BuilderMessage.LastOn,
                    BuildResult.Refused(BuildRefusal.UnknownChannel) to BuilderMessage.NotFound,
                    BuildResult.Refused(BuildRefusal.NotCustom) to BuilderMessage.NotChangeable,
                    BuildResult.Refused(BuildRefusal.NotShuffle) to BuilderMessage.NotShuffle,
                    BuildResult.NotEditable to BuilderMessage.NotEditable,
                    BuildResult.SaveFailed to BuilderMessage.SaveFailed,
                    BuildResult.Failed to BuilderMessage.Failed,
                )
            rig.openEdit()
            expected.forEach { (result, message) ->
                rig.builder.buildResult = result
                rig.vm.save(andWatch = false)
                rig.advance(1)
                assertEquals(message, rig.ui.message)
            }
        }

    @Test
    fun anInvalidRefusalShowsOnTheStepNotAsAMessage() =
        builderTest { rig ->
            rig.openEdit()
            rig.builder.buildResult = BuildResult.Refused(BuildRefusal.Invalid(DraftProblem.NoSources))
            rig.vm.save(andWatch = false)
            rig.advance(1)
            assertNull(rig.ui.message)
            assertEquals(DraftProblem.NoSources, rig.ui.problem)
        }

    @Test
    fun backGoesToThePreviousStepAndOnTheFirstAsksOnlyWhenDirty() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.back()
            assertEquals(listOf(BuilderEvent.Closed), rig.events)
            rig.events.clear()
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.back()
            assertEquals(BuilderStep.Play, rig.ui.step)
            rig.back()
            assertEquals(BuilderStep.Sources, rig.ui.step)
            rig.back()
            assertEquals(BuilderConfirm.Leave, rig.ui.confirm)
            assertTrue(rig.events.isEmpty())
        }

    @Test
    fun leaveFocusesKeepBuilding() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.back()
            assertEquals(BuilderFocus.Choice(1), rig.ui.focus)
            rig.back()
            assertNull(rig.ui.confirm)
            assertEquals(BuilderStep.Sources, rig.ui.step)
            rig.back()
            rig.press(Left)
            rig.select()
            assertEquals(listOf(BuilderEvent.Closed), rig.events)
        }

    @Test
    fun editBackWithChangesAsksKeepEditing() =
        builderTest { rig ->
            rig.openEdit()
            rig.back()
            assertEquals(listOf(BuilderEvent.Closed), rig.events)
            rig.events.clear()
            rig.openEdit()
            rig.press(Down, Down, Down, Right)
            assertTrue(rig.ui.dirty)
            rig.back()
            assertEquals(BuilderConfirm.Leave, rig.ui.confirm)
            assertEquals(BuilderFocus.Choice(1), rig.ui.focus)
        }

    @Test
    fun anEditPageGoesBackToTheSheet() =
        builderTest { rig ->
            rig.openEdit()
            assertTrue(rig.ui.onSheet)
            rig.press(Down)
            rig.select()
            assertFalse(rig.ui.onSheet)
            assertEquals(BuilderStep.Sources, rig.ui.step)
            rig.back()
            assertTrue(rig.ui.onSheet)
            assertEquals(BuilderFocus.Sheet(SheetRow.Sources), rig.ui.focus)
        }

    @Test
    fun saveAndWatchEmitsTheNewIdForTheTune() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.press(Down, Down)
            assertEquals(BuilderFocus.NameRow(NameRowKind.Save), rig.ui.focus)
            rig.select()
            assertEquals(listOf(BuilderEvent.Saved(ChannelId("custom:fake"), true)), rig.events)
        }

    @Test
    fun aFailedSaveStaysOnTheStepWithAMessage() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.builder.buildResult = BuildResult.SaveFailed
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            rig.vm.next()
            rig.vm.save(andWatch = true)
            rig.advance(1)
            assertEquals(BuilderStep.Name, rig.ui.step)
            assertEquals(BuilderMessage.SaveFailed, rig.ui.message)
            assertFalse(rig.ui.saving)
            assertTrue(rig.events.isEmpty())
        }

    @Test
    fun openCopyFillsFromTheBuiltInWithTheNameTheScreenFormatted() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.Opened(draftOf("Movies", listOf(lib1), origin = BUILT_IN), "Movies")
            rig.vm.openCopy(BUILT_IN) { "$it (copy)" }
            rig.settle()
            assertEquals("Movies (copy)", rig.ui.draft.name)
            assertEquals(BuilderMode.Copy(BUILT_IN), rig.ui.mode)
            assertFalse(rig.ui.dirty)
            assertTrue(rig.ui.ready)
        }

    @Test
    fun anOpenThatFindsNothingSaysWhy() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.NotFound
            rig.vm.openEdit(CUSTOM)
            rig.settle()
            assertEquals(BuilderMessage.NotFound, rig.ui.message)
            assertFalse(rig.ui.ready)
            rig.builder.openResult = OpenResult.Failed
            rig.vm.openEdit(CUSTOM)
            rig.settle()
            assertEquals(BuilderMessage.OpenFailed, rig.ui.message)
            rig.builder.openResult = OpenResult.NotCustomisable
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()
            assertEquals(BuilderMessage.NotCustomisable, rig.ui.message)
        }

    @Test
    fun theKeptLineShowsForACopyAndUseBuildersSettingsClearsIt() =
        builderTest { rig ->
            val kept = KeptSettings("Movies", listOf(KeptItem.Order(KeptOrder.Chronological)))
            rig.builder.openResult =
                OpenResult.Opened(draftOf("Movies", listOf(lib1), order = null, kept = kept, origin = BUILT_IN), null)
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()
            assertEquals(listOf(KeptItem.Order(KeptOrder.Chronological)), rig.ui.draft.keptInForce)
            rig.vm.useBuildersSettings()
            assertEquals(emptyList<KeptItem>(), rig.ui.draft.keptInForce)
            assertEquals(PlayOrder.Shuffle, rig.ui.draft.order)
            assertTrue(rig.ui.dirty)
        }

    @Test
    fun deleteAsksAndFocusesCancel() =
        builderTest { rig ->
            rig.openEdit()
            rig.vm.delete()
            assertEquals(BuilderConfirm.Delete("Mine"), rig.ui.confirm)
            assertEquals(BuilderFocus.Choice(1), rig.ui.focus)
            assertFalse("delete" in rig.builder.calls)
            rig.press(Left)
            rig.select()
            assertTrue("delete" in rig.builder.calls)
            assertEquals(listOf(BuilderEvent.Closed), rig.events)
        }

    @Test
    fun closingBuilderStartsClean() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            rig.tickFirst()
            rig.vm.next()
            assertEquals(BuilderStep.Play, rig.ui.step)
            rig.openNew()
            assertEquals(BuilderStep.Sources, rig.ui.step)
            assertEquals(emptyList<DraftSource>(), rig.ui.draft.sources)
            assertEquals(BuilderMode.New, rig.ui.mode)
            assertFalse(rig.ui.dirty)
            assertNull(rig.ui.message)
            assertNull(rig.ui.confirm)
        }

    @Test
    fun aFastDoubleBackClosesOnce() =
        builderTest { rig ->
            rig.openNew()
            rig.vm.back()
            rig.vm.back()
            rig.settle()
            assertEquals(listOf(BuilderEvent.Closed), rig.events)
            rig.events.clear()
            rig.openNew()
            rig.settle()
            assertTrue(rig.events.isEmpty())
        }
}
