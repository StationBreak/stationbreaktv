// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.annotation.StringRes
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.stan.StanScene
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.CardNote
import tv.stationbreak.feature.live.ChannelTag
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
 * banner says it already, or only the plain status when a banner over the screen says the rest. [tag] is the
 * screen's root test tag; its lines have their own.
 */
internal data class StanScreenSpec(
    val scene: StanScene,
    val headline: Phrase,
    val status: Phrase,
    val detail: Phrase?,
    val spoken: Phrase?,
    val tag: String,
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

/** The standby screen while the lineup is still being built: no channel to name yet, so the status says why. */
internal fun buildingSpec(): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.Standby,
        headline = Phrase(R.string.live_stan_standby_headline),
        status = Phrase(R.string.live_stan_building_status),
        detail = null,
        // No banner says it: the screen does, once.
        spoken = Phrase(R.string.live_stan_spoken_building),
        tag = LiveTags.STAN_STANDBY,
    )

/** The standby screen while live starts, before anything is chosen: no channel to name, and no banner over it. */
internal fun startingSpec(): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.Standby,
        headline = Phrase(R.string.live_stan_standby_headline),
        status = Phrase(R.string.live_stan_starting_status),
        detail = null,
        // No banner says it: the screen does, once.
        spoken = Phrase(R.string.live_stan_spoken_starting),
        tag = LiveTags.STAN_STARTING,
    )

/** Signed in, but no channel is on the air: Stan shrugs, and the detail says where channels are switched on. */
internal fun noChannelsSpec(): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.NoSignal,
        headline = Phrase(R.string.live_stan_no_channels_headline),
        status = Phrase(R.string.live_stan_no_channels_status),
        detail = Phrase(R.string.live_stan_no_channels_detail),
        spoken = Phrase(R.string.live_stan_spoken_no_channels),
        tag = LiveTags.NO_CHANNELS,
    )

/** None of this TV's libraries is available (spec 26): Stan shrugs, and the detail says where to choose them. */
internal fun noLibrariesSpec(): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.NoSignal,
        headline = Phrase(R.string.live_stan_no_libraries_headline),
        status = Phrase(R.string.live_stan_no_libraries_status),
        detail = Phrase(R.string.live_stan_no_libraries_detail),
        spoken = Phrase(R.string.live_stan_spoken_no_libraries),
        tag = LiveTags.NO_LIBRARIES,
    )

private val PLAIN_TROUBLE = R.string.live_stan_spoken_trouble_status
private val FULL_TROUBLE = R.string.live_stan_spoken_trouble

/** The screen for a show that is missing or will not play; [bannerShown] when a banner says what is next. */
internal fun difficultiesSpec(bannerShown: Boolean = false): StanScreenSpec =
    StanScreenSpec(
        scene = StanScene.Trouble,
        headline = Phrase(R.string.live_stan_trouble_headline),
        status = Phrase(R.string.live_stan_trouble_status),
        detail = Phrase(R.string.live_stan_trouble_detail),
        spoken = Phrase(if (bannerShown) PLAIN_TROUBLE else FULL_TROUBLE),
        tag = LiveTags.DIFFICULTIES,
    )

/**
 * The off-air screen for a gap, a show that ended or a card with a [note]. Without a note, when [upNext] is known the
 * detail says when the channel returns, in the time [time] writes; without either it has the status alone. When a
 * banner is shown ([bannerShown]) it says the rest, so what is spoken is the plain status.
 */
internal fun offAirSpec(
    upNext: Banner?,
    note: CardNote?,
    bannerShown: Boolean,
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
                val spoken =
                    if (bannerShown) {
                        Phrase(R.string.live_stan_spoken_offair)
                    } else {
                        Phrase(R.string.live_stan_spoken_offair_returns, returns)
                    }
                Phrase(R.string.live_stan_offair_returns, returns) to spoken
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
        tag = LiveTags.STAN_OFF_AIR,
    )
}

/** True while [state] has no picture and is changing channel: the states the viewer chooses Stan or snow for. */
internal fun isChangingChannel(state: LiveState): Boolean {
    val noPicture = state is LiveState.Choosing && !state.overPicture
    return state is LiveState.Tuning || noPicture
}

/**
 * Stan's screen for [state], or null when [state] has none: the picture and the sign-in hand-over have none, a start
 * or a lineup still being built has him stand by, no channels or no libraries has him shrug, and a channel change
 * shows snow when [change] says so. The difficulties, ident, starting, building and empty screens are not part of the
 * choice; only the change of channel is. [withBanner] is true on the whole screen, where a banner is over the card,
 * and false in the guide's corner, which has none.
 */
internal fun stanSpecOf(
    state: LiveState,
    change: ChannelChangeStyle,
    withBanner: Boolean,
    time: (Long) -> String,
): StanScreenSpec? =
    when {
        isChangingChannel(state) && change == ChannelChangeStyle.Snow -> {
            null
        }

        state is LiveState.Tuning -> {
            tuningSpec(state.banner.number, state.banner.title)
        }

        state is LiveState.Choosing -> {
            if (state.overPicture) null else tuningSpec(state.target.number, null)
        }

        state is LiveState.TechnicalDifficulties -> {
            difficultiesSpec(withBanner && hasBanner(state.upNext, state.channel))
        }

        state is LiveState.Ident -> {
            offAirSpec(state.upNext, state.note, withBanner && hasBanner(state.upNext, state.channel), time)
        }

        else -> {
            namelessSpecOf(state)
        }
    }

// The screens that name no channel: each of these states has its one. Every state is listed, so a new one has to
// say what it shows; the states [stanSpecOf] handles before this, the picture and the sign-in hand-over have none here.
private fun namelessSpecOf(state: LiveState): StanScreenSpec? =
    when (state) {
        LiveState.Starting -> startingSpec()

        LiveState.BuildingChannels -> buildingSpec()

        LiveState.NoChannels -> noChannelsSpec()

        LiveState.NoLibraries -> noLibrariesSpec()

        is LiveState.Tuning,
        is LiveState.Choosing,
        is LiveState.TechnicalDifficulties,
        is LiveState.Ident,
        is LiveState.Watching,
        LiveState.SignedOut,
        -> null
    }

// A card's banner is the up-next one, or the channel's own strip.
private fun hasBanner(
    upNext: Banner?,
    channel: ChannelTag?,
) = upNext != null || channel != null
