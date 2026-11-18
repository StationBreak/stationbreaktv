// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.annotation.StringRes
import tv.stationbreak.core.ui.ChannelChange
import tv.stationbreak.core.ui.stan.StanScene
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.CardNote
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.R

/** One string resource and the arguments it is formatted with: copy is never joined from pieces. */
internal data class Phrase(
    @param:StringRes val id: Int,
    val args: List<Any> = emptyList(),
)

/**
 * What one of Stan's station screens says (DESIGN.md, Station Screen): [headline] is Stan's one line in capitals (two
 * lines are one string with a line break), [status] the plain teletype status, [detail] the grey line under it. [spoken]
 * is what a screen reader is told, once and politely: the plain status, never Stan's joke; null for a screen whose
 * banner says it already. [tag] is the screen's root test tag and [detailTag] the detail line's.
 */
internal data class StanScreenSpec(
    val scene: StanScene,
    val headline: Phrase,
    val status: Phrase,
    val detail: Phrase?,
    val spoken: Phrase?,
    val tag: String,
    val detailTag: String = LiveTags.STAN_DETAIL,
)

/** The standby screen while channel [number] tunes, with the programme's [title] when it is known. */
internal fun tuningSpec(
    number: Int,
    title: String?,
): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.Standby,
        headline = Phrase(R.string.live_stan_standby_headline),
        status = Phrase(R.string.live_stan_tuning_status, listOf(number)),
        detail = title?.takeIf { it.isNotBlank() }?.let { Phrase(R.string.live_stan_programme, listOf(it)) },
        // The banner over the screen says the channel and the programme as a live region; two would repeat it.
        spoken = null,
        tag = LiveTags.STAN_STANDBY,
    )

/** The screen for a show that is missing or will not play. */
internal fun difficultiesSpec(): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.Trouble,
        headline = Phrase(R.string.live_stan_trouble_headline),
        status = Phrase(R.string.live_stan_trouble_status),
        detail = Phrase(R.string.live_stan_trouble_detail),
        spoken = Phrase(R.string.live_stan_spoken_trouble),
        tag = LiveTags.DIFFICULTIES,
    )

/**
 * The off-air screen for a gap, a show that ended or a card with a [note]. Without a note, when [upNext] is known the
 * detail says when the channel returns, in the time [time] writes; without either it has the status alone.
 */
internal fun offAirSpec(
    upNext: Banner?,
    note: CardNote?,
    time: (Long) -> String,
): StanScreenSpec {
    val (detail, spoken) =
        when {
            note == CardNote.NotInLibrary -> {
                Phrase(R.string.live_stan_offair_next) to Phrase(R.string.live_stan_spoken_offair_next)
            }

            note is CardNote.OffTheAir -> {
                Phrase(R.string.live_stan_offair_now_on, listOf(note.nowOn)) to
                    Phrase(R.string.live_stan_spoken_offair_now_on, listOf(note.channelName, note.nowOn))
            }

            upNext != null -> {
                val returns = listOf(upNext.channelName, time(upNext.slotStartMs))
                Phrase(R.string.live_stan_offair_returns, returns) to
                    Phrase(R.string.live_stan_spoken_offair_returns, returns)
            }

            else -> {
                null to Phrase(R.string.live_stan_spoken_offair)
            }
        }
    return StanScreenSpec(
        scene = StanScene.OffAir,
        headline = Phrase(R.string.live_stan_offair_headline),
        status = Phrase(R.string.live_stan_offair_status),
        detail = detail,
        spoken = spoken,
        tag = LiveTags.IDENT,
        detailTag = if (note != null) LiveTags.IDENT_NOTE else LiveTags.STAN_DETAIL,
    )
}

/** True while [state] has no picture and is changing channel: the states the viewer chooses Stan or snow for. */
internal fun isChangingChannel(state: LiveState): Boolean {
    val noPicture = state is LiveState.Choosing && !state.overPicture
    return state is LiveState.Tuning || noPicture
}

/**
 * Stan's screen for [state], or null when [state] has none: the picture, the sign-in hand-over and the two instruction
 * cards (no channels, no libraries) stay as they are, and a channel change shows snow when [change] says so. The
 * difficulties and ident screens are not part of the choice; only the change of channel is.
 */
internal fun stanSpecOf(
    state: LiveState,
    change: ChannelChange,
    time: (Long) -> String,
): StanScreenSpec? =
    when {
        isChangingChannel(state) && change == ChannelChange.Snow -> null
        state is LiveState.Tuning -> tuningSpec(state.banner.number, state.banner.title)
        state is LiveState.Choosing -> if (state.overPicture) null else tuningSpec(state.target.number, null)
        state is LiveState.TechnicalDifficulties -> difficultiesSpec()
        state is LiveState.Ident -> offAirSpec(state.upNext, state.note, time)
        else -> null
    }
