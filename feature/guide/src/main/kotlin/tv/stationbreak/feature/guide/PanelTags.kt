// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.MediaSummary
import java.util.Locale

/** One outlined tag in the details panel's row (#723): plain text, or a score with its label. */
internal sealed interface PanelTag {
    data class Plain(
        val text: String,
    ) : PanelTag

    /** A score: [label] ("Critics") in the quiet colour, then [value] ("68%"). */
    data class Score(
        val label: String,
        val value: String,
    ) : PanelTag
}

/** The words the tags use that a language changes; the rest (SD, HD, 4K, 5.1, 7.1) are the same everywhere. */
internal data class PanelTagCopy(
    val mono: String,
    val stereo: String,
    val closedCaptions: String,
    val critics: String,
    val audience: String,
)

/** The size of a picture, in the words a viewer knows it by. */
internal enum class ResolutionClass(
    val label: String,
) {
    Sd("SD"),
    Hd("HD"),
    Uhd("4K"),
}

/**
 * The tags for [item], in this order and each only when known: the parental rating, the resolution class, the audio
 * layout, "CC" when any subtitle track exists, the critics' score and the audience's. A fact the server did not give
 * is left out, never guessed; an item with no facts has no tags.
 */
internal fun panelTagsOf(
    item: ItemDetails,
    copy: PanelTagCopy,
): List<PanelTag> =
    listOfNotNull(
        item.officialRating?.let { PanelTag.Plain(it) },
        item.media?.let(::resolutionOf)?.let { PanelTag.Plain(it.label) },
        item.media
            ?.audioChannels
            ?.let { audioLayoutOf(it, copy) }
            ?.let { PanelTag.Plain(it) },
        if (item.media?.hasSubtitles == true) PanelTag.Plain(copy.closedCaptions) else null,
        item.criticRating?.let { PanelTag.Score(copy.critics, "$it%") },
        item.communityRating?.let { PanelTag.Score(copy.audience, String.format(Locale.ROOT, "%.1f", it)) },
    )

/**
 * The class of a picture. By either side, because a wide film is letterboxed into a short frame: a 1920 by 800 picture
 * is HD and a 3840 by 1600 one is 4K, though neither is as tall as its class. Null when the server gave no size.
 */
internal fun resolutionOf(media: MediaSummary): ResolutionClass? {
    val width = media.videoWidth ?: 0
    val height = media.videoHeight ?: 0
    return when {
        width == 0 && height == 0 -> null
        width >= UHD_WIDTH || height >= UHD_HEIGHT -> ResolutionClass.Uhd
        width >= HD_WIDTH || height >= HD_HEIGHT -> ResolutionClass.Hd
        else -> ResolutionClass.Sd
    }
}

/** The layout name of [channels]: mono, stereo, 5.1 or 7.1; any other count has no name here, so no tag. */
internal fun audioLayoutOf(
    channels: Int,
    copy: PanelTagCopy,
): String? =
    when (channels) {
        MONO -> copy.mono
        STEREO -> copy.stereo
        FIVE_ONE -> "5.1"
        SEVEN_ONE -> "7.1"
        else -> null
    }

private const val UHD_WIDTH = 3_000
private const val UHD_HEIGHT = 2_000
private const val HD_WIDTH = 1_200
private const val HD_HEIGHT = 700

// The channel counts that have a name: 5.1 is six channels (with the low-frequency one) and 7.1 is eight.
private const val MONO = 1
private const val STEREO = 2
private const val FIVE_ONE = 6
private const val SEVEN_ONE = 8
