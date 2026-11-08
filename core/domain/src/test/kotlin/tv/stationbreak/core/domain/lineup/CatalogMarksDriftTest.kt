// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.StarterCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guide's bundled logos and the live catalog's keys agree both ways: every key has a logo (or is named here as one
 * with none on purpose), and every logo is for a key. Lives in `core:domain` because the catalog is reachable from here
 * and not from `feature:guide`; the build passes the logo folder in, and declares it as an input of the test task.
 */
class CatalogMarksDriftTest {
    private val assets = File(checkNotNull(System.getProperty("stationbreak.channelmarks")) { "no logo folder" })

    // The logo's file number against its key, for the files named like a logo.
    private val bundled: Map<String, Int> =
        assets
            .list()
            .orEmpty()
            .mapNotNull { NAME.matchEntire(it) }
            .associate { it.groupValues[2] to it.groupValues[1].toInt() }

    // Each key against the catalog number of its channel.
    private val catalogNumbers: Map<String, Int> =
        StarterCatalog.recipes.associate { it.key to it.number } +
            StarterCatalog.leftovers.associate { it.key to it.number }

    @Test
    fun everyCatalogKeyHasABundledLogoOrIsOnTheNoLogoList() {
        assertTrue(bundled.isNotEmpty(), "no logos found in $assets")
        val missing = catalogNumbers.keys.filter { it !in bundled && it !in NO_LOGO_KEYS }
        assertEquals(emptyList(), missing, "catalog keys with no bundled logo and not on NO_LOGO_KEYS")
        // The pack is keyed by both: the number in a file name is its channel's catalog number.
        val renumbered = bundled.filter { (key, number) -> catalogNumbers[key] != number }.keys.sorted()
        assertEquals(emptyList(), renumbered, "logo files whose number is not their channel's catalog number")
    }

    @Test
    fun everyBundledLogoIsForACatalogKey() {
        val orphans = bundled.keys.filter { it !in catalogNumbers }.sorted()
        assertEquals(emptyList(), orphans, "bundled logos whose key is not a catalog recipe or leftovers key")
    }

    private companion object {
        /** Keys that have no logo on purpose. Empty today. */
        val NO_LOGO_KEYS: Set<String> = emptySet()
        val NAME = Regex("sl_(\\d{3})_([a-z0-9-]+)_32dp\\.png")
    }
}
