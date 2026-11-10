// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import tv.stationbreak.core.model.LanguageCodes
import tv.stationbreak.core.model.TrackFlags
import tv.stationbreak.core.playback.AudioFacts
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.SourceFacts
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SubtitleFacts
import tv.stationbreak.core.playback.SubtitleForm
import tv.stationbreak.core.playback.VideoFacts
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.PickerRow
import tv.stationbreak.feature.live.R
import java.util.Locale

/**
 * The words the stream line and the picker rows are made from, from resources. A plain holder, so the rules in
 * [StreamLabels] are tested on the JVM against the shipped `strings.xml`.
 */
@Suppress("LongParameterList") // One string per resource: a holder, not behaviour.
internal class StreamCopy(
    val transcoding: String,
    val subtitlesOff: String,
    val subtitlesNone: String,
    val subtitles: (language: String) -> String,
    val unknown: String,
    val commentary: String,
    val sdh: String,
    val forced: String,
    val text: String,
    val picture: String,
    val off: String,
    val chipNone: String,
    val serverDefault: String,
    val restarts: String,
    val playing: String,
    val switchFailed: String,
) {
    companion object {
        fun from(resources: Resources) =
            StreamCopy(
                transcoding = resources.getString(R.string.live_stream_transcoding),
                subtitlesOff = resources.getString(R.string.live_stream_subtitles_off),
                subtitlesNone = resources.getString(R.string.live_stream_subtitles_none),
                subtitles = { resources.getString(R.string.live_stream_subtitles, it) },
                unknown = resources.getString(R.string.live_track_unknown),
                commentary = resources.getString(R.string.live_track_commentary),
                sdh = resources.getString(R.string.live_track_sdh),
                forced = resources.getString(R.string.live_track_forced),
                text = resources.getString(R.string.live_track_text),
                picture = resources.getString(R.string.live_track_picture),
                off = resources.getString(R.string.live_track_off),
                chipNone = resources.getString(R.string.live_chip_none),
                serverDefault = resources.getString(R.string.live_picker_server_default),
                restarts = resources.getString(R.string.live_picker_restarts),
                playing = resources.getString(R.string.live_picker_playing),
                switchFailed = resources.getString(R.string.live_switch_failed),
            )
    }
}

/**
 * The banner's stream line (what plays), or null when the tune had no facts to give. After a switch that did not take
 * it says so instead, for the one banner that follows (design doc B2.7).
 */
@Composable
internal fun rememberStreamLine(banner: Banner): String? {
    val resources = LocalContext.current.resources
    return remember(banner) {
        val copy = StreamCopy.from(resources)
        if (banner.switchFailed) copy.switchFailed else banner.stream?.let { StreamLabels.streamLine(it, copy) }
    }
}

/**
 * What a language code is called to the viewer, in [locale]; null when the code says there is no language. A code the
 * platform has no name for is shown as the code in capitals, so a track is never nameless.
 */
internal fun languageName(
    code: String?,
    locale: Locale = Locale.getDefault(),
): String? {
    val canonical = LanguageCodes.canonical(code) ?: return null
    val name = Locale(canonical).getDisplayLanguage(locale)
    val known = name.isNotBlank() && name != canonical
    return if (known) name.replaceFirstChar { it.titlecase(locale) } else canonical.uppercase(Locale.ROOT)
}

/**
 * The text of the stream line and of the picker rows (design doc B1, B2.8). Pure: the language names come in as
 * [language] (the screen passes [languageName]) so a test names them itself.
 */
@Suppress("TooManyFunctions") // One function per piece of text the banner and the pickers say.
internal object StreamLabels {
    const val SEPARATOR = " · "

    /**
     * `1080p HEVC · English 5.1 · Subtitles off`: the picture, the audio in use, the subtitles in use. A transcode
     * starts with "Transcoding · "; direct play and direct stream say nothing of the route. A part the facts do not
     * know is left out, so the line says only what is so.
     */
    fun streamLine(
        facts: StreamFacts,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): String {
        val playing = facts.playing
        val parts =
            listOfNotNull(
                copy.transcoding.takeIf { facts.route == Route.TRANSCODE },
                playing?.video?.let(::pictureOf),
                facts.audio?.let { audioInUse(it, copy, language) },
                subtitlesInUse(facts, playing, copy, language),
            )
        return parts.joinToString(SEPARATOR)
    }

