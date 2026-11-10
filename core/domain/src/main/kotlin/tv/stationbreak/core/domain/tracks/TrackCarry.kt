// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.tracks

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.LanguageCodes
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.flags

/** What the carried preferences say to do about the subtitles of one programme. */
sealed interface SubtitleCarry {
    /** Nothing carries: the server's default applies, silently. */
    data object ServerDefault : SubtitleCarry

    /** The viewer's choice is off: no subtitles, whatever the server's default. */
    data object Off : SubtitleCarry

    /** Show this text subtitle track (the server's stream [index]). */
    data class Track(
        val index: Int,
    ) : SubtitleCarry
}

/** What carries onto one programme: an audio track ([audioIndex], null for the server's default) and the subtitles. */
data class CarriedTracks(
    val audioIndex: Int?,
    val subtitles: SubtitleCarry,
) {
    /** True when nothing carries, so the tune needs no override and the server's own defaults stand. */
    val isServerDefault: Boolean get() = audioIndex == null && subtitles == SubtitleCarry.ServerDefault
}

/**
 * The carry-over rules (design doc B2.6). What carries is the language, applied when the next programme has a track in
 * it; no match leaves the server's default, silently (the stream line shows what plays). Commentary, forced and audio
 * description tracks never carry. A subtitle preference that says SDH picks an SDH track in the language and falls back
 * to a plain one; one that does not picks a plain track and falls back to an SDH one. Subtitles off does not remove a
 * forced track: when the source's default subtitle is forced, the server's default stands.
 */
object TrackCarry {
    /** The tracks of [source] that [preferences] choose. Pure: the same preferences and source give the same answer. */
    fun tracksFor(
        preferences: TrackPreferences,
        source: MediaSource,
    ): CarriedTracks =
        CarriedTracks(
            audioIndex = preferences.audioLanguage?.let { audio(it, source) },
            subtitles = preferences.subtitles?.let { subtitles(it, source) } ?: SubtitleCarry.ServerDefault,
        )

    /** The version remembered for the item, if the item still offers it; null leaves the server's order to choose. */
    fun version(
        remembered: MediaSourceId?,
        sources: List<MediaSource>,
    ): MediaSourceId? = remembered?.takeIf { id -> sources.any { it.id == id } }

    /**
     * The audio track in [language]: the default-flagged one, else the first. Commentary, audio description and any
     * other flagged track never carries, however it is named.
     */
    private fun audio(
        language: String,
        source: MediaSource,
    ): Int? =
        source.streams
            .filter { it.kind == StreamKind.AUDIO && it.carries(language) }
            .pickDefaultOrFirst()
            ?.index

    private fun subtitles(
        choice: SubtitlePreference,
        source: MediaSource,
    ): SubtitleCarry =
        when (choice) {
            SubtitlePreference.Off -> {
                // Off does not remove a forced track: it carries only the foreign-language lines, and the viewer who
                // turned subtitles off still needs them. When the server's default is one, it stands.
                val forcedDefault = source.defaultSubtitle()?.flags()?.forced == true
                if (forcedDefault) SubtitleCarry.ServerDefault else SubtitleCarry.Off
            }

            is SubtitlePreference.On -> {
                // Text tracks only: a picture subtitle is burned in, which turns direct play into a transcode, so a
                // preference never picks one. The server's own default may be a picture one; then it stands.
                val inLanguage =
                    source.streams
                        .filter { it.kind == StreamKind.SUBTITLE && Codecs.isTextSubtitle(it.codec) }
                        .filter { it.carries(choice.language) }
                // Each kind of viewer prefers their own track and takes the other kind in that language before none.
                val (sdh, plain) = inLanguage.partition { it.flags().sdh }
                val ordered = if (choice.hearingImpaired) listOf(sdh, plain) else listOf(plain, sdh)
                val picked = ordered.firstNotNullOfOrNull { it.pickDefaultOrFirst() }
                picked?.let { SubtitleCarry.Track(it.index) } ?: SubtitleCarry.ServerDefault
            }
        }

    private fun MediaSource.defaultSubtitle(): MediaStreamInfo? =
        streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.index == defaultSubtitleIndex }

    private fun MediaStreamInfo.carries(wanted: String): Boolean {
        val sameLanguage = LanguageCodes.same(language, wanted)
        return sameLanguage && !flags().neverCarries
    }

    private fun List<MediaStreamInfo>.pickDefaultOrFirst(): MediaStreamInfo? {
        val flagged = firstOrNull { it.isDefault }
        return flagged ?: firstOrNull()
    }
}
