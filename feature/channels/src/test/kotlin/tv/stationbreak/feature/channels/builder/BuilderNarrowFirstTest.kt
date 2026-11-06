// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.channels.PanelMove.Down
import tv.stationbreak.feature.channels.PanelMove.Right
import tv.stationbreak.feature.channels.PanelMove.Up

class BuilderNarrowFirstTest {
    @Test
    fun narrowDownIsTheFirstLineOfTheSourcesList() =
        builderTest { rig ->
            rig.picker.libraries = libraries(3)
            rig.openNew()
            rig.press(Right)
            assertEquals(BuilderFocus.Entry("lib1"), rig.ui.focus)
            rig.press(Up)
            assertEquals(BuilderFocus.Narrow, rig.ui.focus)
        }

    @Test
    fun downFromNarrowReachesTheFirstEntry() =
        builderTest { rig ->
            rig.picker.libraries = libraries(3)
            rig.openNew()
            rig.press(Right, Up, Down)
            assertEquals(BuilderFocus.Entry("lib1"), rig.ui.focus)
        }

    @Test
    fun downPastTheLastEntryGoesToNextAndUpFromNextGoesToTheLastEntry() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            rig.press(Right, Down)
            assertEquals(BuilderFocus.Entry("lib2"), rig.ui.focus)
            rig.press(Down)
            assertEquals(BuilderFocus.Next, rig.ui.focus)
            rig.press(Up)
            assertEquals(BuilderFocus.Entry("lib2"), rig.ui.focus)
        }

    @Test
    fun theRowsOfAnOpenNarrowSitUnderNarrowAboveTheEntries() =
        builderTest { rig ->
            rig.picker.libraries = libraries(2)
            rig.openNew()
            rig.press(Right, Up)
            rig.select()
            assertTrue(rig.ui.narrow != null)
            rig.press(Down)
            assertEquals(BuilderFocus.NarrowRow(NarrowRowKind.Genres), rig.ui.focus)
            rig.press(Down, Down, Down, Down, Down)
            assertEquals(BuilderFocus.NarrowRow(NarrowRowKind.LeaveOut), rig.ui.focus)
            rig.press(Down)
            assertEquals(BuilderFocus.Entry("lib1"), rig.ui.focus)
            rig.press(Up)
            assertEquals(BuilderFocus.NarrowRow(NarrowRowKind.LeaveOut), rig.ui.focus)
        }

    @Test
    fun theFilterLinesComeFirstThenTheSearchThenTheEntries() {
        val view =
            NavView(
                step = BuilderStep.Sources,
                tab = SourceKindTab.Pick,
                entryKeys = listOf("a", "b"),
                narrowOpen = true,
                playRows = emptyList(),
                sheetRows = emptyList(),
                nameRows = emptyList(),
            )
        val expected =
            listOf(BuilderFocus.Narrow) +
                NarrowRowKind.entries.map { BuilderFocus.NarrowRow(it) } +
                listOf(BuilderFocus.Search, BuilderFocus.Entry("a"), BuilderFocus.Entry("b"))
        assertEquals(expected, sourceFocusOrder(view))
    }
}
