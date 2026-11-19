// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.TestAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

// #436: the security tests run against the R8-minified app from a small test APK (`:security-minified-test-hilt`). The app's
// R8 run for that build keeps what the test APK references, found by R8's own `tracereferences`, and nothing else.
// This is only on when the build is asked for it: `-Pstationbreak.minifiedSecurityTest=true`.
private const val MINIFIED_SECURITY_PROPERTY = "stationbreak.minifiedSecurityTest"
private const val MINIFIED_VARIANT = "githubBenchmark"
private val TEST_PROJECTS = listOf(":security-minified-test-hilt", ":security-minified-test-trust")
private const val CLASSES_USAGE = "stationbreak-security-test-classes"

/** Only the value `true` turns the minified security run on; `=false`, or any other text, leaves the build as it is. */
internal fun minifiedSecurityRequested(value: String?): Boolean = value.toBoolean()

internal fun Project.minifiedSecurityTestOn(): Boolean =
    providers.gradleProperty(MINIFIED_SECURITY_PROPERTY).map(::minifiedSecurityRequested).getOrElse(false)

/**
 * For `:security-minified-test-hilt`: hands its classes, and everything its test APK carries, to the app as a jar, so the
 * app can trace what they reference. Only the variant that targets the minified app has one.
 */
class SecurityTestClassesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val classesJar =
                tasks.register<ClassesJar>("securityTestClassesJar") {
                    description = "Packs the security test APK's classes for tracereferences (#436)."
                    output.set(layout.buildDirectory.file("security-test/classes.jar"))
                }
            configurations.consumable("securityTestClasses") {
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(CLASSES_USAGE))
                outgoing.artifact(classesJar.flatMap { it.output })
            }
            if (minifiedSecurityTestOn()) {
                // AGP makes the test module's Java compile wait for `checkTestedAppObfuscation`, which reads the app's
                // finished R8 output. With the app's R8 reading this module's classes that is a cycle. The app keeps
                // its class names (-dontobfuscate, guarded by R8ConfigCheck), and the check still runs before the
                // test APK is packaged, so only the order changes.
                // After AGP has made its tasks and wired them.
                afterEvaluate {
                    tasks.named("compile${MINIFIED_VARIANT.replaceFirstChar { c -> c.uppercase() }}JavaWithJavac") {
                        setDependsOn(dependsOn.filterNot { "checkTestedAppObfuscation" in it.toString() })
                    }
                }
            }
            pluginManager.withPlugin("com.android.test") {
                val components = extensions.getByType(TestAndroidComponentsExtension::class.java)
                // Only the variant that instruments the minified app is built: no Hilt KSP run for the others.
                components.beforeVariants { it.enable = it.name == MINIFIED_VARIANT }
                components.onVariants(components.selector().withName(MINIFIED_VARIANT)) { variant ->
                    variant.artifacts
                        .forScope(ScopedArtifacts.Scope.ALL)
                        .use(classesJar)
                        .toGet(ScopedArtifact.CLASSES, ClassesJar::jars, ClassesJar::dirs)
                }
            }
        }
    }
}

/**
 * For the app: the keep rules `tracereferences` writes for [variant], or null when this build is not the minified
 * security test run. They are added to the variant's R8 configuration and handed back so the R8 config guard can
 * trust that one file (R8ConfigRules).
 */
internal fun Project.minifiedSecurityKeepRules(variant: ApplicationVariant): Provider<RegularFile>? {
    if (variant.name != MINIFIED_VARIANT || !minifiedSecurityTestOn()) return null
    // Declared in `securityTestClasses` (the one configuration the architecture check lets name the test module), read
    // through the resolvable one beside it.
    val declared = configurations.dependencyScope("securityTestClasses")
    val testClasses: Configuration =
        configurations
            .resolvable("securityTestClassesPath") {
                extendsFrom(declared.get())
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(CLASSES_USAGE))
            }.get()
    val r8Declared = configurations.dependencyScope("r8Tool")
    val r8: Configuration =
        configurations
            .resolvable("r8ToolPath") {
                extendsFrom(r8Declared.get())
                isTransitive = false
            }.get()
    dependencies {
        TEST_PROJECTS.forEach { add("securityTestClasses", project(it)) }
        // AGP runs this jar's R8; tracereferences must read the same R8's view of the app.
        add("r8Tool", "com.android.tools.build:builder:${libs.version("agp")}")
    }
    val appClasses =
        tasks.register<ClassesJar>("securityTargetClasses") {
            description = "Packs the R8 input of $MINIFIED_VARIANT: what the security test APK may reference (#436)."
            output.set(layout.buildDirectory.file("security-test/app-classes.jar"))
        }
    variant.artifacts
        .forScope(ScopedArtifacts.Scope.ALL)
        .use(appClasses)
        .toGet(ScopedArtifact.CLASSES, ClassesJar::jars, ClassesJar::dirs)
    val bootClasspath =
        extensions
            .getByType(ApplicationAndroidComponentsExtension::class.java)
            .sdkComponents.bootClasspath
    val trace =
        tasks.register<TraceReferencesKeepRules>("traceSecurityTestReferences") {
            description = "Writes the keep rules for what the security test APK references in $MINIFIED_VARIANT (#436)."
            this.r8.from(r8)
            source.from(testClasses)
            target.set(appClasses.flatMap { it.output })
            libraries.from(bootClasspath)
            keepRules.set(layout.buildDirectory.file("security-test/keep-rules.pro"))
        }
    val rules = trace.flatMap { it.keepRules }
    variant.proguardFiles.add(rules)
    return rules
}
