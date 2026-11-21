// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

/**
 * A release's version (ADR-0011): `MAJOR.MINOR.PATCH` with MINOR and PATCH 0 to 99 and no leading zeros, optionally
 * followed by `-alpha.N`, `-beta.N` or `-rc.N` (N 0 to 29). MAJOR may be 0, so a beta can come before 1.0.0. The release
 * workflow's tag check (`.github/workflows/release.yml`) uses the same pattern, and so does `scripts/release-notes.sh`
 * for the tag it is given: change all three together.
 *
 * [code] is the Android versionCode, `(MAJOR * 10000 + MINOR * 100 + PATCH) * 100 + stage`. The stage is 99 for a final
 * version, `alpha.N` is N, `beta.N` is 30 + N and `rc.N` is 60 + N, so a pre-release always sorts
 * below its final release and above every earlier version: `0.9.0-beta.1` < `0.9.0` < `1.0.0-beta.1` < `1.0.0`. The
 * workflow's code check (`expected_code`) computes the same number: change both together.
 */
data class ReleaseVersion(
    val name: String,
    val code: Int,
) {
    companion object {
        private val PATTERN =
            Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(?:-(alpha|beta|rc)\.(0|[1-9]|[12][0-9]))?""")

        private const val FINAL_STAGE = 99
        private const val BETA_STAGE = 30
        private const val RC_STAGE = 60

        /** Android's versionCode limit. */
        private const val MAX_CODE = 2_100_000_000L

        fun parse(raw: String): ReleaseVersion {
            val match =
                requireNotNull(PATTERN.matchEntire(raw)) {
                    "RELEASE_VERSION must be MAJOR.MINOR.PATCH with MINOR and PATCH 0 to 99, optionally followed by " +
                        "-alpha.N, -beta.N or -rc.N (N 0 to 29), not '$raw'"
                }
            val (major, minor, patch, label, number) = match.destructured
            val stage =
                when (label) {
                    "alpha" -> number.toInt()
                    "beta" -> BETA_STAGE + number.toInt()
                    "rc" -> RC_STAGE + number.toInt()
                    else -> FINAL_STAGE
                }
            // A huge MAJOR doesn't fit a Long: treat it as over the limit.
            val core = (major.toLongOrNull() ?: Long.MAX_VALUE / 1_000_000) * 10_000 + minor.toLong() * 100 + patch.toLong()
            val code = core * 100 + stage
            require(code <= MAX_CODE) { "RELEASE_VERSION '$raw' gives a versionCode above Android's limit of $MAX_CODE" }
            return ReleaseVersion(raw, code.toInt())
        }
    }
}
