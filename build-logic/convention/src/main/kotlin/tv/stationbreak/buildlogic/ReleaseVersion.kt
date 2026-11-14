// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

/**
 * A release's SemVer (ADR-0011): `MAJOR.MINOR.PATCH` with MAJOR 1 or more and MINOR and PATCH 0 to 99, no leading
 * zeros. [code] packs it into an Android versionCode that rises with the version. The release workflow's tag check
 * (`.github/workflows/release.yml`) uses the same pattern: change both together.
 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    val name: String get() = "$major.$minor.$patch"

    val code: Int get() = major * 10_000 + minor * 100 + patch

    companion object {
        private val PATTERN = Regex("""([1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)""")

        /** Android's versionCode limit (so MAJOR is at most 210000). */
        private const val MAX_CODE = 2_100_000_000L

        fun parse(raw: String): ReleaseVersion {
            val match =
                requireNotNull(PATTERN.matchEntire(raw)) {
                    "RELEASE_VERSION must be MAJOR.MINOR.PATCH with MAJOR 1 or more and MINOR and PATCH 0 to 99, " +
                        "not '$raw'"
                }
            val (major, minor, patch) = match.destructured
            // A huge MAJOR doesn't fit a Long: treat it as over the limit.
            val code = (major.toLongOrNull() ?: Long.MAX_VALUE) * 10_000 + minor.toLong() * 100 + patch.toLong()
            require(major.length <= 6 && code <= MAX_CODE) {
                "RELEASE_VERSION '$raw' gives a versionCode above Android's limit of $MAX_CODE"
            }
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