    /** "English · Commentary · 5.1 · AC3": language (or Unknown), then flags, then layout and codec. */
    fun audioRow(
        audio: AudioFacts,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): String =
        (
            listOf(language(audio.language) ?: copy.unknown) + flagWords(audio.flags, copy) +
                listOfNotNull(StreamFigures.layout(audio.channels), StreamFigures.audioCodec(audio.codec))
        ).joinToString(SEPARATOR)

    /** "English · SDH · Text": language (or Unknown), then flags, then Text or Picture. */
    fun subtitleRow(
        subtitle: SubtitleFacts,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): String =
        (
            listOf(language(subtitle.language) ?: copy.unknown) + flagWords(subtitle.flags, copy) +
                listOfNotNull(form(subtitle.form, copy))
        ).joinToString(SEPARATOR)

    /** "1080p · HEVC · Director's Cut": resolution, codec, then the server's name for the version. */
    fun versionRow(
        source: SourceFacts,
        copy: StreamCopy,
    ): String {
        val parts =
            listOfNotNull(
                source.video?.let { StreamFigures.resolution(it.width, it.height) },
                source.video?.codec?.let(StreamFigures::videoCodec),
                source.name,
            )
        return parts.ifEmpty { listOf(copy.unknown) }.joinToString(SEPARATOR)
    }

    /** The first row of the subtitles picker. */
    fun offRow(copy: StreamCopy): String = copy.off

    /** The Audio chip's value: "English 5.1"; null when the facts do not say. */
    fun audioChip(
        facts: StreamFacts,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): String? = facts.audio?.let { audioInUse(it, copy, language) }

    /** The Subtitles chip's value: "None" with no tracks, "Off", or the language in use. */
    fun subtitlesChip(
        facts: StreamFacts,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): String {
        val chosen = facts.subtitle
        return when {
            chosen != null -> language(chosen.language) ?: copy.unknown
            facts.playing?.subtitles?.isEmpty() == true -> copy.chipNone
            else -> copy.off
        }
    }

    /** The Version chip's value: the playing version's picture and name, as its row reads. */
    fun versionChip(
        facts: StreamFacts,
        copy: StreamCopy,
    ): String? = facts.playing?.let { versionRow(it, copy) }

    /** The text of [row] in a picker. */
    fun pickerRow(
        row: PickerRow,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): String =
        when (row) {
            is PickerRow.Audio -> audioRow(row.track, copy, language)
            is PickerRow.Off -> offRow(copy)
            is PickerRow.Subtitle -> subtitleRow(row.track, copy, language)
            is PickerRow.ServerDefault -> copy.serverDefault
        }

    /**
     * The texts of a whole picker, each row told apart from its twins: two rows that read the same take the track's own
     * title after the text, and one with no title (or the same title again) takes " · 2", " · 3" by its place among
     * them. The first of untitled twins stays as it is. A row nobody shares a text with is unchanged.
     */
    fun pickerTexts(
        rows: List<PickerRow>,
        copy: StreamCopy,
        language: (String?) -> String? = { languageName(it) },
    ): List<String> {
        val base = rows.map { pickerRow(it, copy, language) }
        val titled =
            base.mapIndexed { i, text ->
                val title = titleOf(rows[i])
                if (base.count { it == text } > 1 && title != null) text + SEPARATOR + title else text
            }
        val seen = mutableMapOf<String, Int>()
        return titled.map { text ->
            val place = (seen[text] ?: 0) + 1
            seen[text] = place
            if (titled.count { it == text } > 1 && place > 1) text + SEPARATOR + place else text
        }
    }

    private fun titleOf(row: PickerRow): String? =
        when (row) {
            is PickerRow.Audio -> row.track.title
            is PickerRow.Subtitle -> row.track.title
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }

