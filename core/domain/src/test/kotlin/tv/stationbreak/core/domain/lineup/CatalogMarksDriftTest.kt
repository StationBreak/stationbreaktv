// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.StarterCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every key the live catalog can give a channel has a bundled logo, or is named here as one that has none on purpose.
 * Lives in `core:domain` because the catalog is reachable from here and not from `feature:guide`; the guide's assets
 * are found from the repository root.
 */
class CatalogMarksDriftTest {
    // Gradle runs a module's unit tests with the module directory as the working directory.
    private val root = File(".").canonicalFile.parentFile.parentFile
    private val assets = File(root, "feature/guide/src/main/assets/channelmarks")

    @Test
    fun everyCatalogKeyHasABundledLogoOrIsOnTheNoLogoList() {
        val files = assets.list()?.toSet().orEmpty()
        assertEquals(true, files.isNotEmpty(), "no logos found in $assets")
        val bundled = files.mapNotNull { NAME.matchEntire(it)?.groupValues?.get(1) }.toSet()
        val keys = StarterCatalog.recipes.map { it.key } + StarterCatalog.leftovers.map { it.key }
        val missing = keys.filter { it !in bundled && it !in NO_LOGO_KEYS }
        assertEquals(emptyList(), missing, "catalog keys with no bundled logo and not on NO_LOGO_KEYS")
    }

    private companion object {
        /** Keys that have no logo on purpose. Empty today. */
        val NO_LOGO_KEYS: Set<String> = emptySet()
        val NAME = Regex("sl_\\d{3}_([a-z0-9-]+)_32dp\\.png")
    }
}
