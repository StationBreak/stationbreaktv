// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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

            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(JDK_VERSION)
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

            // detekt 2.0.0-alpha.6 on KMP, listed from `:core:model:tasks --all` and pinned here (the alpha's names may
            // change; a missing name fails the build). detektMainJvm/detektTestJvm run every rule, including those
            // that need type resolution, over the jvm compilation (commonMain + jvmMain, and their tests). Native
            // has no type-resolution task, so linuxX64 code gets the source-set tasks, as does common code (cheap,
            // and it doesn't depend on which compilations include it).
            checkDependsOn(
                "detektMainJvm",
                "detektTestJvm",
                "detektCommonMainSourceSet",
                "detektCommonTestSourceSet",
                "detektJvmMainSourceSet",
                "detektJvmTestSourceSet",
                "detektLinuxX64MainSourceSet",
                "detektLinuxX64TestSourceSet",
            )
            // KMP has no plain `test`; `allTests` runs commonTest on both targets.
            checkDependsOn("allTests")
        }
}
