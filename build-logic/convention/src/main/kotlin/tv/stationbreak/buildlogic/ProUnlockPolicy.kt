// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

/**
 * Whether StationBreak Pro is baked unlocked into a build, by (build type, flavor) (#793, ADR-0023). Debug and benchmark
 * builds keep it open, so dogfood and device runs see no gate. The Amazon release ships closed: a purchase can open it
 * at run time, the build never does. The GitHub release ships fully unlocked (Q-BIZ-1, #119): it is GPL and has no
 * store billing. Any pair not listed is closed.
 */
object ProUnlockPolicy {
    /** What each (build type, flavor) bakes in. A test can pass its own to prove that [unlocked] fails a bad one. */
    val matrix: Map<Pair<String, String?>, Boolean> =
        mapOf(
            ("debug" to "amazon") to true,
            ("debug" to "github") to true,
            ("benchmark" to "amazon") to true,
            ("benchmark" to "github") to true,
            ("release" to "amazon") to false,
            ("release" to "github") to true,
        )

    /** The build's `PRO_UNLOCKED`: fails the build for a store release baked unlocked (see [check]). */
    fun unlocked(
        buildType: String?,
        flavor: String?,
        table: Map<Pair<String, String?>, Boolean> = matrix,
    ): Boolean {
        val unlocked = table[(buildType ?: "release") to flavor] ?: false
        check(buildType ?: "release", flavor, unlocked)
        return unlocked
    }

    /** A release of any flavor but GitHub must ship with the gates closed. GitHub's is the one deliberate exception. */
    fun check(
        buildType: String,
        flavor: String?,
        unlocked: Boolean,
    ) {
        if (buildType == "release" && flavor != "github" && unlocked) {
            error("PRO_UNLOCKED must be false in a store release build ($flavor $buildType)")
        }
    }
}
