// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.TitleHit
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.foldNameAscii

/** One row of a picker: the [key] the rules store and the [label] the viewer sees. */
data class PickerEntry(
    val key: String,
    val label: String,
)

/** What a read that may fail has: its [value], or nothing to show right now. */
sealed interface Listed<out T> {
    data class Ok<T>(
        val value: T,
    ) : Listed<T>

    data object Unavailable : Listed<Nothing>
}

/** What the channel builder's source pickers read (spec 22). Nothing here throws, but for cancellation. */
interface CatalogPicker {
    /** The server's libraries, by the names the server gives them; [Listed.Unavailable] when it can't be reached. */
    suspend fun libraries(account: LibraryAccount): Listed<List<PickerEntry>>

    /** The account's collections, sorted by folded name. */
    suspend fun collections(account: LibraryAccount): List<PickerEntry>

    suspend fun tags(account: LibraryAccount): List<PickerEntry>

    suspend fun genres(account: LibraryAccount): List<PickerEntry>

    /** Shows and films whose name contains [text], from the saved library only (2 to 64 code points, trimmed). */
    suspend fun search(
        account: LibraryAccount,
        text: String,
    ): List<TitleHit>
}

/**
 * The pickers over the saved library ([repository]) and the server's libraries. [serverLibraries] gives the server's
 * libraries for the account, or null when they can't be read (no session, the server unreachable); [chosen] gives the
 * libraries the viewer chose to sync, or null when none is stored, which filters nothing.
 */
class LibraryCatalogPicker(
    private val repository: LibraryRepository,
    private val serverLibraries: suspend (LibraryAccount) -> List<Library>?,
    private val chosen: suspend () -> Set<ItemId>? = { null },
) : CatalogPicker {
    override suspend fun libraries(account: LibraryAccount): Listed<List<PickerEntry>> {
        val all = serverLibraries(account) ?: return Listed.Unavailable
        val only = chosen()
        val shown = all.filter { only == null || it.id in only }
        return Listed.Ok(shown.map { PickerEntry(it.id.value, it.name) }.sortedByName())
    }

    override suspend fun collections(account: LibraryAccount): List<PickerEntry> {
        val names = repository.collectionNames(account)
        return names.ids.mapNotNull { id -> names[id]?.let { PickerEntry(id.value, it) } }.sortedByName()
    }

    override suspend fun tags(account: LibraryAccount) = repository.distinctTags(account).asEntries()

    override suspend fun genres(account: LibraryAccount) = repository.distinctGenres(account).asEntries()

    override suspend fun search(
        account: LibraryAccount,
        text: String,
    ): List<TitleHit> {
        val trimmed = text.trim()
        val points = trimmed.codePointCount(0, trimmed.length)
        if (points !in MIN_SEARCH..MAX_SEARCH) return emptyList()
        return repository.searchTitles(account, trimmed, SEARCH_LIMIT)
    }

    private fun List<String>.asEntries(): List<PickerEntry> = map { PickerEntry(it, it) }

    private fun List<PickerEntry>.sortedByName(): List<PickerEntry> =
        sortedWith(compareBy({ foldNameAscii(it.label) }, { it.label }, { it.key }))

    private companion object {
        const val MIN_SEARCH = 2
        const val MAX_SEARCH = 64
        const val SEARCH_LIMIT = 30
    }
}

/**
 * The words for the sources of a channel being opened: a library by the server's name, a collection by its name, a
 * show or a picked film by its title. A source the saved library no longer has (or a server that can't be reached)
 * gets its key, so nothing is blank.
 */
class SourceLabels(
    private val repository: LibraryRepository,
    private val libraries: suspend (LibraryAccount) -> List<Library>?,
) {
    suspend operator fun invoke(
        account: LibraryAccount,
        sources: List<DraftSource>,
    ): Map<DraftSource, String> {
        val library = if (sources.any { it is DraftSource.Library }) libraries(account).orEmpty() else emptyList()
        val libraryNames = library.associate { it.id.value to it.name }
        val collections = repository.collectionNames(account)
        val titleIds = sources.filter { it is DraftSource.Show || it is DraftSource.Pick }.mapNotNull { id(it) }.toSet()
        val titles = if (titleIds.isEmpty()) emptyMap() else repository.titleNames(account, titleIds)
        return sources.associateWith { source ->
            val named =
                when (source) {
                    is DraftSource.Library -> libraryNames[source.key]
                    is DraftSource.Collection -> id(source)?.let { collections[it] }
                    is DraftSource.Show, is DraftSource.Pick -> id(source)?.let { titles[it] }
                    is DraftSource.Tag, is DraftSource.Genre -> source.key
                }
            named ?: source.key
        }
    }

    private fun id(source: DraftSource): CanonicalId? = CanonicalId.parseOrNull(source.key)
}
