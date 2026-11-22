// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsPanel
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.Choice
import tv.stationbreak.feature.channels.PickerState
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.choicesOf

/** What the screen's root says follows the screen's own focus: the list's line, or the open panel's choice. */
class AnnouncedTest {
    private val id = ChannelId("ch-a")
    private val row =
        ChannelRow(
            id = id,
            number = 6,
            name = "Alpha",
            group = ChannelGroup.On,
            kind = ChannelKind.Starter,
            kid = false,
            optIn = false,
            reason = null,
            hasOverride = false,
        )
    private val base = ChannelsUi.Empty.copy(rows = listOf(row), focus = ChannelsFocus.Row(id))

    @Test
    fun aFocusedRowIsAnnouncedAsThatRow() {
        assertEquals(Announced.Channel(row), announcedOf(base))
    }

    @Test
    fun theHouseholdSwitchCarriesItsState() {
        val ui = base.copy(focus = ChannelsFocus.Household, useMyCollections = false)

        assertEquals(Announced.Household(on = false), announcedOf(ui))
    }

    @Test
    fun newAndResetAreAnnouncedPlainly() {
        assertEquals(Announced.New, announcedOf(base.copy(focus = ChannelsFocus.New, canAddCustom = false)))
        assertEquals(Announced.Reset, announcedOf(base.copy(focus = ChannelsFocus.Reset)))
    }

    @Test
    fun noFocusSaysNothing() {
        assertEquals(Announced.Nothing, announcedOf(base.copy(focus = null)))
    }

    @Test
    fun anOpenPanelAnnouncesItsFocusedChoice_notTheRow() {
        val panel = base.copy(panel = ChannelsPanel.Actions(id), reasonsRead = true)
        val choices = choicesOf(panel)

        choices.indices.forEach { i ->
            val said = announcedOf(panel.copy(choice = i)) as Announced.Pick
            assertEquals(choices[i], said.choice)
        }
    }

    @Test
    fun aFreePanelAnnouncesEveryChoiceItLists() {
        val panel =
            base.copy(
                panel = ChannelsPanel.Actions(id),
                reasonsRead = true,
                isPro = false,
                access = LineupAccess.Editable,
            )
        val said = choicesOf(panel).indices.map { (announcedOf(panel.copy(choice = it)) as Announced.Pick).choice }

        val expected = listOf(Choice.Watch, Choice.SwitchOff, Choice.CopyToCustomise, Choice.ChangeNumber, Choice.About)
        assertEquals(expected, said)
    }

    @Test
    fun theResetQuestionIsHeardWithItsChoice() {
        val said = announcedOf(base.copy(confirmingReset = true)) as Announced.Pick

        assertEquals(Asking.Reset, said.asking)
        assertEquals(Choice.Confirm, said.choice)
    }

    @Test
    fun theNumberPickerAnnouncesTheFocusedDigit() {
        val ui = base.copy(panel = ChannelsPanel.Number(id, 6), picker = PickerState(listOf(0, 0, 6), at = 2))

        assertEquals(Announced.Digit(place = 3, of = 3, value = 6), announcedOf(ui))
    }

    @Test
    fun theBuilderSpeaksForItself() {
        assertEquals(Announced.Nothing, announcedOf(base.copy(builder = BuilderMode.New)))
    }
}
