// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import java.nio.CharBuffer

internal object Wire {
    private val json = Json { ignoreUnknownKeys = true }

    /** Encodes a request body. Nothing in the bodies built from this holds a credential. */
    fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): ByteArray = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    /** Decodes an answer, or says the server isn't speaking Jellyfin's API. Never echoes the text. */
    fun <T> parse(
        text: String,
        serializer: KSerializer<T>,
    ): ServerResult<T> =
        try {
            ServerResult.Success(json.decodeFromString(serializer, text))
        } catch (_: SerializationException) {
            ServerResult.Failure(MediaServerError.NotJellyfin())
        } catch (_: IllegalArgumentException) {
            // A blank ID or token in an otherwise well-formed answer.
            ServerResult.Failure(MediaServerError.NotJellyfin())
        }
}

// These hold a token or a secret: never printed.
// These hold a token or a secret: never printed.
@Serializable
internal class AuthResultDto(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String,
) {
    override fun toString(): String = "AuthResultDto(***)"
}

@Serializable
internal class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Policy") val policy: PolicyDto? = null,
)

@Serializable
internal class PolicyDto(
    @SerialName("IsAdministrator") val isAdministrator: Boolean = false,
)

@Serializable
internal class InitiateDto(
    @SerialName("Secret") val secret: String,
    @SerialName("Code") val code: String,
) {
    override fun toString(): String = "InitiateDto(***)"
}

@Serializable
internal class PollDto(
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

// The media surface (Unit 3). Deliberately small and lenient: only the fields the port needs, every other field and
// every value a newer server invents is ignored, so one odd item can't fail a page (the 10.11 decoding failure,
// ADR-0006). Types are read as text, not as the SDK's enums, so an unknown one is an unknown value and never a
// missing one.

@Serializable
internal class ViewsDto(
    @SerialName("Items") val items: List<ViewDto> = emptyList(),
)

@Serializable
internal class ViewDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
)

@Serializable
internal class ItemsDto(
    @SerialName("Items") val items: List<ItemDto> = emptyList(),
    // Null when the server sent none. The old `items` call reads that as 0; the cache calls fail a first page
    // that has none, because 0 would look like an empty library.
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

/**
 * One item of a page. Decoding is deliberately strict about types: a field that is present but the wrong type (a
 * year that is text, say) fails the whole page as `NotJellyfin`, because `coerceInputValues` would turn it into a
 * silent default and the cache would hold a wrong value. Missing, null and unknown fields are fine. A server that
 * sends one malformed item therefore fails its page every time; the sync (Unit 3) retries and then narrows the page
 * to find it, rather than guessing here.
 */
@Serializable
@Suppress("LongParameterList") // One property per wire field.
internal class ItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ParentId") val parentId: String? = null,
    // Dates stay text here and are read leniently in the mapper, so an odd one is absent, not a failed page.
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Tags") val tags: List<String>? = null,
    @SerialName("Studios") val studios: List<StudioDto>? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
)

/** The fields the details call reads; nothing else can fail it. */
@Serializable
internal class ItemDetailsDto(
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
)

/** A page of series: only what `SeriesInfo` holds is read, so no other field can fail the page. */
@Serializable
internal class SeriesItemsDto(
    @SerialName("Items") val items: List<SeriesDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
internal class SeriesDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Tags") val tags: List<String>? = null,
    @SerialName("Studios") val studios: List<StudioDto>? = null,
)

/** One studio of an item or series: only the name is read. A studio with no name is dropped by [studiosOf]. */
@Serializable
internal class StudioDto(
    @SerialName("Name") val name: String? = null,
)

/** A page of BoxSets: only the ID and the name are read. */
@Serializable
internal class CollectionsDto(
    @SerialName("Items") val items: List<CollectionDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
internal class CollectionDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)

