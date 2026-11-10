// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.tracks.SubtitlePreference
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.LanguageCodes
import tv.stationbreak.core.playback.SourceFacts
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.SubtitleChoice
import tv.stationbreak.core.playback.SubtitleFacts
import tv.stationbreak.core.playback.SubtitleSwitch
import tv.stationbreak.core.playback.SwitchCost
import tv.stationbreak.core.playback.TrackChoice
import tv.stationbreak.core.playback.TrackOverride
import tv.stationbreak.core.playback.TrackSwitch

/** How a viewer's switch of audio or subtitles ended (design doc B2.7, B3). */
internal sealed interface Switched {
    /** It took: in place ([play] is the same play, its facts updated) or by a retune that plays. */
    data class Done(
        val play: TuneOutcome.Playing,
    ) : Switched

    /** The new choice did not play; the previous one was tuned again and does: "Couldn't switch" is said. */
    data class FellBack(
        val play: TuneOutcome.Playing,
    ) : Switched

    /** Neither the new choice nor the old one plays. */
    data object Lost : Switched

    /** The session expired while re-tuning. */
    data object Expired : Switched

    /** The tune was stopped while re-tuning. */
    data object Cancelled : Switched
}

/** What a [TrackSelection] means for the playing facts: the choice to tune with, the in-place switch, and its cost. */
internal class SwitchPlan(
    val choice: TrackChoice,
    val inPlace: TrackSwitch?,
    val cost: SwitchCost,
    val label: BannerLabel,
    val facts: StreamFacts,
    val remember: Remembered?,
)

/**
 * The plan for [selection] on the play whose facts are [facts]; null when the selection names a track the playing
 * version does not have. [now] is the viewer's choice already made for this programme, which a retune keeps: a
 * subtitle the viewer turned off stays off when they change the audio. The cost comes from [facts] (decided in
 * core:playback), so the row's "restarts picture" and what happens here are one answer.
 */
internal fun planSwitch(
    selection: TrackSelection,
    facts: StreamFacts,
    now: TrackOverride?,
): SwitchPlan? {
    val source = facts.playing ?: return null
    val audio = now?.audioIndex ?: facts.audioIndex
    val subtitle = now?.subtitle ?: facts.subtitleIndex?.let { SubtitleChoice.Track(it) } ?: SubtitleChoice.Default
    return when (selection) {
        is TrackSelection.Audio -> planAudio(selection.index, facts, source, subtitle)
        is TrackSelection.Subtitles -> planSubtitles(selection.choice, facts, audio, source.subtitles)
    }
}

private fun planAudio(
    index: Int,
    facts: StreamFacts,
    source: SourceFacts,
    subtitle: SubtitleChoice,
): SwitchPlan? {
    val track = source.audio.firstOrNull { it.index == index } ?: return null
    return SwitchPlan(
        choice = TrackChoice(facts.sourceId, TrackOverride(track.index, subtitle)),
        inPlace = track.pick?.let { TrackSwitch(audio = it) },
        cost = track.cost,
        label = BannerLabel.SwitchingAudio,
        facts = facts.copy(audioIndex = track.index),
        remember =
            Remembered
                .AudioLanguage(LanguageCodes.canonical(track.language))
                .takeIf { !track.flags.neverCarries },
    )
}

