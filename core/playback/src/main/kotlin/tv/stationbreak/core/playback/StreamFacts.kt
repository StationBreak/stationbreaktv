// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.TrackFlags
import tv.stationbreak.core.model.flags

/**
 * What a screen may say about the play that just started, without holding the resolver's types (no URL, no path, no
 * player handle): the [route] it took, the [sourceId] of the version that plays, the server indices of the audio and
 * subtitle track in use ([audioIndex], [subtitleIndex]; null for none), and a summary of [every version][sources] the
 * server offered for the item, the playing one among them. Every track and version carries its [SwitchCost], decided in
 * core:playback against the route and the device, so a picker only reads it; [offCost] is the cost of turning subtitles
 * off. Display text and numbers only, so it is safe to carry to the banner and to hold in state; it is not a log line
 * (a language or a title is the library's own text).
 */
data class StreamFacts(
    val route: Route,
    val sourceId: MediaSourceId,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val sources: List<SourceFacts>,
    val offCost: SwitchCost = SwitchCost.Retune,
) {
    /** The playing version's summary; null only for facts built by hand without it. */
    val playing: SourceFacts? get() = sources.firstOrNull { it.id == sourceId }

    /** The audio track in use, when the playing version lists it. */
    val audio: AudioFacts? get() = playing?.audio?.firstOrNull { it.index == audioIndex }

    /** The subtitle track in use, when the playing version lists it. */
    val subtitle: SubtitleFacts? get() = playing?.subtitles?.firstOrNull { it.index == subtitleIndex }
}

/** One version of the item: [name] is the server's name for it (its edition, often), [video] its picture. */
data class SourceFacts(
    val id: MediaSourceId,
    val name: String?,
    val video: VideoFacts?,
    val audio: List<AudioFacts>,
    val subtitles: List<SubtitleFacts>,
    val cost: SwitchCost = SwitchCost.Retune,
)

/** A version's video: the picture [width] by [height] and its [codec] (the vocabulary's name), each null if unknown. */
data class VideoFacts(
    val width: Int?,
    val height: Int?,
    val codec: String?,
)

/** An audio track: its server [index], and what a row for it says. [title] is the server's text for it. */
data class AudioFacts(
    val index: Int,
    val language: String?,
    val codec: String?,
    val channels: Int?,
    val title: String?,
    val isDefault: Boolean,
    val flags: TrackFlags,
    val cost: SwitchCost = SwitchCost.Retune,
    /** Where the player finds this track in the file, for an instant switch on direct play; null when it cannot. */
    val pick: TrackPick? = null,
)

/** Whether a subtitle is text (the player or the server renders it) or a picture (burned in when chosen). */
enum class SubtitleForm { Text, Picture, Other }

/** A subtitle track: its server [index], its [form], and what a row for it says. */
data class SubtitleFacts(
    val index: Int,
    val language: String?,
    val codec: String?,
    val form: SubtitleForm,
    val title: String?,
    val isDefault: Boolean,
    val isExternal: Boolean,
    val flags: TrackFlags,
    val cost: SwitchCost = SwitchCost.Retune,
    /** Where the player finds this container text track, for an instant switch; null for any other. */
    val pick: TrackPick? = null,
)

/**
 * The facts of this play: see [StreamFacts]. The cost of switching to each track of the playing version, and to each
 * version, is decided here with [switchCost]; a result built by hand without the device's [ResolvedPlayback.limits]
 * says [SwitchCost.Retune] for everything, the safe answer.
 */
fun ResolvedPlayback.facts(): StreamFacts {
    val device = limits

    fun cost(target: SwitchTarget): SwitchCost {
        if (device == null) return SwitchCost.Retune
        return switchCost(this, target, device)
    }
    return StreamFacts(
        route = route,
        sourceId = media.id,
        audioIndex = chosenAudio?.index,
        subtitleIndex = chosenSubtitle?.index,
        // The chosen version is always listed, even for a result built without the server's whole answer.
        sources = (sources.ifEmpty { listOf(media) }).map { it.summary(playing = it.id == media.id, ::cost) },
        offCost = cost(SwitchTarget.Subtitle(null)),
    )
}

private fun MediaSource.summary(
    playing: Boolean,
    cost: (SwitchTarget) -> SwitchCost,
): SourceFacts {
    // Only the playing version's tracks can be switched to in place; another version's tracks come with the version.
    fun trackCost(target: SwitchTarget) = if (playing) cost(target) else SwitchCost.Retune
    return SourceFacts(
        id = id,
        name = name?.takeIf { it.isNotBlank() },
        video = streams.firstOrNull { it.kind == StreamKind.VIDEO }?.let { VideoFacts(it.width, it.height, it.codec) },
        audio = streams.filter { it.kind == StreamKind.AUDIO }.map { it.audioFacts(trackCost(SwitchTarget.Audio(it))) },
        subtitles =
            streams.filter { it.kind == StreamKind.SUBTITLE }.map {
                it.subtitleFacts(trackCost(SwitchTarget.Subtitle(it)))
            },
        cost = cost(SwitchTarget.Version(id)),
    )
}

private fun MediaStreamInfo.title(): String? = (title ?: displayTitle)?.takeIf { it.isNotBlank() }

private fun MediaStreamInfo.audioFacts(cost: SwitchCost): AudioFacts =
    AudioFacts(
        index,
        language,
        codec,
        channels,
        title(),
        isDefault,
        flags(),
        cost,
        positionInKind?.let { TrackPick(it, language, codec) },
    )

private fun MediaStreamInfo.subtitleFacts(cost: SwitchCost): SubtitleFacts {
    val form =
        when {
            Codecs.isTextSubtitle(codec) -> SubtitleForm.Text
            Codecs.isImageSubtitle(codec) -> SubtitleForm.Picture
            else -> SubtitleForm.Other
        }
    return SubtitleFacts(
        index = index,
        language = language,
        codec = codec,
        form = form,
        title = title(),
        isDefault = isDefault,
        isExternal = isExternal,
        flags = flags(),
        cost = cost,
        pick = positionInKind?.takeIf { !isExternal && form == SubtitleForm.Text }?.let { pickAt(it) },
    )
}

private fun MediaStreamInfo.pickAt(position: Int) = TrackPick(position, language, codec)
