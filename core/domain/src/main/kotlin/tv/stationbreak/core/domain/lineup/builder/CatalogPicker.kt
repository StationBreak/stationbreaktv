// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
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

/**
 * What the channel builder's source pickers read (spec 22). Nothing here throws, but for cancellation: a read that
 * fails is [Listed.Unavailable], and an empty list is [Listed.Ok] of nothing.
 */
interface CatalogPicker {
    companion object {
        /** How many code points a title search takes, trimmed; text of any other length finds nothing. */
        val SEARCH_LENGTH: IntRange = 2..64
    }

    /** The server's libraries, by the names the server gives them; [Listed.Unavailable] when it can't be reached. */
    suspend fun libraries(account: LibraryAccount): Listed<List<PickerEntry>>

    /** The account's collections, sorted by folded name. */
    suspend fun collections(account: LibraryAccount): Listed<List<PickerEntry>>

    suspend fun tags(account: LibraryAccount): Listed<List<PickerEntry>>

    suspend fun genres(account: LibraryAccount): Listed<List<PickerEntry>>

    /**
     * Shows and films whose name contains [text], from the saved library only, sorted by folded name. [text] is [SEARCH_LENGTH]
     * code points, trimmed; anything else finds nothing.
     */
    suspend fun search(
        account: LibraryAccount,
        text: String,
    ): Listed<List<TitleHit>>
}

/**
 * The pickers over the saved library ([repository]) and the server's libraries. [serverLibraries] gives the server's
 * libraries for the account, or null when they can't be read (no session, the server unreachable); [chosen] gives the
 * libraries the viewer chose to sync, or null when none is stored, which filters nothing. A read that throws is
 * [Listed.Unavailable].
 */
class LibraryCatalogPicker(
    private val repository: LibraryRepository,
    private val serverLibraries: suspend (LibraryAccount) -> List<Library>?,
    private val chosen: suspend () -> Set<ItemId>? = { null },
) : CatalogPicker {
    override suspend fun libraries(account: LibraryAccount): Listed<List<PickerEntry>> =
        guarded {
            val all = serverLibraries(account)
            val only = chosen()
            all?.filter { only == null || it.id in only }?.map { PickerEntry(it.id.value, it.name) }?.sortedByName()
        }

    override suspend fun collections(account: LibraryAccount): Listed<List<PickerEntry>> =
        guarded {
            val names = repository.collectionNames(account)
            names.ids.mapNotNull { id -> names[id]?.let { PickerEntry(id.value, it) } }.sortedByName()
        }

    override suspend fun tags(account: LibraryAccount) = guarded { repository.distinctTags(account).asEntries() }

    override suspend fun genres(account: LibraryAccount): Listed<List<PickerEntry>> =
        guarded { repository.distinctGenres(account).asEntries() }

    override suspend fun search(
        account: LibraryAccount,
        text: String,
    ): Listed<List<TitleHit>> {
        val trimmed = text.trim()
        val points = trimmed.codePointCount(0, trimmed.length)
        if (points !in CatalogPicker.SEARCH_LENGTH) return Listed.Ok(emptyList())
        return guarded {
            repository
                .searchTitles(account, trimmed, SEARCH_LIMIT)
                .sortedWith(compareBy({ foldNameAscii(it.name) }, { it.name }, { it.id.value }))
        }
    }

    // A null is nothing to show right now (the server is out of reach); a throw is the same, but for cancellation.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(read: suspend () -> T?): Listed<T> =
        try {
            read()?.let { Listed.Ok(it) } ?: Listed.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Listed.Unavailable
        }

    private fun List<String>.asEntries(): List<PickerEntry> = map { PickerEntry(it, it) }

    private fun List<PickerEntry>.sortedByName(): List<PickerEntry> =
        sortedWith(compareBy({ foldNameAscii(it.label) }, { it.label }, { it.key }))

    private companion object {
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
