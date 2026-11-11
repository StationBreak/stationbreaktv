// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.playback.AudioFacts
import tv.stationbreak.core.playback.SourceFacts
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SubtitleChoice
import tv.stationbreak.core.playback.SubtitleFacts
import tv.stationbreak.core.playback.SwitchCost

/**
 * One row of a picker: what choosing it does ([selection]), whether it is what plays now ([playing]), and whether
 * choosing it restarts the picture ([restarts], from the same [SwitchCost] the behaviour follows). The wording is the
 * screen's: this holds the facts, so the rows and the switch cannot disagree.
 */
internal sealed interface PickerRow {
    val selection: TrackSelection
    val playing: Boolean
    val restarts: Boolean

    data class Audio(
        val track: AudioFacts,
        override val playing: Boolean,
    ) : PickerRow {
        override val selection: TrackSelection = TrackSelection.Audio(track.index)
        override val restarts: Boolean = track.cost == SwitchCost.Retune
    }

    /** Subtitles off. */
    data class Off(
        override val playing: Boolean,
        val cost: SwitchCost,
    ) : PickerRow {
        override val selection: TrackSelection = TrackSelection.Subtitles(SubtitleChoice.Off)
        override val restarts: Boolean = cost == SwitchCost.Retune
    }

    data class Subtitle(
        val track: SubtitleFacts,
        override val playing: Boolean,
    ) : PickerRow {
        override val selection: TrackSelection = TrackSelection.Subtitles(SubtitleChoice.Track(track.index))
        override val restarts: Boolean = track.cost == SwitchCost.Retune
    }

    /** The way back: the server decides again, and the viewer's own subtitle choice is forgotten. */
    data class ServerDefault(
        override val playing: Boolean = false,
    ) : PickerRow {
        override val selection: TrackSelection = TrackSelection.Subtitles(SubtitleChoice.Default)

        // What the server would show may differ from what plays, so it is a restart unless nothing would change.
        override val restarts: Boolean = true
    }

    /** One version of the item (#630): choosing another one always re-tunes ([SourceFacts.cost]). */
    data class Version(
        val source: SourceFacts,
        override val playing: Boolean,
    ) : PickerRow {
        override val selection: TrackSelection = TrackSelection.Version(source.id)
        override val restarts: Boolean = source.cost == SwitchCost.Retune
    }
}

/**
 * True when the chip for [kind] can open a picker (design doc B2.8): more than one audio track to choose from, any
 * subtitle track at all, or more than one version. With nothing to choose, the chip shows but is not focusable.
 */
internal fun StreamFacts?.canPick(kind: PickerKind): Boolean {
    val facts = this ?: return false
    return when (kind) {
        PickerKind.Audio -> (facts.playing?.audio?.size ?: 0) > 1
        PickerKind.Subtitles -> facts.playing?.subtitles?.isNotEmpty() == true
        PickerKind.Version -> facts.sources.size > 1
    }
}

/** The audio rows of the playing version, in the server's order. */
internal fun StreamFacts.audioRows(): List<PickerRow> =
    playing?.audio.orEmpty().map { PickerRow.Audio(it, playing = it.index == audioIndex) }

/**
 * The subtitle rows: Off first (design doc B2.8), then every track of the playing version, then the way back to "the
 * server decides". The playing row is the one in use; with none in use that is Off.
 */
internal fun StreamFacts.subtitleRows(): List<PickerRow> =
    buildList {
        add(PickerRow.Off(playing = subtitleIndex == null, cost = offCost))
        playing?.subtitles.orEmpty().forEach { add(PickerRow.Subtitle(it, playing = it.index == subtitleIndex)) }
        add(PickerRow.ServerDefault())
    }

/** The version rows: every version the server offered, in its order; the playing one is marked. */
internal fun StreamFacts.versionRows(): List<PickerRow> {
    val now = sourceId
    return sources.map { PickerRow.Version(it, playing = it.id == now) }
}

/** The rows of [kind]'s picker for [facts]. */
internal fun pickerRows(
    facts: StreamFacts,
    kind: PickerKind,
): List<PickerRow> =
    when (kind) {
        PickerKind.Audio -> facts.audioRows()
        PickerKind.Subtitles -> facts.subtitleRows()
        PickerKind.Version -> facts.versionRows()
    }
