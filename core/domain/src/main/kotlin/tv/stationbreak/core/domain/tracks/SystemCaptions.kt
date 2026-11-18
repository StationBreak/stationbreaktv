// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.tracks

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.LanguageCodes

/** The TV's own captioning setting (Android's system captions, design doc B2.2). A port: Android supplies it. */
interface SystemCaptions {
    /** True when the viewer turned captions on in the TV's settings. */
    fun enabled(): Boolean

    /** The language the TV's caption setting names, or the device's own when it names none; null when unknown. */
    fun language(): String?
}

/**
 * [TrackChoiceStore] with the TV's caption setting as the starting subtitle choice (#725, design doc B2.2). While the
 * viewer has never chosen subtitles on this TV, system captions being on reads as subtitles on in the caption
 * language. A choice the viewer made, on or off, always wins: the system setting only seeds, never overrides, and
 * nothing is written for it, so turning system captions off again leaves no trace. The caption style (size, colours)
 * is not followed.
 */
class SystemCaptionSeededChoices(
    private val store: TrackChoiceStore,
    private val captions: SystemCaptions,
) : TrackChoiceStore by store {
    override suspend fun preferences(account: LibraryAccount): TrackPreferences {
        val saved = store.preferences(account)
        val seed =
            if (saved.subtitles == null && captions.enabled()) LanguageCodes.canonical(captions.language()) else null
        return if (seed == null) saved else saved.copy(subtitles = SubtitlePreference.On(seed))
    }
}
