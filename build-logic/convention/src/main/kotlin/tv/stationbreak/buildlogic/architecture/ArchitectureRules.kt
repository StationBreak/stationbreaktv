// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import java.io.Serializable

/** What a module's build file declares it depends on. */
sealed interface Dependency : Serializable {
    /** A project in this build, by path (`:core:model`). */
    data class OnProject(
        val path: String,
        val testFixtures: Boolean = false,
    ) : Dependency {
        override fun toString() = if (testFixtures) "testFixtures($path)" else path
    }

    /** An external artifact (`androidx.core:core`). */
    data class OnArtifact(
        val group: String,
        val name: String,
    ) : Dependency {
        override fun toString() = "$group:$name"
    }
}

/** One dependency as declared in one configuration of the module. */
data class DeclaredEdge(
    val configuration: String,
    val dependency: Dependency,
) : Serializable

/** A broken rule. [edge] is null when the module itself is the problem (it isn't in MODULES.md). */
data class Violation(
    val module: String,
    val edge: DeclaredEdge?,
    val rule: Rule,
) {
    val message: String
        get() =
            if (edge == null) {
                "$module breaks [${rule.id}]: ${rule.description}"
            } else {
                "$module -> ${edge.dependency} (${edge.configuration}) breaks [${rule.id}]: ${rule.description}"
            }
}

enum class Rule(
    val id: String,
    val description: String,
) {
    NO_ANDROID_IN_PURE_MODULES(
        "no-android-in-pure-modules",
        "a [KMP] or [JVM] module may not depend on an androidx.*, org.jetbrains.androidx.*, com.android.* or " +
            "com.google.android.* artifact, on hilt-android, or on an Android module",
    ),
    FEATURE_ISOLATION("feature-isolation", "feature:* may not depend on data:* or on another feature:*"),
    SCHEDULER_INDEPENDENCE("scheduler-independence", "core:scheduler may not depend on core:engine or core:rules"),
    TESTING_IN_TESTS_ONLY(
        "testing-in-tests-only",
        "core:testing may be used only in test configurations (not main, not testFixtures)",
    ),
    TESTFIXTURES_IN_TESTS_ONLY(
        "testfixtures-in-tests-only",
        "testFixtures (core:domain's port fakes) may be used only in test configurations " +
            "(not main, not testFixtures)",
    ),
    NOT_ALLOWLISTED("not-allowlisted", "this edge isn't in MODULES.md's \"Dependency graph (allowed edges)\""),
    UNKNOWN_MODULE("unknown-module", "the module isn't in MODULES.md; add it there and to ArchitectureRules first"),
}

/** A named, reasoned exception to the rules for one module and one dependency (`:core:x` or `group:name`). */
data class ArchitectureException(
    val module: String,
    val dependency: String,
    val reason: String,
) : Serializable

/**
 * MODULES.md's dependency rules as an allowlist keyed by module kind. A module's kind and platform come from its
 * path (this table), never from inspecting another project, so the check stays isolated-projects safe.
 */
object ArchitectureRules {
    /** Every exception needs a reason. Empty on day one. */
    val EXCEPTIONS: List<ArchitectureException> = emptyList()

    private enum class Platform { ANDROID, PURE }

    private const val APP = ":app"
    private const val FEATURE_PREFIX = ":feature:"
    private const val DATA_PREFIX = ":data:"
    private const val CORE_TESTING = ":core:testing"
    private const val CORE_DOMAIN = ":core:domain"

    // MODULES.md's module tree. [KMP] and [JVM] are both PURE here; feature:* (all Android) is matched by prefix.
    private val PLATFORMS =
        mapOf(
            APP to Platform.ANDROID,
            ":core:model" to Platform.PURE,
            ":core:rules" to Platform.PURE,
            ":core:engine" to Platform.PURE,
            ":core:scheduler" to Platform.PURE,
            CORE_TESTING to Platform.PURE,
            CORE_DOMAIN to Platform.PURE,
            ":core:ui" to Platform.ANDROID,
            ":core:playback" to Platform.ANDROID,
            ":data:jellyfin" to Platform.PURE,
            ":data:library" to Platform.ANDROID,
            ":data:settings" to Platform.ANDROID,
        )

