// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable

/** A server's `major.minor.patch`. Anything after the third number (a build suffix) is dropped. */
@Serializable
data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ServerVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "A version has no negative part" }
    }

    override fun compareTo(other: ServerVersion): Int =
        compareValuesBy(this, other, ServerVersion::major, ServerVersion::minor, ServerVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^(\d{1,9})(?:\.(\d{1,9}))?(?:\.(\d{1,9}))?(?:\.\d{1,9})?(?:[-+].*)?$""")

        /** The version in [text], or null when it doesn't start with a number. */
        fun parse(text: String): ServerVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return ServerVersion(major.toInt(), minor.toIntOrNull() ?: 0, patch.toIntOrNull() ?: 0)
        }
    }
}
