// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration

/**
 * What the info overlay and the guide's details panel show about one item, fetched on demand: not part of the saved
 * library. The text is the server's, already made safe for display by the adapter (tags, control and direction
 * characters removed, length capped), so a screen may draw it as it is. A missing field is null, or empty for [genres].
 *
 * [officialRating] is the parental rating as the server words it ("PG-13", "TV-MA"). [criticRating] is the critics'
 * score from 0 to 100 and [communityRating] the audience's from 0 to 10; each is null when the server has none, or
 * has one outside that range. [logo] says where the item's logo image is, when it has one; [media] is the short
 * summary of its first media source.
 */
data class ItemDetails(
    val id: ItemId,
    val overview: String?,
    val year: Int?,
    val genres: List<String>,
    val runtime: Duration?,
    val officialRating: String? = null,
    val criticRating: Int? = null,
    val communityRating: Double? = null,
    val logo: ItemLogo? = null,
    val media: MediaSummary? = null,
)

/**
 * An image of the logo kind: the [tag] the server gives it (which changes when the image does, so a cached copy never
 * goes stale) and the item that [owner]s it. An episode has no logo of its own: the owner is its series.
 */
data class ItemLogo(
    val owner: ItemId,
    val tag: String,
)

/**
 * What the first media source of an item is, for the details panel's tags: the picture's size in pixels, the channel
 * count of the audio the player would pick by default, and whether any subtitle track exists. Each size or count is
 * null when the server did not say.
 */
data class MediaSummary(
    val videoWidth: Int?,
    val videoHeight: Int?,
    val audioChannels: Int?,
    val hasSubtitles: Boolean,
)
