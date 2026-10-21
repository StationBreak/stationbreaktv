// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import java.io.Serializable

/** What a module's build file declares it depends on. */
sealed interface Dependency : Serializable {
    /** What an [ArchitectureException] names: the project path or `group:name`. */
    val key: String

    /** A project in this build, by path (`:core:model`). */
    data class OnProject(
        val path: String,
        val testFixtures: Boolean = false,
    ) : Dependency {
        override val key get() = path

        override fun toString() = if (testFixtures) "testFixtures($path)" else path
    }

    /** An external artifact (`androidx.core:core`). */
    data class OnArtifact(
        val group: String,
        val name: String,
    ) : Dependency {
        override val key get() = "$group:$name"

        override fun toString() = key
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
    SDK_CONFINED(
        "sdk-confined",
        "only data:jellyfin (and app's instrumented tests) may declare the Jellyfin SDK (org.jellyfin.sdk:*), so no " +
            "SDK type reaches another module (ADR-0005)",
    ),
    OKHTTP_CONFINED(
        "okhttp-confined",
        "a [KMP] or [JVM] module other than data:jellyfin may not declare OkHttp (com.squareup.okhttp3:*), so no " +
            "OkHttp type reaches a port signature (ADR-0005)",
    ),
    NO_ROOM_OR_WORK_IN_CORE(
        "no-room-or-work-in-core",
        "no core:* module may declare Room (androidx.room*) or WorkManager (androidx.work*), in any configuration: " +
            "they are Android-only, and the cache and its worker belong to data:library (F0.6)",
    ),
    NO_HTTP_LOGGING(
        "no-http-logging",
        "no module may declare com.squareup.okhttp3:logging-interceptor, in any configuration: its " +
            "HttpLoggingInterceptor prints URLs, headers and tokens (F0.3 R18)",
    ),
    NOT_ALLOWLISTED("not-allowlisted", "this edge isn't in MODULES.md's \"Dependency graph (allowed edges)\""),
    UNKNOWN_MODULE("unknown-module", "the module isn't in MODULES.md; add it there and to ArchitectureRules first"),
}

/** A named, reasoned exception to the rules for one module and one dependency (`:core:x` or `group:name`). */
data class ArchitectureException(
    val module: String,
    val dependency: String,
    val reason: String,
)

/**
 * MODULES.md's dependency rules as an allowlist keyed by module kind. A module's kind and platform come from its
 * path (this table), never from inspecting another project, so the check stays isolated-projects safe.
 */
object ArchitectureRules {
    /** Every exception needs a reason. Empty on day one. */
    val EXCEPTIONS: List<ArchitectureException> = emptyList()

    private enum class Platform { ANDROID, PURE }

    /** A module's platform and the modules it may depend on; [allowed] null means any (`app`). */
    private class ModuleSpec(
        val platform: Platform,
        val allowed: Set<String>?,
    )

    private const val APP = ":app"
    private const val FEATURE_PREFIX = ":feature:"
    private const val DATA_PREFIX = ":data:"

    // MODULES.md's module tree with its "Dependency graph (allowed edges)" and "Also allowed". [KMP] and [JVM] are
    // both PURE here. Any module's tests may also use core:testing and core:domain's testFixtures; those are handled
    // in [checkProjectEdge].
    private val MODULES =
        mapOf(
            APP to ModuleSpec(Platform.ANDROID, null),
            ":core:model" to ModuleSpec(Platform.PURE, emptySet()),
            ":core:rules" to ModuleSpec(Platform.PURE, setOf(":core:model")),
            ":core:engine" to ModuleSpec(Platform.PURE, setOf(":core:rules", ":core:model")),
            ":core:scheduler" to ModuleSpec(Platform.PURE, setOf(":core:model")),
            ":core:testing" to ModuleSpec(Platform.PURE, setOf(":core:model", ":core:rules")),
            ":core:domain" to ModuleSpec(Platform.PURE, setOf(":core:engine", ":core:scheduler", ":core:model")),
            ":core:ui" to ModuleSpec(Platform.ANDROID, emptySet()),
            ":core:playback" to ModuleSpec(Platform.ANDROID, setOf(":core:model", ":core:domain")),
            ":data:jellyfin" to ModuleSpec(Platform.PURE, setOf(":core:domain", ":core:model")),
            ":data:library" to ModuleSpec(Platform.ANDROID, setOf(":core:domain", ":core:model")),
            ":data:settings" to ModuleSpec(Platform.ANDROID, setOf(":core:domain", ":core:model", ":core:rules")),
        )

    // Edges that only a module's tests may have, beyond its allowlist: data:library's edit-cutoff property test builds
    // days with the real scheduler (spec 09 task 2.11). Its main code reaches the scheduler only through core:domain.
    private val TEST_ONLY_EDGES = mapOf(":data:library" to setOf(":core:scheduler"))

    // Every feature:* module (all Android), matched by prefix.
    private val FEATURE =
        ModuleSpec(Platform.ANDROID, setOf(":core:domain", ":core:ui", ":core:playback", ":core:model"))

    private const val CORE_PREFIX = ":core:"
    private val ROOM_OR_WORK_GROUPS = listOf("androidx.room", "androidx.room3", "androidx.work")

