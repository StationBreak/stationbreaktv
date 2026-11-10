// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.tracks

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId

/** Whether the viewer wants subtitles, and in which language. */
sealed interface SubtitlePreference {
    /** The viewer turned subtitles off. */
    data object Off : SubtitlePreference

    /**
     * The viewer wants subtitles in [language] (a [tv.stationbreak.core.model.LanguageCodes.canonical] code), and
     * [hearingImpaired] says the track they chose was an SDH one: the next programme then prefers an SDH track in that
     * language and falls back to a plain one.
     */
    data class On(
        val language: String,
        val hearingImpaired: Boolean = false,
    ) : SubtitlePreference
}

/**
 * What carries from one programme to the next: the audio [audioLanguage] and the [subtitles] choice, both null until
 * the viewer has chosen (then the server's defaults apply, ADR-0016 decision 6). Languages are canonical codes.
 */
data class TrackPreferences(
    val audioLanguage: String? = null,
    val subtitles: SubtitlePreference? = null,
)

/**
 * What a viewer chose while watching, kept on this device, one record per [LibraryAccount] (design doc B2.5). It is
 * **never written back to Jellyfin**: that would change the viewer's other apps. Audio and subtitle choices are kept
 * as a language (and subtitles on or off), not as a track, because a track means nothing on the next programme; a
 * version is kept per item, the most recent [MAX_VERSIONS] of them. Faults are `null` and `false`, never exceptions,
 * and nothing is logged.
 */
interface TrackChoiceStore {
    /** The account's carried preferences; empty when none are saved or the saved record can't be read. */
    suspend fun preferences(account: LibraryAccount): TrackPreferences

    /**
     * Remembers [language] as the audio language to carry, replacing any earlier one; null clears it (a track with no
     * language leaves nothing to carry). False when it could not be stored.
     */
    suspend fun saveAudioLanguage(
        account: LibraryAccount,
        language: String?,
    ): Boolean

    /** Remembers the subtitle choice, replacing any earlier one. False when it could not be stored. */
    suspend fun saveSubtitles(
        account: LibraryAccount,
        choice: SubtitlePreference,
    ): Boolean

    /** The version the account last chose for [item], or null when none is remembered. */
    suspend fun version(
        account: LibraryAccount,
        item: ItemId,
    ): MediaSourceId?

    /** Remembers [source] as [item]'s version, replacing any earlier one. False when it could not be stored. */
    suspend fun saveVersion(
        account: LibraryAccount,
        item: ItemId,
        source: MediaSourceId,
    ): Boolean

    companion object {
        /** How many items' versions are remembered: the most recently chosen ones. */
        const val MAX_VERSIONS = 200
    }
}

/**
 * The remembered versions as an ordered list, oldest first, for a store to keep. [with] puts an item at the recent end
 * (replacing its earlier entry) and drops the oldest beyond [TrackChoiceStore.MAX_VERSIONS].
 */
class VersionMemory(
    val entries: List<Pair<ItemId, MediaSourceId>> = emptyList(),
) {
    operator fun get(item: ItemId): MediaSourceId? = entries.lastOrNull { it.first == item }?.second

    fun with(
        item: ItemId,
        source: MediaSourceId,
    ): VersionMemory {
        val others = entries.filterNot { it.first == item }
        return VersionMemory((others + (item to source)).takeLast(TrackChoiceStore.MAX_VERSIONS))
    }
}
