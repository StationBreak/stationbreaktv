// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.withType
import tv.stationbreak.buildlogic.architecture.ArchitecturePlugin

/** A plain Kotlin/JVM module (`[JVM]` in MODULES.md) on the JDK 17 toolchain, tested with kotlin.test on JUnit 5. */
class JvmLibPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply(QualityPlugin::class.java)
            pluginManager.apply(ArchitecturePlugin::class.java)

            configureKotlinToolchain()

            dependencies {
                // KGP picks the kotlin-test-junit5 variant from the test task's framework.
                add("testImplementation", kotlin("test"))
            }
            tasks.withType<Test>().configureEach { useJUnitPlatform() }

            // detekt's JVM tasks that run type resolution.
            checkDependsOn("detektMain", "detektTest")
        }
}
