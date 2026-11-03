// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Up

/** The D-pad paths of the builder (#559): no dead ends, and nothing focusable that does nothing. */
class BuilderFocusFixesTest {
    @Test
    fun downFromThePickTabIsNotADeadEnd() =
        builderTest { rig ->
            rig.openNew()
            repeat(SourceKindTab.entries.size - 1) { rig.press(Down) }
            assertEquals(BuilderFocus.Tab(SourceKindTab.Pick), rig.ui.focus)

            rig.press(Down)

            assertEquals(BuilderFocus.Next, rig.ui.focus)
        }

    @Test
    fun downFromAnEarlierTabStillGoesToTheNextTab() =
        builderTest { rig ->
            rig.openNew()

            rig.press(Down)

            assertEquals(BuilderFocus.Tab(SourceKindTab.Collections), rig.ui.focus)
        }

    @Test
    fun theNextUpRowOfTheSheetIsListedButTheDpadSkipsIt() =
        builderTest { rig ->
            rig.openEdit()
            assertTrue(SheetRow.Preview in rig.ui.sheetRows)
            repeat(rig.ui.sheetRows.indexOf(SheetRow.Number)) { rig.press(Down) }
            assertEquals(BuilderFocus.Sheet(SheetRow.Number), rig.ui.focus)

            rig.press(Down)
            assertEquals(BuilderFocus.Sheet(SheetRow.Save), rig.ui.focus)

            rig.press(Up)
            assertEquals(BuilderFocus.Sheet(SheetRow.Number), rig.ui.focus)
        }
}
