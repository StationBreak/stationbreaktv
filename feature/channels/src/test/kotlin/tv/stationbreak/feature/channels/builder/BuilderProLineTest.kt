// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.OpenResult
import tv.stationbreak.feature.channels.PanelMove

/**
 * The builder's Pro lines with a way on (#1146): the focus, Select, Back and the return from the purchase screen, as a
 * state machine. With no store the line stays text and the focus stays where it was.
 */
class BuilderProLineTest {
    private val lib1 = DraftSource.Library("lib1", "Library 1")
    private val see = BuilderFocus.Choice(0)
    private val ok = BuilderFocus.Choice(1)

    // An edit sheet with the focus off its first row, so a restore is not the default focus; Save then refused for Pro.
    private fun BuilderRig.proAtSave(refusal: BuildRefusal = BuildRefusal.NeedsPro): BuilderFocus {
        openEdit(draftOf("Mine", listOf(lib1)))
        press(PanelMove.Down, PanelMove.Down)
        val before = checkNotNull(ui.focus)
        builder.buildResult = BuildResult.Refused(refusal)
        vm.save(andWatch = false)
        advance(1)
        return before
    }

    @Test
    fun withAStore_theProLineMovesTheFocusToSeeProAndTheTextIsTheQuestion() =
        builderTest(storeAvailable = true) { rig ->
            rig.proAtSave()

            assertEquals(BuilderConfirm.Pro(BuilderMessage.NeedsPro), rig.ui.confirm)
            assertEquals(see, rig.ui.focus)
            assertNull("the text is the question, not a second line", rig.ui.message)
        }

    @Test
    fun theCustomLimitLineGetsTheSameWayOn() =
        builderTest(storeAvailable = true) { rig ->
            rig.proAtSave(BuildRefusal.CustomLimit)

            assertEquals(BuilderConfirm.Pro(BuilderMessage.CustomLimit), rig.ui.confirm)
            assertEquals(see, rig.ui.focus)
        }

    @Test
    fun withNoStore_theLineStaysTextAndTheFocusStaysOnTheRow() =
        builderTest { rig ->
            val before = rig.proAtSave()

            assertNull(rig.ui.confirm)
            assertEquals(BuilderMessage.NeedsPro, rig.ui.message)
            assertEquals(before, rig.ui.focus)
        }

    @Test
    fun aLineThatIsNotAboutProNeverGetsTheWayOn() =
        builderTest(storeAvailable = true) { rig ->
            rig.proAtSave(BuildRefusal.KidsBandFull)

            assertNull(rig.ui.confirm)
            assertEquals(BuilderMessage.KidsBandFull, rig.ui.message)
        }

    @Test
    fun selectOnSeeProSendsTheEventAndLeavesTheLineUp() =
        builderTest(storeAvailable = true) { rig ->
            rig.proAtSave()
            rig.select()

            assertEquals(listOf<BuilderEvent>(BuilderEvent.SeePro), rig.events)
            assertEquals(BuilderConfirm.Pro(BuilderMessage.NeedsPro), rig.ui.confirm)
            assertEquals(see, rig.ui.focus)
        }

    @Test
    fun backFromThePurchaseScreen_findsSeeProAgain_andOkThenRestoresTheRow() =
        builderTest(storeAvailable = true) { rig ->
            val before = rig.proAtSave()
            rig.select()

            rig.vm.proReturned(owned = false)
            rig.settle()
            assertEquals(see, rig.ui.focus)
            assertEquals(BuilderConfirm.Pro(BuilderMessage.NeedsPro), rig.ui.confirm)

            rig.press(PanelMove.Down)
            assertEquals(ok, rig.ui.focus)
            rig.select()
            assertNull(rig.ui.confirm)
            assertEquals(before, rig.ui.focus)
        }

    @Test
    fun proBoughtOnThePurchaseScreen_closesTheLineAndRestoresTheRow() =
        builderTest(storeAvailable = true) { rig ->
            val before = rig.proAtSave()
            rig.select()

            rig.vm.proReturned(owned = true)
            rig.settle()

            assertNull(rig.ui.confirm)
            assertEquals(before, rig.ui.focus)
            assertEquals("the builder did not end", listOf<BuilderEvent>(BuilderEvent.SeePro), rig.events)
        }

