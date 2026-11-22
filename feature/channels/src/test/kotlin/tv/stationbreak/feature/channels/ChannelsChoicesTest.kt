// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId

class ChannelsChoicesTest {
    private fun row(
        group: ChannelGroup,
        kind: ChannelKind = ChannelKind.Starter,
        reason: HiddenReason? = null,
        override: Boolean = false,
    ) = ChannelRow(
        id = ChannelId("x"),
        number = 5,
        name = "Test",
        group = group,
        kind = kind,
        kid = false,
        optIn = false,
        reason = reason,
        hasOverride = override,
    )

    private fun proActions(
        row: ChannelRow,
        access: LineupAccess,
    ) = actionsOf(row, access, reasonsRead = true)

    @Test
    fun pickerStartsZeroPadded() {
        assertEquals(listOf(0, 0, 6), PickerState.of(6).digits)
        assertEquals(listOf(1, 0, 0), PickerState.of(100).digits)
        assertEquals(12, PickerState.of(12).value)
    }

    @Test
    fun pickerHasAsManyDigitsAsTheBiggestNumber() {
        assertEquals(999, ChannelNumbers.all.last)
        assertEquals(3, PickerState.of(1).digits.size)
        assertEquals(999, PickerState.of(5_000).value)
    }

    @Test
    fun pickerDigitsWrapAndStayInside() {
        val p = PickerState.of(0)
        assertEquals(listOf(9, 0, 0), p.down().digits)
        assertEquals(listOf(0, 0, 0), p.down().up().digits)
        assertEquals(listOf(0, 9, 0), p.right().down().digits)
        assertEquals(
            2,
            p
                .right()
                .right()
                .right()
                .at,
        )
        assertEquals(0, p.left().at)
        assertEquals(
            989,
            PickerState
                .of(999)
                .copy(at = 1)
                .down()
                .value,
        )
    }

    @Test
    fun onlyWatchWhenTheLineupCannotBeWritten() {
        val on = row(ChannelGroup.On, override = true)
        assertEquals(listOf(Choice.Watch, Choice.About), proActions(on, LineupAccess.ReadOnly))
        assertEquals(listOf(Choice.Watch, Choice.About), proActions(on, LineupAccess.Loading))
    }

    @Test
    fun watchWaitsForTheFirstReadOfTheReasons() {
        assertEquals(
            listOf(Choice.SwitchOff, Choice.CopyToCustomise, Choice.ChangeNumber, Choice.About),
            actionsOf(row(ChannelGroup.On), LineupAccess.Editable, reasonsRead = false),
        )
    }

    @Test
    fun editableActionsFollowTheRow() {
        assertEquals(
            listOf(
                Choice.Watch,
                Choice.SwitchOff,
                Choice.CopyToCustomise,
                Choice.ChangeNumber,
                Choice.DefaultNumber,
                Choice.About,
            ),
            actionsOf(row(ChannelGroup.On, override = true), LineupAccess.Editable, reasonsRead = true),
        )
        assertEquals(
            listOf(Choice.SwitchOn, Choice.CopyToCustomise, Choice.ChangeNumber, Choice.About),
            actionsOf(row(ChannelGroup.Shelf), LineupAccess.Editable, reasonsRead = true),
        )
        assertEquals(
            listOf(Choice.SwitchOff, Choice.CopyToCustomise, Choice.ChangeNumber, Choice.About),
            actionsOf(
                row(ChannelGroup.On, reason = HiddenReason.EmptyToday),
                LineupAccess.Editable,
                reasonsRead = true,
            ),
        )
        val unusable = row(ChannelGroup.Off, ChannelKind.Unusable)
        assertEquals(listOf(Choice.About), actionsOf(unusable, LineupAccess.Editable, reasonsRead = true))
    }

    @Test
    fun aBuiltInChannelOffersCopyToCustomise() {
        val choices = actionsOf(row(ChannelGroup.Shelf), LineupAccess.Editable, reasonsRead = true)
        assertEquals(listOf(Choice.SwitchOn, Choice.CopyToCustomise, Choice.ChangeNumber, Choice.About), choices)
    }

    @Test
    fun aLeftoversChannelOffersNeitherCopyNorEdit() {
        val choices = proActions(row(ChannelGroup.On, ChannelKind.Leftovers), LineupAccess.Editable)
        assertEquals(listOf(Choice.Watch, Choice.SwitchOff, Choice.ChangeNumber, Choice.About), choices)
    }

