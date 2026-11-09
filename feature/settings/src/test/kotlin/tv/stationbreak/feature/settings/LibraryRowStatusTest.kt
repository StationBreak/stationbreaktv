// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/** The line under a row: an untick saving says Removing, a tick saving says Saving, a stored add still read, Adding. */
class LibraryRowStatusTest {
    private fun row(
        ticked: Boolean,
        adding: Boolean = false,
        pending: Boolean = false,
    ) = LibraryRow(ItemId("lib-alpha"), "Alpha", ticked, adding, pending)

    @Test
    fun anUntickNotStored_isRemoving() = assertEquals(RowStatus.Removing, row(ticked = false, pending = true).status)

    @Test
    fun aTickNotStored_isSaving() = assertEquals(RowStatus.Saving, row(ticked = true, pending = true).status)

    @Test
    fun aStoredAddStillRead_isAdding() = assertEquals(RowStatus.Adding, row(ticked = true, adding = true).status)

    @Test
    fun aStoredRow_saysNothing() {
        assertNull(row(ticked = true).status)
        assertNull(row(ticked = false).status)
    }
}
