// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Spec 26, Part 4: the library selection has one writer. In production code (comments aside), only it and the
 * collaborators it builds (internal to core:domain, run only under its lock), the ports' own file, the stores that
 * implement the writes and the DI modules that bind them may name `SelectionWriter` or `SelectionStore`, and only the
 * stores and the selection call the writes; the cache's library-row writes are called only by the selection and
 * carried out by the repository and its DAOs. The selection itself is named only by the module that binds it
 * (anything holding it could open the resume gate), and its collaborators only by its own package. Each DI module
 * takes one store. A new user fails this test.
 */
class SelectionWriterScanTest {
    private val ports = "core/domain/src/main/kotlin/tv/stationbreak/core/domain/ServerSettings.kt"
    private val selectionDir = "core/domain/src/main/kotlin/tv/stationbreak/core/domain/selection"
    private val selection = "$selectionDir/LibrarySelection.kt"

    // The selection's collaborators: built inside it, internal to core:domain, and run only under its lock.
    private val allocation = "$selectionDir/SelectionAllocation.kt"
    private val cache = "$selectionDir/SelectionCache.kt"
    private val reconciler = "$selectionDir/SelectionReconciler.kt"
    private val file = "data/settings/src/main/kotlin/tv/stationbreak/data/settings/server/FileServerSettings.kt"
    private val pinSyncing = "app/src/main/kotlin/tv/stationbreak/app/net/PinSyncingServerSettings.kt"
    private val mediaServerModule = "app/src/main/kotlin/tv/stationbreak/app/di/MediaServerModule.kt"
    private val lineupModule = "app/src/main/kotlin/tv/stationbreak/app/di/LineupModule.kt"
    private val library = "data/library/src/main/kotlin/tv/stationbreak/data/library"
    private val roomRepository = "$library/RoomLibraryRepository.kt"
    private val cacheDao = "$library/CacheTransactionDao.kt"
    private val libraryDao = "$library/LibraryDao.kt"

    // main, commonMain, jvmMain and the app's flavour and build-type sets; never a test set or the test fixtures.
    private val productionSourceSet = Regex("/src/(?![A-Za-z]*[Tt]est)[A-Za-z]+/")

    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

    // Repo-relative path to the file's code lines: comments and KDoc left out, as they write nothing.
    private val code: Map<String, List<String>> by lazy {
        root
            .walkTopDown()
            // `.claude` holds agent worktrees: whole checkouts of other branches, not this tree's sources.
            .onEnter { it.name !in setOf("build", ".git", ".gradle", ".claude", "build-logic") }
            .filter { it.isFile && it.extension == "kt" }
            .filter { productionSourceSet.containsMatchIn(it.path.replace('\\', '/')) }
            .associate { source ->
                val lines =
                    source
                        .readLines()
                        .map { it.substringBefore("//").trim() }
                        .filterNot { it.startsWith("*") || it.startsWith("/*") }
                source.relativeTo(root).path.replace('\\', '/') to lines
            }
    }

    private fun usersOf(pattern: Regex): Set<String> =
        code.filterValues { lines -> lines.any { pattern.containsMatchIn(it) } }.keys.toSortedSet()

    @Test
    fun theWritePorts_areNamedOnlyByTheirWriterTheStoresAndTheirBindings() {
        val allowed =
            setOf(ports, selection, allocation, reconciler, file, pinSyncing, mediaServerModule, lineupModule)

        val users = usersOf(Regex("""\bSelection(Writer|Store)\b"""))

        assertEquals("the selection's writes named outside the allowed files", emptySet<String>(), users - allowed)
    }

    @Test
    fun theWrites_areCalledOnlyByTheSelectionAndTheStores() {
        val allowed = setOf(selection, allocation, reconciler, file, pinSyncing)

        val users = usersOf(Regex("""\.(saveSelection|clearLibraries)\("""))

        assertEquals("a selection write called outside the allowed files", emptySet<String>(), users - allowed)
    }

    // The cache's library rows follow the selection alone: only it calls the row writes, and only the repository and
    // its DAOs carry them out. A pass that wrote them itself (hold expiry, say) would be a second writer.
    @Test
    fun theCacheRowWrites_areCalledOnlyByTheSelectionTheRepositoryAndItsDaos() {
        val allowed = setOf(selection, cache, roomRepository, cacheDao, libraryDao)

        val users = usersOf(Regex("""\.(applySelection|landAdd|revertUnproven)\("""))

        assertEquals("a library-row write called outside the allowed files", emptySet<String>(), users - allowed)
    }

    // The selection's collaborators are internal to core:domain, so any file there could reach their writes: only the
    // selection's own package may name them.
    @Test
    fun theSelectionsCollaborators_areNamedOnlyInItsPackage() {
        val users = usersOf(Regex("""\bSelection(Ownership|Allocation|Cache|Reconciler|Follower)\b"""))

        val outside = users.filterNot { it.substringBeforeLast('/') == selectionDir }.toSortedSet()

        assertEquals("a collaborator of the selection named outside its package", emptySet<String>(), outside)
    }

    @Test
    fun theSelection_isNamedOnlyByTheModuleThatBindsIt() {
        val users = usersOf(Regex("""\bLibrarySelection\b"""))

        val outside = users - setOf(selection, lineupModule)

        assertEquals("the selection named outside its module", emptySet<String>(), outside)
    }

    @Test
    fun eachModule_takesTheStoreOnce() {
        val parameter = Regex("""\b\w+: SelectionStore\b""")

        fun count(path: String) = code.getValue(path).sumOf { parameter.findAll(it).count() }

        assertEquals("the selection's one store parameter in LineupModule", 1, count(lineupModule))
        assertEquals("the one binding over the store in MediaServerModule", 1, count(mediaServerModule))
    }
}
