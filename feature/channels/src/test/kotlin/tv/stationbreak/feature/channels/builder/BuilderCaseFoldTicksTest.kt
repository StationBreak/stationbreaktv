// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.channels.builder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.builder.DraftSource
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Left
import tv.stationbreak.feature.channels.PanelMove.Right

/** A source stored under another spelling of a genre or tag shows ticked (#614): the rules fold names to match. */
class BuilderCaseFoldTicksTest {
    private fun ticks(rig: BuilderRig) = (rig.ui.entries as PickerView.Entries).rows.map { it.ticked }

    @Test
    fun aGenreStoredInAnotherSpellingTicksTheListedEntryAndUnticksIt() =
        builderTest { rig ->
            rig.picker.libraries = libraries(1)
            rig.picker.collections = Listed.Ok(emptyList())
            rig.picker.tags = Listed.Ok(listOf(PickerEntry("Holiday", "Holiday"), PickerEntry("Other", "Other")))
            rig.picker.genres = Listed.Ok(listOf(PickerEntry("Action", "Action"), PickerEntry("Drama", "Drama")))
            val stored = listOf(DraftSource.Genre("action", "action"), DraftSource.Tag("HOLIDAY", "HOLIDAY"))
            rig.openEdit(draftOf("Mine", stored))
            rig.press(Down)
            rig.select()
            assertEquals(BuilderStep.Sources, rig.ui.step)
            rig.press(Down, Down, Right)
            // The Tags tab.
            assertEquals(SourceKindTab.Tags, rig.ui.tab)
            assertEquals(listOf(true, false), ticks(rig))
            rig.press(Left, Down, Right)
            // The Genres tab.
            assertEquals(listOf(true, false), ticks(rig))
            rig.press(Down)
            assertEquals(BuilderFocus.Entry("Action"), rig.ui.focus)
            rig.select()
            assertEquals(listOf(DraftSource.Tag("HOLIDAY", "HOLIDAY")), rig.ui.draft.sources)
            assertEquals(listOf(false, false), ticks(rig))
        }
}
