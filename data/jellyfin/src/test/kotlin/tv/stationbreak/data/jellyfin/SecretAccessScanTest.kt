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
    private val allowed = setOf("AuthHeaderBuilder.kt", "SessionRepository.kt", "QuickConnectRequests.kt", "Secrets.kt")

    @Test
    fun theOptInAppearsOnlyInTheAllowedMainSourceFiles() {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }
        val users =
            root
                .walkTopDown()
                .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }
                .filter { "SecretAccess" in it.readText() }
                .map { it.name }
                .toSortedSet()
        assertEquals(emptySet(), users - allowed, "SecretAccess used outside the allowed files")
    }
}
