// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.tracks

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId

/** A [TrackChoiceStore] in memory for tests. It can be told to fail a save, and never throws for a fault. */
class InMemoryTrackChoiceStore : TrackChoiceStore {
    private val preferences = mutableMapOf<LibraryAccount, TrackPreferences>()
    private val versions = mutableMapOf<LibraryAccount, VersionMemory>()

    var failSave = false

    override suspend fun preferences(account: LibraryAccount): TrackPreferences {
        val saved = preferences[account]
        return saved ?: TrackPreferences()
    }

    override suspend fun saveAudioLanguage(
        account: LibraryAccount,
        language: String?,
    ): Boolean = save { preferences[account] = preferences(account).copy(audioLanguage = language) }

    override suspend fun saveSubtitles(
        account: LibraryAccount,
        choice: SubtitlePreference,
    ): Boolean = save { preferences[account] = preferences(account).copy(subtitles = choice) }

    override suspend fun version(
        account: LibraryAccount,
        item: ItemId,
    ): MediaSourceId? = versions[account]?.get(item)

    override suspend fun saveVersion(
        account: LibraryAccount,
        item: ItemId,
        source: MediaSourceId,
    ): Boolean = save { versions[account] = (versions[account] ?: VersionMemory()).with(item, source) }

    private inline fun save(write: () -> Unit): Boolean {
        if (failSave) return false
        write()
        return true
    }
}
