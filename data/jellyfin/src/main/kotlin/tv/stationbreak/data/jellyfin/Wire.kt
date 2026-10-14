// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
internal class ItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ParentId") val parentId: String? = null,
)

/** The answer to `PlaybackInfo`. `Path`, `TranscodingUrl` and the like are not here, so they can't be passed on. */
@Serializable
internal class PlaybackInfoDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
internal class MediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
)

/** The body of `Sessions/Playing`, `/Progress` and `/Stopped`. `IsPaused` is left out of the last. */
@Serializable
internal class PlaybackReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
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
