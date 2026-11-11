// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

/**
 * The word before the title on the picture's label while the guide is open (#722). Each is the banner's own label
 * ("Tuning in", "Up next", "Filling in", the switches), except that a banner's "Now" reads "Now playing" here.
 */
enum class PictureLead {
    NowPlaying,
    TuningIn,
    UpNext,
    FillingIn,
    SwitchingAudio,
    SwitchingSubtitles,
    SwitchingVersion,
}

/**
 * What the label along the top of the guide's picture says: the channel (its [number], [channelName] and, for its mark,
 * [recipeKey]) and, when something is on, [lead] and [title]. With no [title] there is no [lead] either, and the label
 * reads the channel's name alone.
 */
data class PictureLabel(
    val number: Int,
    val channelName: String,
    val recipeKey: String?,
    val lead: PictureLead?,
    val title: String?,
) {
    init {
        require((lead == null) == (title == null)) { "A label has a lead exactly when it has a title: $this" }
    }
}

/**
 * The label for what the picture is, or shows in its place, in [state] (#722); null when no channel is named (nothing
 * on the air, no libraries, signed out). [playing] is the banner of the show on, which [state] no longer carries once
 * the banner has lingered away (`Watching(null)`).
 *
 * While the picture plays the label says "Now playing"; during a tune, an ident or a slate the title follows the
 * banner's own label ("Tuning in", "Up next", ...). A card that names no show, and a number still moving, say the
 * channel alone.
 */
internal fun pictureLabelOf(
    state: LiveState,
    playing: Banner?,
): PictureLabel? =
    when (state) {
        is LiveState.Watching -> (state.banner ?: playing)?.labelled(PictureLead.NowPlaying)
        is LiveState.Tuning -> state.banner.labelled(leadOf(state.banner.label))
        is LiveState.Ident -> cardLabel(state.upNext, state.channel)
        is LiveState.TechnicalDifficulties -> cardLabel(state.upNext, state.channel)
        is LiveState.Choosing -> state.target.alone()
        LiveState.NoChannels, LiveState.NoLibraries, LiveState.SignedOut -> null
    }

// A slate or ident: what is up next when it says, else the channel alone.
private fun cardLabel(
    upNext: Banner?,
    channel: ChannelTag?,
): PictureLabel? = upNext?.let { it.labelled(leadOf(it.label)) } ?: channel?.alone()

private fun Banner.labelled(lead: PictureLead): PictureLabel {
    val named = title?.takeIf { it.isNotBlank() }
    return PictureLabel(number, channelName, recipeKey, lead.takeIf { named != null }, named)
}

private fun ChannelTag.alone() = PictureLabel(number, name, recipeKey, lead = null, title = null)

private fun leadOf(label: BannerLabel): PictureLead =
    when (label) {
        BannerLabel.TuningIn -> PictureLead.TuningIn
        BannerLabel.Now -> PictureLead.NowPlaying
        BannerLabel.UpNext -> PictureLead.UpNext
        BannerLabel.FillingIn -> PictureLead.FillingIn
        BannerLabel.SwitchingAudio -> PictureLead.SwitchingAudio
        BannerLabel.SwitchingSubtitles -> PictureLead.SwitchingSubtitles
        BannerLabel.SwitchingVersion -> PictureLead.SwitchingVersion
    }
