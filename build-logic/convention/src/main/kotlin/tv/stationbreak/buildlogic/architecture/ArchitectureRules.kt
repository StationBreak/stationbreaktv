// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import java.io.Serializable

/** What a module's build file declares it depends on. */
sealed interface Dependency : Serializable {
    /** A project in this build, by path (`:core:model`). */
    data class OnProject(
        val path: String,
        val testFixtures: Boolean = false,
    ) : Dependency

    /** An external artifact (`androidx.core:core`). */
    data class OnArtifact(
        val group: String,
        val name: String,
    ) : Dependency
}

/** One dependency as declared in one configuration of the module. */
data class DeclaredEdge(
    val configuration: String,
    val dependency: Dependency,
) : Serializable

data class Violation(
    val module: String,
    val edge: DeclaredEdge,
    val rule: Rule,
) {
    val message: String get() = ""
}

enum class Rule(
    val id: String,
) {
    NO_ANDROID_IN_PURE_MODULES("no-android-in-pure-modules"),
    FEATURE_ISOLATION("feature-isolation"),
    SCHEDULER_INDEPENDENCE("scheduler-independence"),
    TESTING_IN_TESTS_ONLY("testing-in-tests-only"),
    NOT_ALLOWLISTED("not-allowlisted"),
    UNKNOWN_MODULE("unknown-module"),
}

data class ArchitectureException(
    val module: String,
    val dependency: String,
    val reason: String,
)

object ArchitectureRules {
    val EXCEPTIONS: List<ArchitectureException> = emptyList()

    fun violations(
        module: String,
        edges: List<DeclaredEdge>,
        exceptions: List<ArchitectureException> = EXCEPTIONS,
    ): List<Violation> = emptyList()
}
