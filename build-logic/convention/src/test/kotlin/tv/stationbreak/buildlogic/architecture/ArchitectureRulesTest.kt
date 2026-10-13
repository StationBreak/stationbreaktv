// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import tv.stationbreak.buildlogic.architecture.Dependency.OnArtifact
import tv.stationbreak.buildlogic.architecture.Dependency.OnProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    private fun project(
        configuration: String,
        path: String,
        testFixtures: Boolean = false,
    ) = DeclaredEdge(configuration, OnProject(path, testFixtures))

    private fun artifact(
        configuration: String,
        coordinates: String,
    ) = DeclaredEdge(configuration, coordinates.split(":").let { (group, name) -> OnArtifact(group, name) })

    private fun rulesBroken(
        module: String,
        vararg edges: DeclaredEdge,
    ): List<Rule> = ArchitectureRules.violations(module, edges.toList()).map { it.rule }

    // [KMP]/[JVM] modules: no Android dependencies.

    @Test
    fun kmpModuleMayNotDependOnAnAndroidxArtifact() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":core:model", artifact("commonMainImplementation", "androidx.collection:collection")),
        )
    }

    @Test
    fun jvmModuleMayNotDependOnAComAndroidArtifact() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":core:domain", artifact("implementation", "com.android.billingclient:billing")),
        )
    }

    @Test
    fun jvmModuleMayNotDependOnAComGoogleAndroidArtifact() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":data:jellyfin", artifact("implementation", "com.google.android.material:material")),
        )
    }

    @Test
    fun androidArtifactRuleCoversTestConfigurationsToo() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":core:scheduler", artifact("jvmTestImplementation", "androidx.test:core")),
        )
    }

    @Test
    fun kmpModuleMayNotDependOnAnAndroidProject() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":core:engine", project("commonMainImplementation", ":core:ui")),
        )
    }

    @Test
    fun jvmModuleMayNotDependOnAnAndroidProject() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":core:domain", project("implementation", ":core:ui")),
        )
    }

    // feature:* isolation.

    @Test
    fun featureMayNotDependOnData() {
        assertEquals(
            listOf(Rule.FEATURE_ISOLATION),
            rulesBroken(":feature:live", project("implementation", ":data:jellyfin")),
        )
    }

    @Test
    fun featureMayNotDependOnAnotherFeature() {
        assertEquals(
            listOf(Rule.FEATURE_ISOLATION),
            rulesBroken(":feature:guide", project("implementation", ":feature:live")),
        )
    }

    // core:scheduler needs only core:model.

    @Test
    fun schedulerMayNotDependOnEngineOrRules() {
        assertEquals(
            listOf(Rule.SCHEDULER_INDEPENDENCE, Rule.SCHEDULER_INDEPENDENCE),
            rulesBroken(
                ":core:scheduler",
                project("commonMainImplementation", ":core:engine"),
                project("commonMainImplementation", ":core:rules"),
            ),
        )
    }

    // core:testing only in tests.

    @Test
    fun coreTestingMayNotBeUsedOutsideTests() {
        assertEquals(
            listOf(Rule.TESTING_IN_TESTS_ONLY),
            rulesBroken(":core:domain", project("implementation", ":core:testing")),
        )
    }

    @Test
    fun coreTestingMayNotBeUsedInTestFixtures() {
        assertEquals(
            listOf(Rule.TESTING_IN_TESTS_ONLY),
            rulesBroken(":core:domain", project("testFixturesImplementation", ":core:testing")),
        )
    }

    @Test
    fun coreTestingIsAllowedInEveryKindOfTestConfiguration() {
        val testConfigurations =
            listOf(
                "commonTestImplementation",
                "jvmTestImplementation",
                "linuxX64TestImplementation",
                "testImplementation",
                "testDebugImplementation",
                "androidTestImplementation",
                "githubDebugAndroidTestImplementation",
            )
        for (configuration in testConfigurations) {
            assertEquals(
                emptyList(),
                rulesBroken(":core:scheduler", project(configuration, ":core:testing")),
                configuration,
            )
        }
    }

    // The allowlist.

    @Test
    fun allowedEdgesPass() {
        assertEquals(
            emptyList(),
            rulesBroken(
                ":core:domain",
                project("implementation", ":core:engine"),
                project("implementation", ":core:scheduler"),
                project("implementation", ":core:model"),
                artifact("implementation", "javax.inject:javax.inject"),
            ),
        )
        assertEquals(emptyList(), rulesBroken(":data:settings", project("implementation", ":core:rules")))
        assertEquals(emptyList(), rulesBroken(":feature:live", project("implementation", ":core:ui")))
        assertEquals(emptyList(), rulesBroken(":core:ui", artifact("implementation", "androidx.tv:tv-material")))
    }

    @Test
    fun appMayDependOnAnyModule() {
        assertEquals(
            emptyList(),
            rulesBroken(
                ":app",
                project("implementation", ":data:jellyfin"),
                project("implementation", ":core:scheduler"),
                project("implementation", ":feature:live"),
            ),
        )
    }

    @Test
    fun testsMayUseCoreDomainTestFixtures() {
        assertEquals(
            emptyList(),
            rulesBroken(":data:jellyfin", project("testImplementation", ":core:domain", testFixtures = true)),
        )
    }

    @Test
    fun anEdgeNotOnTheAllowlistFails() {
        assertEquals(
            listOf(Rule.NOT_ALLOWLISTED),
            rulesBroken(":data:jellyfin", project("implementation", ":core:engine")),
        )
    }

    @Test
    fun testConfigurationsGetNoExtraProjectEdges() {
        assertEquals(
            listOf(Rule.NOT_ALLOWLISTED),
            rulesBroken(":core:model", project("commonTestImplementation", ":core:rules")),
        )
    }

    @Test
    fun aModuleMissingFromModulesMdFails() {
        assertEquals(listOf(Rule.UNKNOWN_MODULE), rulesBroken(":core:common"))
        assertEquals(
            listOf(Rule.UNKNOWN_MODULE),
            rulesBroken(":core:domain", project("implementation", ":data:xmltv")),
        )
    }

    // Reporting.

    @Test
    fun aViolationNamesTheModuleTheDependencyAndTheRule() {
        val message =
            ArchitectureRules
                .violations(":core:scheduler", listOf(project("commonMainImplementation", ":core:engine")))
                .single()
                .message
        assertTrue(":core:scheduler" in message, message)
        assertTrue(":core:engine" in message, message)
        assertTrue("commonMainImplementation" in message, message)
        assertTrue(Rule.SCHEDULER_INDEPENDENCE.id in message, message)
    }

    @Test
    fun anExceptionMustNameTheModuleAndDependencyExactly() {
        val edge = artifact("implementation", "androidx.annotation:annotation")
        val exception =
            ArchitectureException(":core:domain", "androidx.annotation:annotation", "reason: test only")
        assertEquals(emptyList(), ArchitectureRules.violations(":core:domain", listOf(edge), listOf(exception)))
        assertEquals(1, ArchitectureRules.violations(":data:jellyfin", listOf(edge), listOf(exception)).size)
    }

    @Test
    fun theProjectHasNoExceptions() {
        assertEquals(emptyList(), ArchitectureRules.EXCEPTIONS)
    }
}
