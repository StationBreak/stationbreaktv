// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDefaultsTest {
    private fun library(
        id: String,
        kind: LibraryKind,
    ) = Library(ItemId(id), "Name $id", kind)

    @Test
    fun ticksEveryLibraryExceptHomeVideos() {
        val libraries =
            listOf(
                library("a", LibraryKind.Movies),
                library("b", LibraryKind.HomeVideos),
                library("c", LibraryKind.TvShows),
                library("d", LibraryKind.Mixed),
                library("e", LibraryKind.MusicVideos),
            )

        assertEquals(setOf(ItemId("a"), ItemId("c"), ItemId("d"), ItemId("e")), LibraryDefaults.ticked(libraries))
    }

    @Test
    fun ticksNothingWhenOnlyHomeVideosExist() {
        assertEquals(emptySet(), LibraryDefaults.ticked(listOf(library("b", LibraryKind.HomeVideos))))
    }
}
