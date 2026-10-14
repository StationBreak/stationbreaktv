// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion

/**
 * What `GET /System/Info/Public` must say for the server to be a usable Jellyfin: the expected shape, the product name
 * of a Jellyfin server, version 12.0 or later (ADR-0006; a later major is allowed and reported, the CI matrix is what
 * catches drift) and a finished startup wizard.
 */
internal object ProbeChecks {
    /** Public system info is a few hundred bytes. Anything past this isn't one, and is never parsed. */
    const val MAX_BODY_BYTES = 64 * 1024

    private const val PRODUCT_NAME = "Jellyfin Server"
    private const val MIN_MAJOR = 12

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class PublicInfo(
        @SerialName("ServerName") val serverName: String? = null,
        @SerialName("Version") val version: String? = null,
        @SerialName("Id") val id: String? = null,
        @SerialName("ProductName") val productName: String? = null,
        @SerialName("StartupWizardCompleted") val startupWizardCompleted: Boolean? = null,
    )

    fun check(body: String): ServerResult<ServerInfo> {
        val info = decode(body)
        val version = info?.version?.let(ServerVersion::parse)
        val id = info?.id?.takeIf { it.isNotBlank() }
        return when {
            info == null || version == null || id == null || info.serverName == null -> notJellyfin()
            info.productName != PRODUCT_NAME || info.startupWizardCompleted == null -> notJellyfin()
            version.major < MIN_MAJOR -> ServerResult.Failure(MediaServerError.UnsupportedVersion(version))
            !info.startupWizardCompleted -> ServerResult.Failure(MediaServerError.ServerNotSetUp())
            else -> ServerResult.Success(ServerInfo(info.serverName, ServerId(id), version))
        }
    }

    fun notJellyfin() = ServerResult.Failure(MediaServerError.NotJellyfin())

    private fun decode(body: String): PublicInfo? =
        try {
            json.decodeFromString<PublicInfo>(body)
        } catch (_: IllegalArgumentException) {
            // Not JSON, or not this shape: SerializationException is an IllegalArgumentException. The text isn't kept.
            null
        }
}
