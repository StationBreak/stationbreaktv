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