    private fun pictureOf(video: VideoFacts): String? =
        listOfNotNull(StreamFigures.resolution(video.width, video.height), video.codec?.let(StreamFigures::videoCodec))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")

    private fun audioInUse(
        audio: AudioFacts,
        copy: StreamCopy,
        language: (String?) -> String?,
    ): String =
        listOfNotNull(language(audio.language) ?: copy.unknown, StreamFigures.layout(audio.channels))
            .joinToString(" ")

    private fun subtitlesInUse(
        facts: StreamFacts,
        playing: SourceFacts?,
        copy: StreamCopy,
        language: (String?) -> String?,
    ): String? {
        val chosen = facts.subtitle
        return when {
            chosen != null -> copy.subtitles(language(chosen.language) ?: copy.unknown)
            playing != null && playing.subtitles.isEmpty() -> copy.subtitlesNone
            else -> copy.subtitlesOff
        }
    }

    private fun flagWords(
        flags: TrackFlags,
        copy: StreamCopy,
    ): List<String> =
        listOfNotNull(
            copy.commentary.takeIf {
                flags.commentary
            },
            copy.sdh.takeIf { flags.sdh },
            copy.forced.takeIf { flags.forced },
        )

    private fun form(
        form: SubtitleForm,
        copy: StreamCopy,
    ): String? =
        when (form) {
            SubtitleForm.Text -> copy.text
            SubtitleForm.Picture -> copy.picture
            SubtitleForm.Other -> null
        }
}

/**
 * The figures of a stream as the viewer reads them: a resolution tier, a channel layout, a codec's name. Numbers and
 * the vocabulary's codec names only, so nothing here needs a string resource.
 */
internal object StreamFigures {
    private val WIDTH_TIERS = listOf(3600 to 2160, 2400 to 1440, 1800 to 1080, 1200 to 720)
    private val HEIGHT_TIERS = listOf(2000 to 2160, 1300 to 1440, 1000 to 1080, 700 to 720)
    private val LAYOUTS =
        mapOf(1 to "1.0", 2 to "2.0", 3 to "2.1", 4 to "4.0", 5 to "5.0", 6 to "5.1", 7 to "6.1", 8 to "7.1")
    private val VIDEO_CODECS =
        mapOf("hevc" to "HEVC", "h264" to "H.264", "av1" to "AV1", "vp9" to "VP9", "mpeg2video" to "MPEG-2")
    private val AUDIO_CODECS =
        mapOf(
            "truehd" to "TrueHD",
            "opus" to "Opus",
            "flac" to "FLAC",
            "eac3" to "EAC3",
            "ac3" to "AC3",
            "aac" to "AAC",
            "dts" to "DTS",
            "mp3" to "MP3",
        )

    /**
     * "1080p". A film is cropped to its own shape, so the height alone mislabels it (1920 by 800 is 1080p): the
     * tier is the larger of what the width and the height say, and a small picture says its own height.
     */
    fun resolution(
        width: Int?,
        height: Int?,
    ): String? {
        val tier =
            listOfNotNull(
                width?.let { w -> WIDTH_TIERS.firstOrNull { w >= it.first }?.second },
                height?.let { h -> HEIGHT_TIERS.firstOrNull { h >= it.first }?.second },
            ).maxOrNull()
        return (tier ?: height?.takeIf { it > 0 })?.let { "${it}p" }
    }

    /** "5.1": the channel count as a layout; null when the count is not known. */
    fun layout(channels: Int?): String? =
        when {
            channels == null || channels <= 0 -> null
            else -> LAYOUTS[channels] ?: "${channels}ch"
        }

    fun videoCodec(codec: String): String = VIDEO_CODECS[codec.lowercase(Locale.ROOT)] ?: codec.uppercase(Locale.ROOT)

    fun audioCodec(codec: String?): String? {
        val name = codec?.lowercase(Locale.ROOT) ?: return null
        return AUDIO_CODECS[name] ?: name.uppercase(Locale.ROOT)
    }
}