/** A page of a BoxSet's members: only the ID and the type are read, so no other field can fail the page. */
@Serializable
internal class CollectionMembersDto(
    @SerialName("Items") val items: List<CollectionMemberDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
internal class CollectionMemberDto(
    @SerialName("Id") val id: String,
    @SerialName("Type") val type: String = "",
)

/** An ID-only listing: the answer carries more, but only the ID is read. */
@Serializable
internal class ItemIdsDto(
    @SerialName("Items") val items: List<ItemIdDto> = emptyList(),
    // Null when the server sent none. The old `items` call reads that as 0; the cache calls fail a first page
    // that has none, because 0 would look like an empty library.
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
internal class ItemIdDto(
    @SerialName("Id") val id: String,
)

/**
 * The answer to `PlaybackInfo`. `Path`, `TranscodingUrl`, `DirectStreamUrl`, `DeliveryUrl` and the like are not here,
 * so they can't be passed on (R2).
 */
@Serializable
internal class PlaybackInfoDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
@Suppress("LongParameterList") // One property per wire field.
internal class MediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
    // A list on current servers; read as a JSON value so a comma-separated string can't fail the whole answer.
    @SerialName("TranscodeReasons") val transcodeReasons: JsonElement? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

/** One track. Text is read as text so a type or delivery method a newer server invents is unknown, not an error. */
@Serializable
@Suppress("LongParameterList") // One property per wire field.
internal class MediaStreamDto(
    // A track with no index can't be chosen or addressed, so it is dropped rather than failing the answer.
    @SerialName("Index") val index: Int? = null,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("Profile") val profile: String? = null,
    // The level and depth are read as JSON values so one odd value on a track can't fail the whole answer.
    @SerialName("Level") val level: JsonElement? = null,
    @SerialName("BitDepth") val bitDepth: JsonElement? = null,
)

/**
 * The body of `Sessions/Playing`, `/Progress` and `/Stopped`. `IsPaused`, `PlayMethod` and `CanSeek` are left out of
 * the last; `Failed` is sent only by it.
 */
@Suppress("LongParameterList") // One property per wire field.
@Serializable
internal class PlaybackReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = null,
    @SerialName("CanSeek") val canSeek: Boolean? = null,
    @SerialName("Failed") val failed: Boolean? = null,
)

/** The part of `UserItems/{id}/UserData` the app reads. */
@Serializable
internal class UserDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
)

/** The `AuthenticateByName` body, written straight into a byte array so the caller can wipe the password. */
internal object PasswordBody {
    private const val MAX_ESCAPE = 6
    private const val HEX = 16
    private const val UNICODE_DIGITS = 4
    private const val FIRST_PRINTABLE = ' '

    fun encode(
        username: String,
        password: CharArray,
    ): ByteArray {
        val prefix = "{\"Username\":${JsonText.quote(username)},\"Pw\":\""
        val suffix = "\"}"
        val buffer = CharArray(prefix.length + password.size * MAX_ESCAPE + suffix.length)
        try {
            var n = 0
            for (c in prefix) buffer[n++] = c
            for (c in password) n = put(buffer, n, c)
            for (c in suffix) buffer[n++] = c
            val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(buffer, 0, n))
            val out = ByteArray(encoded.remaining())
            encoded.get(out)
            if (encoded.hasArray()) encoded.array().fill(0)
            return out
        } finally {
            buffer.fill('\u0000')
        }
    }

    private fun put(
        buffer: CharArray,
        at: Int,
        c: Char,
    ): Int {
        var n = at
        when {
            c == '"' -> {
                buffer[n++] = '\\'
                buffer[n++] = '"'
            }

            c == '\\' -> {
                buffer[n++] = '\\'
                buffer[n++] = '\\'
            }

            c < FIRST_PRINTABLE -> {
                for (u in "\\u" + c.code.toString(HEX).padStart(UNICODE_DIGITS, '0')) buffer[n++] = u
            }

            else -> {
                buffer[n++] = c
            }
        }
        return n
    }
}
