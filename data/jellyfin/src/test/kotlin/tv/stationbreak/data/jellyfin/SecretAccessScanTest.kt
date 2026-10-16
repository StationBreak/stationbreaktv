// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R4: the raw value of a token or Quick Connect secret is read in three places only: the header builder, the session
 * serialiser, and the Quick Connect request that has to send the secret. A new reader fails this test.
 */
class SecretAccessScanTest {
    // Repo-relative paths, so a same-named file in another module doesn't pass.
    private val allowed =
        setOf(
            "core/model/src/commonMain/kotlin/tv/stationbreak/core/model/Secrets.kt",
            "core/domain/src/main/kotlin/tv/stationbreak/core/domain/SessionRepository.kt",
            "data/jellyfin/src/main/kotlin/tv/stationbreak/data/jellyfin/AuthHeaderBuilder.kt",
            "data/jellyfin/src/main/kotlin/tv/stationbreak/data/jellyfin/QuickConnectRequests.kt",
        )

    // main, commonMain, jvmMain and so on; not test or androidTest.
    private val mainSourceSet = Regex("/src/[A-Za-z]*[Mm]ain/")

    @Test
    fun theOptInAppearsOnlyInTheAllowedMainSourceFiles() {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }
        val users =
            root
                .walkTopDown()
                // `.claude` holds agent worktrees: whole checkouts of other branches, not this tree's sources.
                .onEnter { it.name !in setOf("build", ".git", ".gradle", ".claude") }
                .filter { it.isFile && it.extension == "kt" }
                .filter { mainSourceSet.containsMatchIn(it.path.replace('\\', '/')) }
                .filter { "SecretAccess" in it.readText() }
                .map { it.relativeTo(root).path.replace('\\', '/') }
                .toSortedSet()
        assertEquals(emptySet(), users - allowed, "SecretAccess used outside the allowed files")
    }
}
