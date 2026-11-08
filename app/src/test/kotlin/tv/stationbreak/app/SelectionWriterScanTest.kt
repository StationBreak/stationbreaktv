// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Spec 26, Part 4: the library selection has one writer. Only it, the ports' own file, the stores that implement the
 * writes and the DI modules that bind them may name `SelectionWriter` or `SelectionStore` in production code; anyone
 * else holding them could write the selection outside its lock. A new user fails this test.
 */
class SelectionWriterScanTest {
    // Repo-relative paths, so a same-named file in another module doesn't pass.
    private val allowed =
        setOf(
            "core/domain/src/main/kotlin/tv/stationbreak/core/domain/ServerSettings.kt",
            "core/domain/src/main/kotlin/tv/stationbreak/core/domain/selection/LibrarySelection.kt",
            "data/settings/src/main/kotlin/tv/stationbreak/data/settings/server/FileServerSettings.kt",
            "app/src/main/kotlin/tv/stationbreak/app/net/PinSyncingServerSettings.kt",
            "app/src/main/kotlin/tv/stationbreak/app/di/MediaServerModule.kt",
            "app/src/main/kotlin/tv/stationbreak/app/di/LineupModule.kt",
        )

    // main, commonMain, jvmMain and the app's flavour and build-type sets; never a test set or the test fixtures.
    private val productionSourceSet = Regex("/src/(?![A-Za-z]*[Tt]est)[A-Za-z]+/")

    private val names = Regex("""\bSelection(Writer|Store)\b""")

    @Test
    fun theSelectionsWritesAreNamedOnlyByItsOneWriterTheStoresAndTheirBindings() {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }
        val users =
            root
                .walkTopDown()
                // `.claude` holds agent worktrees: whole checkouts of other branches, not this tree's sources.
                .onEnter { it.name !in setOf("build", ".git", ".gradle", ".claude", "build-logic") }
                .filter { it.isFile && it.extension == "kt" }
                .filter { productionSourceSet.containsMatchIn(it.path.replace('\\', '/')) }
                .filter { names.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).path.replace('\\', '/') }
                .toSortedSet()
        assertEquals("the selection's writes named outside the allowed files", emptySet<String>(), users - allowed)
    }
}
