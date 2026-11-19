// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

/**
 * The version of a local or device-test release build (#966), passed in `TEST_RELEASE_VERSION`. Looser than
 * [ReleaseVersion], which stays the strict gate for the tagged store release: MAJOR may be 0, and a pre-release suffix
 * `-alpha`, `-beta` or `-rc` with a number 0 to 29 (`-rc.2` or `-rc2`) is allowed, so a test APK on a TV is told apart
 * from the store build by its name.
 *
 * [code] is `(MAJOR * 10000 + MINOR * 100 + PATCH) * 100 + stage`. The stage is 99 for the final version and
 * `alpha 0..29 < beta 30..59 < rc 60..89` for a pre-release, so `1.0.0-rc.2` sorts below `1.0.0` and above
 * `1.0.0-beta.9`, as SemVer orders them. A test code is never compared with a store code (a test build is never
 * shipped, as with CI's run-number codes in ADR-0011), which is why the two schemes differ.
 */
data class TestReleaseVersion(
    val name: String,
    val code: Int,
) {
    companion object {
        private val PATTERN =
            Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(?:-(alpha|beta|rc)\.?(0|[1-9][0-9]?))?""")

        private val LABELS = listOf("alpha", "beta", "rc")

        private const val FINAL_STAGE = 99
        private const val MAX_CODE = 2_100_000_000L

        fun parse(raw: String): TestReleaseVersion {
            val match =
                requireNotNull(PATTERN.matchEntire(raw)) {
                    "TEST_RELEASE_VERSION must be MAJOR.MINOR.PATCH with MINOR and PATCH 0 to 99, optionally followed " +
                        "by -alpha, -beta or -rc and a number 0 to 29 (1.0.0-rc.2), not '$raw'"
                }
            val (major, minor, patch, label, number) = match.destructured
            val stage =
                if (label.isEmpty()) {
                    FINAL_STAGE
                } else {
                    val n = number.toInt()
                    require(n < 30) { "TEST_RELEASE_VERSION '$raw' has a pre-release number above 29" }
                    LABELS.indexOf(label) * 30 + n
                }
            // A huge MAJOR doesn't fit a Long: treat it as over the limit.
            val core = (major.toLongOrNull() ?: Long.MAX_VALUE / 1_000_000) * 10_000 + minor.toLong() * 100 + patch.toLong()
            val code = core * 100 + stage
            require(code <= MAX_CODE) {
                "TEST_RELEASE_VERSION '$raw' gives a versionCode above Android's limit of $MAX_CODE"
            }
            return TestReleaseVersion(raw, code.toInt())
        }
    }
}