    @Test
    fun anUnusableChannelOffersNeitherCopyNorEdit() {
        val choices = proActions(row(ChannelGroup.On, ChannelKind.Unusable), LineupAccess.Editable)
        assertEquals(listOf(Choice.Watch, Choice.About), choices)
        val off = proActions(row(ChannelGroup.Off, ChannelKind.Unusable), LineupAccess.Editable)
        assertEquals(listOf(Choice.About), off)
    }

    @Test
    fun aCustomChannelFromANewerAppOffersNeitherCopyNorEdit() {
        val kind = ChannelKind.UnusableCustom
        val choices = actionsOf(row(ChannelGroup.On, kind), LineupAccess.Editable, reasonsRead = true)
        assertEquals(listOf(Choice.Watch, Choice.About), choices)
    }

    @Test
    fun aCustomChannelOffersEdit() {
        val choices = proActions(row(ChannelGroup.On, ChannelKind.Custom), LineupAccess.Editable)
        assertEquals(listOf(Choice.Watch, Choice.SwitchOff, Choice.Edit, Choice.ChangeNumber, Choice.About), choices)
    }

    @Test
    fun readOnlyAccessOffersNoBuilder() {
        for (access in listOf(LineupAccess.ReadOnly, LineupAccess.Loading)) {
            for (kind in listOf(ChannelKind.Starter, ChannelKind.Custom)) {
                val choices = actionsOf(row(ChannelGroup.On, kind), access, reasonsRead = true)
                assertEquals(listOf(Choice.Watch, Choice.About), choices)
            }
        }
    }

    private val reshaping = listOf(Choice.SwitchOn, Choice.SwitchOff, Choice.ChangeNumber, Choice.DefaultNumber)

    @Test
    fun aFreeViewerIsShownEveryChoiceOnABuiltInChannel_theGatedOnesAnswerWithTheProNote() {
        val on = row(ChannelGroup.On, override = true)
        val expected =
            listOf(
                Choice.Watch,
                Choice.SwitchOff,
                Choice.CopyToCustomise,
                Choice.ChangeNumber,
                Choice.DefaultNumber,
                Choice.About,
            )
        assertEquals(expected, actionsOf(on, LineupAccess.Editable, reasonsRead = true))
        reshaping.forEach {
            assertEquals("$it free", ProNeed.Reshape, proNeedOf(it, on, isPro = false, canAddCustom = true))
            assertNull("$it pro", proNeedOf(it, on, isPro = true, canAddCustom = true))
        }
        assertNull(proNeedOf(Choice.Watch, on, isPro = false, canAddCustom = true))
        assertNull(proNeedOf(Choice.About, on, isPro = false, canAddCustom = true))
    }

    @Test
    fun aFreeViewerChangesTheirOwnCustomChannelWithNoNote() {
        val custom = row(ChannelGroup.On, ChannelKind.Custom, override = true).copy(viewerMade = true)
        reshaping.forEach { assertNull("$it", proNeedOf(it, custom, isPro = false, canAddCustom = true)) }
    }

    @Test
    fun aRetiredStarterIsGatedLikeAPlannerChannel() {
        // A starter whose recipe left the catalog is kind Custom but not the viewer's own (#805).
        val retired = row(ChannelGroup.On, ChannelKind.Custom).copy(viewerMade = false)
        assertEquals(ProNeed.Reshape, proNeedOf(Choice.SwitchOff, retired, isPro = false, canAddCustom = true))
    }

    @Test
    fun theChoicesOfTheProNoteAreSeeProAndCancel() {
        val on = row(ChannelGroup.On)
        val ui = ChannelsUi.Empty.copy(rows = listOf(on), panel = ChannelsPanel.NeedsPro(ProNeed.Reshape, on.id))
        assertEquals(listOf(Choice.SeePro, Choice.Cancel), choicesOf(ui))
        assertEquals(0, startChoice(ui.panel, confirmingReset = false))
    }

    @Test
    fun copyToCustomiseAnswersWithTheCapNoteOnlyAtTheCap() {
        val starter = row(ChannelGroup.On)
        val atCap = proNeedOf(Choice.CopyToCustomise, starter, isPro = true, canAddCustom = false)
        assertEquals(ProNeed.CustomLimit, atCap)
        assertNull(proNeedOf(Choice.CopyToCustomise, starter, isPro = true, canAddCustom = true))
        assertNull(proNeedOf(Choice.Watch, starter, isPro = true, canAddCustom = false))
    }
}
