// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.Interceptor
import okhttp3.Response
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.SecretAccess

/**
 * Builds the one `Authorization: MediaBrowser ...` header (R10). Each value is percent-encoded as UTF-8 with only
 * the RFC 3986 unreserved characters left alone, so a device name can't add a field, close its quotes or carry a
 * control character. Jellyfin decodes the values. This is one of the few places that may read a token.
 */
internal class AuthHeaderBuilder(
    private val identity: ClientIdentity,
) {
    @OptIn(SecretAccess::class)
    fun build(
        deviceId: String,
        token: AccessToken?,
    ): String =
        buildString {
            append("MediaBrowser Client=\"").append(encode(identity.clientName))
            append("\", Device=\"").append(encode(identity.deviceName))
            append("\", DeviceId=\"").append(encode(deviceId))
            append("\", Version=\"").append(encode(identity.appVersion)).append('"')
            if (token != null) append(", Token=\"").append(encode(token.reveal)).append('"')
        }

    private fun encode(value: String): String =
        buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and BYTE_MASK
                if (c.toChar() in UNRESERVED) {
                    append(c.toChar())
                } else {
                    append('%').append(HEX[c shr NIBBLE]).append(HEX[c and NIBBLE_MASK])
                }
            }
        }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val NIBBLE = 4
        const val NIBBLE_MASK = 0xF
        const val HEX = "0123456789ABCDEF"
        val UNRESERVED = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
    }
}

/** What a request says about who is calling; [AuthHeaderInterceptor] turns it into the header. */
internal class AuthTag(
    val deviceId: String,
    val token: AccessToken?,
)

/**
 * An **application** interceptor that sets the `Authorization` header from the request's [AuthTag], replacing any
 * other. Being an application interceptor, OkHttp's own rule applies on a redirect-following client built the same
 * way: it drops `Authorization` when a redirect leaves the host (defence in depth for R9).
 */
internal class AuthHeaderInterceptor(
    private val header: AuthHeaderBuilder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tag = request.tag(AuthTag::class.java) ?: return chain.proceed(request)
        val signed = request.newBuilder().header("Authorization", header.build(tag.deviceId, tag.token)).build()
        return chain.proceed(signed)
    }
}