    private const val DATA_JELLYFIN = ":data:jellyfin"
    private const val SDK_GROUP = "org.jellyfin.sdk"
    private const val OKHTTP_GROUP = "com.squareup.okhttp3"
    private const val OKHTTP_LOGGING = "com.squareup.okhttp3:logging-interceptor"

    private val SCHEDULER_FORBIDDEN = setOf(":core:engine", ":core:rules")

    private val ANDROID_GROUPS = listOf("androidx", "org.jetbrains.androidx", "com.android", "com.google.android")

    // Android-only artifacts in groups that are otherwise plain JVM.
    private val ANDROID_ARTIFACTS = setOf("com.google.dagger:hilt-android")

    // commonTest*, jvmTest*, linuxX64Test*, test*, androidTest* and their variant forms (testDebug…,
    // githubDebugAndroidTest…). testFixtures* is not a test configuration.
    private val TEST_CONFIGURATION = Regex("^test(?!Fixtures)|[a-z0-9]Test(?!Fixtures)(?=[A-Z]|$)")

    fun isTestConfiguration(name: String): Boolean = TEST_CONFIGURATION.containsMatchIn(name)

    // data:jellyfin, and app's instrumented tests, which use the SDK's own client to pin the SLF4J binding it needs
    // (ADR-0006). Nothing the APK ships may declare it.
    private fun mayDeclareSdk(
        module: String,
        configuration: String,
    ): Boolean = module == DATA_JELLYFIN || (module == APP && isTestConfiguration(configuration))

    fun violations(
        module: String,
        edges: List<DeclaredEdge>,
        exceptions: List<ArchitectureException> = EXCEPTIONS,
    ): List<Violation> {
        val spec = specOf(module) ?: return listOf(Violation(module, null, Rule.UNKNOWN_MODULE))
        return edges
            .filterNot { edge -> exceptions.any { it.module == module && it.dependency == edge.dependency.key } }
            .mapNotNull { edge -> check(module, spec, edge)?.let { Violation(module, edge, it) } }
    }

    private fun specOf(path: String): ModuleSpec? =
        if (path.startsWith(FEATURE_PREFIX) && path.count { it == ':' } == 2) FEATURE else MODULES[path]

    private fun check(
        module: String,
        spec: ModuleSpec,
        edge: DeclaredEdge,
    ): Rule? =
        when (val dependency = edge.dependency) {
            is Dependency.OnArtifact -> {
                val android =
                    ANDROID_GROUPS.any { dependency.group == it || dependency.group.startsWith("$it.") } ||
                        dependency.key in ANDROID_ARTIFACTS
                when {
                    dependency.key == OKHTTP_LOGGING -> {
                        Rule.NO_HTTP_LOGGING
                    }

                    module.startsWith(CORE_PREFIX) && dependency.group in ROOM_OR_WORK_GROUPS -> {
                        Rule.NO_ROOM_OR_WORK_IN_CORE
                    }

                    spec.platform == Platform.PURE && android -> {
                        Rule.NO_ANDROID_IN_PURE_MODULES
                    }

                    dependency.group == SDK_GROUP && !mayDeclareSdk(module, edge.configuration) -> {
                        Rule.SDK_CONFINED
                    }

                    dependency.group == OKHTTP_GROUP && spec.platform == Platform.PURE && module != DATA_JELLYFIN -> {
                        Rule.OKHTTP_CONFINED
                    }

                    else -> {
                        null
                    }
                }
            }

            is Dependency.OnProject -> {
                checkProjectEdge(module, spec, dependency, edge.configuration)
            }
        }

    // The specific rules come first, so a violation names the rule MODULES.md states rather than "not-allowlisted".
    private fun checkProjectEdge(
        module: String,
        spec: ModuleSpec,
        dependency: Dependency.OnProject,
        configuration: String,
    ): Rule? {
        val target = dependency.path
        val targetSpec = specOf(target)
        val isTest = isTestConfiguration(configuration)
        return when {
            targetSpec == null -> {
                Rule.UNKNOWN_MODULE
            }

            target == module -> {
                null
            }

            target == ":core:testing" -> {
                if (isTest) null else Rule.TESTING_IN_TESTS_ONLY
            }

            spec.platform == Platform.PURE && targetSpec.platform == Platform.ANDROID -> {
                Rule.NO_ANDROID_IN_PURE_MODULES
            }

            spec === FEATURE && (target.startsWith(DATA_PREFIX) || target.startsWith(FEATURE_PREFIX)) -> {
                Rule.FEATURE_ISOLATION
            }

            module == ":core:scheduler" && target in SCHEDULER_FORBIDDEN -> {
                Rule.SCHEDULER_INDEPENDENCE
            }

            dependency.testFixtures && !isTest -> {
                Rule.TESTFIXTURES_IN_TESTS_ONLY
            }

            dependency.testFixtures && target == ":core:domain" -> {
                null
            }

            isTest && target in TEST_ONLY_EDGES[module].orEmpty() -> {
                null
            }

            spec.allowed == null || target in spec.allowed -> {
                null
            }

            else -> {
                Rule.NOT_ALLOWLISTED
            }
        }
    }
}
