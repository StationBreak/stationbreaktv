// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.BandNeeded
import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.DraftFilters
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Left
import tv.stationbreak.feature.channels.PanelMove.Right
import tv.stationbreak.feature.channels.PanelMove.Up

/** #1152: the Family channel switch and its Cap row (Tots, Kids, Teens) on How it plays and on the sheet. */
class BuilderCapTest {
    private fun BuilderRig.toFamilyRow() {
        picker.libraries = libraries(1)
        vm.openNew()
        settle()
        tickFirst()
        vm.next()
        press(Down, Down, Down)
        assertEquals(BuilderFocus.PlayRow(PlayRowKind.Family), ui.focus)
    }

    @Test
    fun theCapRowIsSkippedWhileFamilyChannelIsOff() =
        builderTest { rig ->
            rig.toFamilyRow()

            assertNull(rig.ui.draft.tier)
            assertFalse(PlayRowKind.Cap in playRowsOf(rig.ui.draft))
            // Family is the last row: Down goes on to Next, and Cap is never a stop.
            rig.press(Down)
            assertEquals(BuilderFocus.Next, rig.ui.focus)
        }

    @Test
    fun turningFamilyOnDefaultsTheCapToTotsAtTheFirstTotsNumber() =
        builderTest { rig ->
            rig.toFamilyRow()

            rig.select()

            assertEquals(FamilyTier.Tots, rig.ui.draft.tier)
            assertEquals(1, rig.ui.draft.number)
            val rows = playRowsOf(rig.ui.draft)
            assertEquals(PlayRowKind.Cap, rows[rows.indexOf(PlayRowKind.Family) + 1])
        }