    // MODULES.md, "Dependency graph (allowed edges)" and "Also allowed". `app` may depend on any module, and any
    // module's tests on core:testing and core:domain's testFixtures; those are handled in [check].
    private val DATA_EDGES = setOf(CORE_DOMAIN, ":core:model")
    private val FEATURE_EDGES = setOf(CORE_DOMAIN, ":core:ui", ":core:playback", ":core:model")
    private val ALLOWED =
        mapOf(
            ":core:model" to emptySet(),
            ":core:rules" to setOf(":core:model"),
            ":core:engine" to setOf(":core:rules", ":core:model"),
            ":core:scheduler" to setOf(":core:model"),
            CORE_TESTING to setOf(":core:model", ":core:rules"),
            CORE_DOMAIN to setOf(":core:engine", ":core:scheduler", ":core:model"),
            ":core:ui" to emptySet(),
            ":core:playback" to emptySet(),
            ":data:jellyfin" to DATA_EDGES,
            ":data:library" to DATA_EDGES,
            ":data:settings" to DATA_EDGES + ":core:rules",
        )

    private val ANDROID_GROUPS = listOf("androidx", "org.jetbrains.androidx", "com.android", "com.google.android")

    // Android-only artifacts in groups that are otherwise plain JVM.
    private val ANDROID_ARTIFACTS = setOf("com.google.dagger:hilt-android")

    // commonTest*, jvmTest*, linuxX64Test*, test*, androidTest* and their variant forms (testDebug…,
    // githubDebugAndroidTest…). testFixtures* is not a test configuration.
    private val TEST_CONFIGURATION = Regex("^test(?!Fixtures)|[a-z0-9]Test(?!Fixtures)(?=[A-Z]|$)")

    fun isTestConfiguration(name: String): Boolean = TEST_CONFIGURATION.containsMatchIn(name)

    fun violations(
        module: String,
        edges: List<DeclaredEdge>,
        exceptions: List<ArchitectureException> = EXCEPTIONS,
    ): List<Violation> {
        val platform = platformOf(module) ?: return listOf(Violation(module, null, Rule.UNKNOWN_MODULE))
        return edges
            .filterNot { edge -> exceptions.any { it.module == module && it.dependency == edge.dependency.key } }
            .mapNotNull { edge -> check(module, platform, edge)?.let { Violation(module, edge, it) } }
    }

    private val Dependency.key: String
        get() =
            when (this) {
                is Dependency.OnProject -> path
                is Dependency.OnArtifact -> "$group:$name"
            }

    private fun platformOf(path: String): Platform? =
        if (path.startsWith(FEATURE_PREFIX) && path.count { it == ':' } == 2) Platform.ANDROID else PLATFORMS[path]

    private fun check(
        module: String,
        platform: Platform,
        edge: DeclaredEdge,
    ): Rule? =
        when (val dependency = edge.dependency) {
            is Dependency.OnArtifact -> {
                val android =
                    ANDROID_GROUPS.any { dependency.group == it || dependency.group.startsWith("$it.") } ||
                        dependency.key in ANDROID_ARTIFACTS
                if (platform == Platform.PURE && android) Rule.NO_ANDROID_IN_PURE_MODULES else null
            }

            is Dependency.OnProject -> {
                checkProjectEdge(module, platform, dependency, edge.configuration)
            }
        }

    // The specific rules come first, so a violation names the rule MODULES.md states rather than "not-allowlisted".
    private fun checkProjectEdge(
        module: String,
        platform: Platform,
        dependency: Dependency.OnProject,
        configuration: String,
    ): Rule? {
        val target = dependency.path
        val targetPlatform = platformOf(target)
        val isTest = isTestConfiguration(configuration)
        val moduleIsFeature = module.startsWith(FEATURE_PREFIX)
        return when {
            targetPlatform == null -> {
                Rule.UNKNOWN_MODULE
            }

            target == module -> {
                null
            }

            target == CORE_TESTING -> {
                if (isTest) null else Rule.TESTING_IN_TESTS_ONLY
            }

            platform == Platform.PURE && targetPlatform == Platform.ANDROID -> {
                Rule.NO_ANDROID_IN_PURE_MODULES
            }

            moduleIsFeature && (target.startsWith(DATA_PREFIX) || target.startsWith(FEATURE_PREFIX)) -> {
                Rule.FEATURE_ISOLATION
            }

            module == ":core:scheduler" && target in setOf(":core:engine", ":core:rules") -> {
                Rule.SCHEDULER_INDEPENDENCE
            }

            dependency.testFixtures && !isTest -> {
                Rule.TESTFIXTURES_IN_TESTS_ONLY
            }

            dependency.testFixtures && target == CORE_DOMAIN -> {
                null
            }

            module == APP -> {
                null
            }

            target in (if (moduleIsFeature) FEATURE_EDGES else ALLOWED[module].orEmpty()) -> {
                null
            }

            else -> {
                Rule.NOT_ALLOWLISTED
            }
        }
    }
}
