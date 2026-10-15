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
    fun pureModuleMayNotDependOnAJetBrainsAndroidxArtifact() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":core:model", artifact("commonMainImplementation", "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel")),
        )
    }

    @Test
    fun pureModuleMayNotDependOnHiltAndroid() {
        assertEquals(
            listOf(Rule.NO_ANDROID_IN_PURE_MODULES),
            rulesBroken(":data:jellyfin", artifact("implementation", "com.google.dagger:hilt-android")),
        )
    }

    @Test
    fun pureModuleMayStillUseNonAndroidDagger() {
        assertEquals(emptyList(), rulesBroken(":data:jellyfin", artifact("implementation", "com.google.dagger:dagger")))
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

    @Test
    fun androidFeatureModuleMayUseHiltAndItsNavigationAndViewModelLibraries() {
        // F0.5: an Android feature:* module may apply stationbreak.hilt for @HiltViewModel. The pure modules still may not.
        assertEquals(
            emptyList(),
            rulesBroken(
                ":feature:onboarding",
                artifact("implementation", "com.google.dagger:hilt-android"),
                artifact("implementation", "androidx.hilt:hilt-navigation-compose"),
                artifact("implementation", "androidx.lifecycle:lifecycle-viewmodel-compose"),
                project("implementation", ":core:domain"),
                project("implementation", ":core:model"),
                project("implementation", ":core:ui"),
                project("testImplementation", ":core:domain", testFixtures = true),
            ),
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

    // The Jellyfin SDK and OkHttp stay inside data:jellyfin (ADR-0005, F0.3 D10).

    @Test
    fun coreDomainMayNotDeclareTheJellyfinSdk() {
        assertEquals(
            listOf(Rule.SDK_CONFINED),
            rulesBroken(":core:domain", artifact("implementation", "org.jellyfin.sdk:jellyfin-core")),
        )
    }

    @Test
    fun appMayNotDeclareTheJellyfinSdk() {
        assertEquals(
            listOf(Rule.SDK_CONFINED),
            rulesBroken(":app", artifact("implementation", "org.jellyfin.sdk:jellyfin-api-okhttp")),
        )
    }

    // The one carve-out: app's instrumented tests may use the SDK's own client, to pin the SLF4J binding it needs
    // (ADR-0006). Nothing the APK ships can.
    @Test
    fun appMayDeclareTheJellyfinSdkInItsInstrumentedTestsOnly() {
        for (configuration in listOf("androidTestImplementation", "githubDebugAndroidTestImplementation")) {
            assertEquals(
                emptyList(),
                rulesBroken(":app", artifact(configuration, "org.jellyfin.sdk:jellyfin-core")),
                configuration,
            )
        }
        for (configuration in listOf("implementation", "runtimeOnly", "debugImplementation", "testFixturesApi")) {
            assertEquals(
                listOf(Rule.SDK_CONFINED),
                rulesBroken(":app", artifact(configuration, "org.jellyfin.sdk:jellyfin-core")),
                configuration,
            )
        }
    }

    @Test
    fun noOtherModuleMayDeclareTheJellyfinSdkInATest() {
        assertEquals(
            listOf(Rule.SDK_CONFINED),
            rulesBroken(":core:domain", artifact("testImplementation", "org.jellyfin.sdk:jellyfin-core")),
        )
    }

    @Test
    fun coreModelMayNotDeclareOkHttp() {
        assertEquals(
            listOf(Rule.OKHTTP_CONFINED),
            rulesBroken(":core:model", artifact("commonMainImplementation", "com.squareup.okhttp3:okhttp")),
        )
    }

    @Test
    fun coreDomainMayNotDeclareOkHttpEvenInTests() {
        assertEquals(
            listOf(Rule.OKHTTP_CONFINED),
            rulesBroken(":core:domain", artifact("testImplementation", "com.squareup.okhttp3:mockwebserver3")),
        )
    }

    @Test
    fun noModuleMayDeclareOkHttpsLoggingInterceptor() {
        // R18: it would print URLs, headers and tokens. Debug builds included, so the configuration doesn't matter.
        for (module in listOf(":data:jellyfin", ":app", ":core:domain", ":feature:player")) {
            for (configuration in listOf("implementation", "debugImplementation", "testImplementation")) {
                assertEquals(
                    listOf(Rule.NO_HTTP_LOGGING),
                    rulesBroken(module, artifact(configuration, "com.squareup.okhttp3:logging-interceptor")),
                    "$module $configuration",
                )
            }
        }
    }

    @Test
    fun dataJellyfinMayDeclareTheSdkAndOkHttp() {
        assertEquals(
            emptyList(),
            rulesBroken(
                ":data:jellyfin",
                artifact("implementation", "org.jellyfin.sdk:jellyfin-core"),
                artifact("implementation", "org.jellyfin.sdk:jellyfin-api-okhttp"),
                artifact("implementation", "com.squareup.okhttp3:okhttp"),
            ),
        )
    }

    @Test
    fun dataJellyfinTestConfigurationsMayDeclareOkHttpHelpers() {
        assertEquals(
            emptyList(),
            rulesBroken(
                ":data:jellyfin",
                artifact("testImplementation", "com.squareup.okhttp3:mockwebserver3"),
                artifact("testImplementation", "com.squareup.okhttp3:okhttp-tls"),
            ),
        )
    }

    @Test
    fun appMayDeclareOkHttp() {
        assertEquals(emptyList(), rulesBroken(":app", artifact("implementation", "com.squareup.okhttp3:okhttp")))
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
    fun coreDomainTestFixturesMayNotBeUsedOutsideTests() {
        for (configuration in listOf("implementation", "api", "testFixturesImplementation")) {
            assertEquals(
                listOf(Rule.TESTFIXTURES_IN_TESTS_ONLY),
                rulesBroken(":data:jellyfin", project(configuration, ":core:domain", testFixtures = true)),
                configuration,
            )
        }
        assertEquals(
            listOf(Rule.TESTFIXTURES_IN_TESTS_ONLY),
            rulesBroken(":app", project("implementation", ":core:domain", testFixtures = true)),
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
