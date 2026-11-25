// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

/**
 * Whether the Amazon build puts Pro on sale (`BuildConfig.PRO_ON_SALE`, #99, ADR-0032), by (build type, flavor). THE
 * SWITCH: when it is false the store adapter is the closed stand-in (nothing owned, `available` false, purchase and
 * restore fail) and no Appstore SDK call is made at all. The owner flipped the store release to true on 2026-11-25 for
 * beta.3, after confirming the ADR-0032 purchase checklist; setting the `("release" to "amazon")` line below back to
 * false closes the store again. Debug and benchmark builds are on sale where they already unlock Pro (ProUnlockPolicy),
 * so the real adapter can be exercised on a device.
 *
 * Only the `amazon` flavor has the constant (the github flavor has no store code and never gets one): any other flavor
 * baked on sale fails the build (see [check]).
 */
object ProSalePolicy {
    /** What each (build type, flavor) bakes in. Any pair not listed is off sale. */
    val matrix: Map<Pair<String, String?>, Boolean> =
        mapOf(
            ("debug" to "amazon") to true,
            ("benchmark" to "amazon") to true,
            // OWNER SWITCH: true since 2026-11-25 (beta.3). False closes the store release again.
            ("release" to "amazon") to true,
        )

    /** The build's `PRO_ON_SALE`: fails the build for a flavor with no store (see [check]). */
    fun onSale(
        buildType: String?,
        flavor: String?,
        table: Map<Pair<String, String?>, Boolean> = matrix,
    ): Boolean {
        val onSale = table[(buildType ?: "release") to flavor] ?: false
        check(buildType ?: "release", flavor, onSale)
        return onSale
    }

    /** Only the `amazon` flavor has a store, so only it may be on sale. */
    fun check(
        buildType: String,
        flavor: String?,
        onSale: Boolean,
    ) {
        if (onSale && flavor != "amazon") {
            error("PRO_ON_SALE must be false outside the amazon flavor ($flavor $buildType)")
        }
    }
}