    @Test
    fun backOnTheLineDismissesItAsOkDoes_andRestoresTheSameRow() =
        builderTest(storeAvailable = true) { rig ->
            val before = rig.proAtSave()
            rig.back()

            assertNull(rig.ui.confirm)
            assertNull(rig.ui.message)
            assertEquals(before, rig.ui.focus)
            assertTrue("Back dismissed the line, it did not leave the builder", rig.events.isEmpty())
        }

    @Test
    fun theDirectionsMoveBetweenSeeProAndOk_andNeverLeaveThePair() =
        builderTest(storeAvailable = true) { rig ->
            rig.proAtSave()

            rig.press(PanelMove.Up)
            assertEquals(see, rig.ui.focus)
            rig.press(PanelMove.Down)
            assertEquals(ok, rig.ui.focus)
            rig.press(PanelMove.Down, PanelMove.Right)
            assertEquals(ok, rig.ui.focus)
            rig.press(PanelMove.Left)
            assertEquals(see, rig.ui.focus)
        }

    @Test
    fun selectOnOkSendsNoEvent() =
        builderTest(storeAvailable = true) { rig ->
            rig.proAtSave()
            rig.press(PanelMove.Down)
            rig.select()

            assertTrue(rig.events.isEmpty())
            assertNull(rig.ui.confirm)
        }

    @Test
    fun anOpenThatNeedsPro_hasTheLineWithNoChannelBehindIt_andEitherWayOutLeavesTheBuilder() =
        builderTest(storeAvailable = true) { rig ->
            rig.builder.openResult = OpenResult.NeedsPro
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()

            assertFalse(rig.ui.ready)
            assertEquals(BuilderConfirm.Pro(BuilderMessage.NeedsPro), rig.ui.confirm)
            assertEquals(see, rig.ui.focus)
            rig.select()
            assertEquals(listOf<BuilderEvent>(BuilderEvent.SeePro), rig.events)

            rig.back()
            assertEquals(listOf(BuilderEvent.SeePro, BuilderEvent.Closed), rig.events)
        }

    @Test
    fun anOpenThatNeedsProWithNoStoreIsTheTextAlone_andBackLeaves() =
        builderTest { rig ->
            rig.builder.openResult = OpenResult.NeedsPro
            rig.vm.openCopy(BUILT_IN) { it }
            rig.settle()

            assertNull(rig.ui.confirm)
            assertEquals(BuilderMessage.NeedsPro, rig.ui.message)
            rig.select()
            assertTrue(rig.events.isEmpty())
            rig.back()
            assertEquals(listOf<BuilderEvent>(BuilderEvent.Closed), rig.events)
        }

    @Test
    fun aProLineArrivingOverAnotherQuestionStaysTextAndDoesNotStealIt() =
        builderTest(storeAvailable = true) { rig ->
            rig.openEdit(draftOf("Mine", listOf(lib1)))
            val line = BuilderModel(confirm = BuilderConfirm.KidsOff, message = BuilderMessage.NeedsPro)
            val kept = line.proNoted(BuilderModel(confirm = BuilderConfirm.KidsOff), offered = true)

            assertSame(line, kept)
        }

    @Test
    fun theTransitionIsPure_theFocusBeforeIsTheResumeAndSeeProIsTheTarget() {
        val field = BuilderFocus.Sheet(SheetRow.Sources)
        val old = BuilderModel(ready = true, focus = field)
        val arrived = old.copy(message = BuilderMessage.NeedsPro)

        val noted = arrived.proNoted(old, offered = true)
        assertEquals(BuilderConfirm.Pro(BuilderMessage.NeedsPro), noted.confirm)
        assertEquals(see, noted.focus)
        assertEquals(field, noted.resume)
        assertNull(noted.message)

        assertSame("no store, no change", arrived, arrived.proNoted(old, offered = false))
        assertSame("the same line again is not new", arrived, arrived.proNoted(arrived, offered = true))
    }
}
