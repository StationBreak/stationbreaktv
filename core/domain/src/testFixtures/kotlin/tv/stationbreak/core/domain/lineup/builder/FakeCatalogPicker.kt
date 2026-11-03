// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.TitleHit

/** A [CatalogPicker] for feature tests: it answers as told and records the searches it got. */
class FakeCatalogPicker : CatalogPicker {
    var libraries: Listed<List<PickerEntry>> = Listed.Ok(emptyList())
    var collections: List<PickerEntry> = emptyList()
    var tags: List<PickerEntry> = emptyList()
    var genres: List<PickerEntry> = emptyList()

    /** What [search] answers; every search gets all of them. */
    var hits: List<TitleHit> = emptyList()

    /** The texts searched for, in order. */
    val searches = mutableListOf<String>()

    override suspend fun libraries(account: LibraryAccount) = libraries

    override suspend fun collections(account: LibraryAccount) = collections

    override suspend fun tags(account: LibraryAccount) = tags

    override suspend fun genres(account: LibraryAccount) = genres

    override suspend fun search(
        account: LibraryAccount,
        text: String,
    ): List<TitleHit> {
        searches += text
        return hits
    }
}
