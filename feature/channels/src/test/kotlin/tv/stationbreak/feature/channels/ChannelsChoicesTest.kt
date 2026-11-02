// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
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

    @Test
    fun pickerStartsZeroPadded() {
        assertEquals(listOf(0, 0, 6), PickerState.of(6).digits)
        assertEquals(listOf(1, 0, 0), PickerState.of(100).digits)
        assertEquals(12, PickerState.of(12).value)
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
        assertEquals(listOf(Choice.Watch), actionsOf(on, LineupAccess.ReadOnly, reasonsRead = true))
        assertEquals(listOf(Choice.Watch), actionsOf(on, LineupAccess.Loading, reasonsRead = true))
    }

    @Test
    fun watchWaitsForTheFirstReadOfTheReasons() {
        assertEquals(
            listOf(Choice.SwitchOff, Choice.ChangeNumber),
            actionsOf(row(ChannelGroup.On), LineupAccess.Editable, reasonsRead = false),
        )
    }

    @Test
    fun editableActionsFollowTheRow() {
        assertEquals(
            listOf(Choice.Watch, Choice.SwitchOff, Choice.ChangeNumber, Choice.DefaultNumber),
            actionsOf(row(ChannelGroup.On, override = true), LineupAccess.Editable, reasonsRead = true),
        )
        assertEquals(
            listOf(Choice.SwitchOn, Choice.ChangeNumber),
            actionsOf(row(ChannelGroup.Shelf), LineupAccess.Editable, reasonsRead = true),
        )
        assertEquals(
            listOf(Choice.SwitchOff, Choice.ChangeNumber),
            actionsOf(
                row(ChannelGroup.On, reason = HiddenReason.EmptyToday),
                LineupAccess.Editable,
                reasonsRead = true,
            ),
        )
        val unusable = row(ChannelGroup.Off, ChannelKind.Unusable)
        assertEquals(emptyList<Choice>(), actionsOf(unusable, LineupAccess.Editable, reasonsRead = true))
    }
}