private fun planSubtitles(
    choice: SubtitleChoice,
    facts: StreamFacts,
    audio: Int?,
    tracks: List<SubtitleFacts>,
): SwitchPlan? {
    val tune = TrackChoice(facts.sourceId, TrackOverride(audio, choice))
    return when (choice) {
        SubtitleChoice.Off -> {
            SwitchPlan(
                tune,
                TrackSwitch(subtitles = SubtitleSwitch.Off),
                facts.offCost,
                BannerLabel.SwitchingSubtitles,
                facts.copy(subtitleIndex = null),
                Remembered.Subtitles(SubtitlePreference.Off),
            )
        }

        // The server decides again: only a fresh resolve says which track that is, so it always re-tunes.
        SubtitleChoice.Default -> {
            SwitchPlan(
                tune,
                null,
                SwitchCost.Retune,
                BannerLabel.SwitchingSubtitles,
                facts,
                Remembered.Subtitles(null),
            )
        }

        is SubtitleChoice.Track -> {
            val track = tracks.firstOrNull { it.index == choice.index } ?: return null
            val language = LanguageCodes.canonical(track.language)
            val carries = language != null && !track.flags.neverCarries
            SwitchPlan(
                tune,
                track.pick?.let { TrackSwitch(subtitles = SubtitleSwitch.To(it)) },
                track.cost,
                BannerLabel.SwitchingSubtitles,
                facts.copy(subtitleIndex = track.index),
                language?.takeIf { carries }?.let { Remembered.Subtitles(SubtitlePreference.On(it, track.flags.sdh)) },
            )
        }
    }
}

/**
 * Makes the viewer's [selection] on the play [play] of [item], in [block], at media position [atMs]. In place when the
 * facts say it is instant and the player takes it; else a retune at the place under the snow ([retune], labelled
 * "Switching ..."). If the retune does not play, the previous choice is tuned again and "Couldn't switch" is said; if
 * that does not play either the play is lost. A choice that took is the programme's from then on ([onAirChoice], so a
 * stall-retry keeps it) and leaves its language for the next programmes. [onPlaying] is told of a new play as soon as
 * it answers, as for a retry.
 */
@Suppress("LongParameterList", "ReturnCount") // The play and where it is; each return is one way a switch ends.
internal suspend fun Sitting.switchTo(
    selection: TrackSelection,
    play: TuneOutcome.Playing,
    block: AiringBlock,
    item: ItemId,
    atMs: Long,
    retune: Retune,
    onPlaying: (TuneOutcome.Playing) -> Unit,
): Switched {
    val before = choiceFor(block, item)
    val plan = play.facts?.let { planSwitch(selection, it, before?.tracks) } ?: return Switched.Done(play)
    val inPlace = plan.inPlace
    if (plan.cost == SwitchCost.Instant && inPlace != null && play.control.switchTracks(inPlace)) {
        took(plan, block, item)
        state.restream(block, plan.facts)
        return Switched.Done(play.copy(facts = plan.facts))
    }
    return retuneTo(plan, before, block, item, atMs, retune, onPlaying)
}

/** The retune of [switchTo]: the new choice, and if it does not play the previous one, saying so. */
@Suppress("LongParameterList", "ReturnCount") // The plan and where; each return is one way a retune ends.
private suspend fun Sitting.retuneTo(
    plan: SwitchPlan,
    before: TrackChoice?,
    block: AiringBlock,
    item: ItemId,
    atMs: Long,
    retune: Retune,
    onPlaying: (TuneOutcome.Playing) -> Unit,
): Switched {
    // The choice is on the sitting before the tune fires, so the tune reads it ([choiceFor]).
    onAirChoice = OnAirChoice(block.startMs, item, plan.choice)
    val tried = retune.switching(atMs, plan.label, failed = false, onPlaying)
    if (tried is TuneOutcome.Playing) {
        remember(plan)
        return Switched.Done(tried)
    }
    if (tried !is TuneOutcome.Dead) return if (tried == TuneOutcome.Expired) Switched.Expired else Switched.Cancelled
    onAirChoice = before?.let { OnAirChoice(block.startMs, item, it) }
    return when (val back = retune.switching(atMs, plan.label, failed = true, onPlaying)) {
        is TuneOutcome.Playing -> Switched.FellBack(back)
        TuneOutcome.Expired -> Switched.Expired
        TuneOutcome.Cancelled -> Switched.Cancelled
        is TuneOutcome.Dead -> Switched.Lost
    }
}

private suspend fun Sitting.took(
    plan: SwitchPlan,
    block: AiringBlock,
    item: ItemId,
) {
    onAirChoice = OnAirChoice(block.startMs, item, plan.choice)
    remember(plan)
}

private suspend fun Sitting.remember(plan: SwitchPlan) {
    plan.remember?.let { tuner.remember(session, it) }
}
