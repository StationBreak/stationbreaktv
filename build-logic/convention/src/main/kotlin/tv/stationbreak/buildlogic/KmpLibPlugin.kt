// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import tv.stationbreak.buildlogic.architecture.ArchitecturePlugin

/**
 * A pure-Kotlin shared-core module (ADR-0004): `commonMain` code, a `jvm()` target that Android modules consume,
 * and a `linuxX64()` canary target. The canary is never published; it exists so the compiler rejects `java.*` in
 * `commonMain`.
 */
class KmpLibPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply(QualityPlugin::class.java)
            pluginManager.apply(ArchitecturePlugin::class.java)

            configureKotlinToolchain()

            extensions.configure<KotlinMultiplatformExtension> {
                jvm()
                linuxX64()

                sourceSets.getByName("commonTest").dependencies {
                    implementation(kotlin("test"))
                }
            }

            extensions.configure<DetektExtension> {
                // Forbids javax.inject (JVM-only); java.* is rejected by the linuxX64 canary's compiler.
                config.from(repoRoot.file("config/detekt/kmp.yml"))
            }

            // detekt's task names are pinned because it's an alpha (a missing name fails the build). Native has no
            // type-resolution task, so linuxX64 code gets the source-set tasks.
            checkDependsOn(
                "detektMainJvm",
                "detektTestJvm",
                "detektCommonMainSourceSet",
                "detektCommonTestSourceSet",
                "detektJvmMainSourceSet",
                "detektJvmTestSourceSet",
                "detektLinuxX64MainSourceSet",
                "detektLinuxX64TestSourceSet",
                // KMP has no plain `test`; `allTests` runs commonTest on both targets.
                "allTests",
            )
        }
}
