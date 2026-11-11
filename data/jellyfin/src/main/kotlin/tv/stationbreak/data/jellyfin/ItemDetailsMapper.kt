// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.model.MediaSummary
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Turns the details answer into [ItemDetails]. Everything here is untrusted server input: text goes through
 * [ServerText], numbers outside their range are dropped, and an image tag that is not the usual short run of letters,
 * digits, '-' and '_' is no logo. An odd value drops its own detail and never the whole answer.
 */
internal object ItemDetailsMapper {
    private const val CRITIC_MAX = 100.0
    private const val COMMUNITY_MAX = 10.0

    fun map(
        itemId: ItemId,
        dto: ItemDetailsDto,
        runtime: (Long) -> Duration?,
    ): ItemDetails =
        ItemDetails(
            id = itemId,
            overview = ServerText.overview(dto.overview),
            year = dto.productionYear,
            genres = ServerText.genres(dto.genres),
            runtime = dto.runTimeTicks?.let(runtime),
            officialRating = ServerText.rating(dto.officialRating),
            criticRating = number(dto.criticRating, CRITIC_MAX)?.roundToInt(),
            communityRating = number(dto.communityRating, COMMUNITY_MAX),
            logo = logo(itemId, dto),
            media = media(dto),
        )

    /** [element] as a number from 0 to [max], or null: a score outside that, or not a number, is not shown. */
    private fun number(
        element: JsonElement?,
        max: Double,
    ): Double? = (element as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..max }

    // The item's own logo, else the nearest parent's (an episode has its series').
    private fun logo(
        itemId: ItemId,
        dto: ItemDetailsDto,
    ): ItemLogo? {
        val own = (dto.imageTags as? JsonObject)?.get("Logo")?.let { it as? JsonPrimitive }?.takeIf { it.isString }
        return runCatching {
            when {
                own != null -> {
                    ItemLogo(itemId, own.content)
                }

                dto.parentLogoItemId != null && dto.parentLogoImageTag != null -> {
                    ItemLogo(ItemId(dto.parentLogoItemId), dto.parentLogoImageTag)
                }

                else -> {
                    null
                }
            }
        }.getOrNull() // A blank or unsafe ID or tag is no logo.
    }

    // The first media source's tracks: the first picture, the audio the player would pick, and any subtitles.
    private fun media(dto: ItemDetailsDto): MediaSummary? {
        val streams = dto.mediaSources?.firstOrNull()?.mediaStreams ?: return null
        val video = streams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
        return MediaSummary(
            videoWidth = whole(video?.width),
            videoHeight = whole(video?.height),
            audioChannels = whole(defaultAudio(streams)?.channels),
            hasSubtitles = streams.any { it.type.equals("Subtitle", ignoreCase = true) },
        )
    }

    // The track marked default, else the first: what the player would pick with nothing chosen.
    private fun defaultAudio(streams: List<DetailsStreamDto>): DetailsStreamDto? {
        val audio = streams.filter { it.type.equals("Audio", ignoreCase = true) }
        return audio.firstOrNull { (it.isDefault as? JsonPrimitive)?.booleanOrNull == true } ?: audio.firstOrNull()
    }

    private fun whole(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
}