    @Test
    fun leftAndRightMoveTheCapAndTakeTheFirstFreeNumberOfTheNewBand() =
        builderTest { rig ->
            rig.toFamilyRow()
            rig.select()
            rig.press(Down)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Cap), rig.ui.focus)

            rig.press(Right)
            assertEquals(FamilyTier.Kids, rig.ui.draft.tier)
            assertEquals(34, rig.ui.draft.number)
            assertFalse(rig.ui.draft.numberChosen)
            assertEquals("PG", rig.ui.draft.filters.maxRating)

            rig.press(Right)
            assertEquals(FamilyTier.Teens, rig.ui.draft.tier)
            assertEquals(67, rig.ui.draft.number)
            // The ceiling it had still holds it; a Teens channel may be PG.
            assertEquals("PG", rig.ui.draft.filters.maxRating)

            // Right stops at Teens; Left goes back down.
            rig.press(Right)
            assertEquals(FamilyTier.Teens, rig.ui.draft.tier)
            rig.press(Left)
            rig.press(Left)
            assertEquals(FamilyTier.Tots, rig.ui.draft.tier)
            assertEquals(1, rig.ui.draft.number)
            rig.press(Left)
            assertEquals(FamilyTier.Tots, rig.ui.draft.tier)
            assertEquals(BuilderFocus.PlayRow(PlayRowKind.Cap), rig.ui.focus)
        }

    @Test
    fun selectOnTheCapGoesRoundToTheFirstAfterTheLast() =
        builderTest { rig ->
            rig.toFamilyRow()
            rig.select()
            rig.press(Down)

            rig.select()
            assertEquals(FamilyTier.Kids, rig.ui.draft.tier)
            rig.select()
            assertEquals(FamilyTier.Teens, rig.ui.draft.tier)
            rig.select()
            assertEquals(FamilyTier.Tots, rig.ui.draft.tier)
        }

    @Test
    fun aBandWithNoFreeNumberLeavesTheCapWhereItWasAndSaysWhich() =
        builderTest { rig ->
            rig.toFamilyRow()
            rig.select()
            rig.press(Down)
            rig.builder.free = Listed.Ok(emptyList())

            rig.press(Right)

            assertEquals(FamilyTier.Tots, rig.ui.draft.tier)
            assertEquals(BuilderMessage.KidsTierBandFull, rig.ui.message)
            rig.builder.free = null
            rig.press(Right)
            rig.press(Right)
            rig.builder.free = Listed.Ok(emptyList())
            rig.press(Left)
            assertEquals(FamilyTier.Teens, rig.ui.draft.tier)
        }

    @Test
    fun turningFamilyOffFromTeensAsksAndTakesAGrownUpNumber() =
        builderTest { rig ->
            rig.toFamilyRow()
            rig.select()
            rig.press(Down)
            rig.press(Right)
            rig.press(Right)
            rig.press(Up)
            assertEquals(FamilyTier.Teens, rig.ui.draft.tier)

            rig.select()
            assertEquals(BuilderConfirm.KidsOff, rig.ui.confirm)
            rig.press(Left)
            rig.select()

            assertNull(rig.ui.draft.tier)
            assertEquals(600, rig.ui.draft.number)
            assertFalse(PlayRowKind.Cap in playRowsOf(rig.ui.draft))
        }

    @Test
    fun theSheetListsTheCapRowOnlyWhileTheChannelHasATier() =
        builderTest { rig ->
            val lib = DraftSource.Library("lib1", "Library 1")
            rig.openEdit(draftOf("Mine", listOf(lib), tier = FamilyTier.Kids, number = 40))
            assertTrue(SheetRow.Cap in rig.ui.sheetRows)
            assertEquals(SheetRow.Cap, rig.ui.sheetRows[rig.ui.sheetRows.indexOf(SheetRow.Family) + 1])

            rig.openEdit(draftOf("Mine", listOf(lib)))
            assertFalse(SheetRow.Cap in rig.ui.sheetRows)
            assertTrue(SheetRow.Family in rig.ui.sheetRows)
        }

    @Test
    fun theSheetsCapRowMovesTheTierWithLeftAndRight() =
        builderTest { rig ->
            val lib = DraftSource.Library("lib1", "Library 1")
            rig.openEdit(draftOf("Mine", listOf(lib), tier = FamilyTier.Kids, number = 40))
            val capAt = rig.ui.sheetRows.indexOf(SheetRow.Cap)
            repeat(capAt) { rig.press(Down) }
            assertEquals(BuilderFocus.Sheet(SheetRow.Cap), rig.ui.focus)

            rig.press(Right)

            assertEquals(FamilyTier.Teens, rig.ui.draft.tier)
            assertEquals(67, rig.ui.draft.number)
        }

    @Test
    fun ceilingsFollowTheTier() {
        fun turn(
            rating: String?,
            by: Int,
            tier: FamilyTier?,
        ) = DraftFilters(maxRating = rating).withRating(by, wrap = false, tier = tier).maxRating

        assertEquals("PG", turn("PG", 1, FamilyTier.Kids))
        assertEquals("TV-Y7", turn("PG", -1, FamilyTier.Kids))
        assertEquals("TV-14", turn("TV-14", 1, FamilyTier.Teens))
        assertEquals("PG-13", turn("TV-14", -1, FamilyTier.Teens))
        assertEquals("TV-14", turn("PG-13", 1, FamilyTier.Teens))
        assertNull(turn("TV-X", 1, null))
    }

    @Test
    fun theNewRefusalsHaveWords() {
        assertEquals(BuilderMessage.KidsTierBandFull, refusalMessage(BuildRefusal.TierBandFull(FamilyTier.Kids)))
        assertEquals(BuilderMessage.TeensTierBandFull, refusalMessage(BuildRefusal.TierBandFull(FamilyTier.Teens)))
        assertEquals(BuilderMessage.KidsTierBand, refusalMessage(BuildRefusal.OutsideTierBand(FamilyTier.Kids)))
        assertEquals(BuilderMessage.TeensTierBand, refusalMessage(BuildRefusal.OutsideTierBand(FamilyTier.Teens)))
        assertEquals(BuilderMessage.KidsBandFull, refusalMessage(BuildRefusal.KidsBandFull))
        assertEquals(BuilderMessage.NeedsGeneral, refusalMessage(BuildRefusal.BelowBand(BandNeeded.General)))
    }
}
