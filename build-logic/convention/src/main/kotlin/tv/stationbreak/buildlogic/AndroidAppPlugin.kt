// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import tv.stationbreak.buildlogic.architecture.ArchitecturePlugin
import tv.stationbreak.buildlogic.architecture.R8ConfigCheck

/** The Android application: SDK levels, the distribution flavors, desugaring, the JDK 17 toolchain and Lint. */
class AndroidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            // AGP 9 compiles Kotlin itself; the Kotlin version is pinned in the root build.
            pluginManager.apply("com.android.application")
            pluginManager.apply(QualityPlugin::class.java)
            pluginManager.apply(ArchitecturePlugin::class.java)

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.intVersion("targetSdk")

                flavorDimensions += "distribution"
                productFlavors {
                    create("amazon") { dimension = "distribution" }
                    create("github") { dimension = "distribution" }
                }
            }

            // #437: the merged R8 configuration of every minified variant is scanned right after R8 writes it, so the
            // `assemble` of a release or benchmark build (CI's Assemble job) fails on a risky consumer rule.
            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    if (!variant.isMinifyEnabled) return@onVariants
                    val capitalised = variant.name.replaceFirstChar { it.uppercase() }
                    val guard =
                        tasks.register<R8ConfigCheck>("checkR8Config$capitalised") {
                            group = LifecycleBasePlugin.VERIFICATION_GROUP
                            description = "Fails if the merged R8 configuration of $capitalised has a risky rule."
                            // AGP writes configuration.txt beside mapping.txt.
                            configuration.set(layout.buildDirectory.file("outputs/mapping/${variant.name}/configuration.txt"))
                            report.set(layout.buildDirectory.file("reports/r8/${variant.name}.txt"))
                            appRules.set(layout.projectDirectory.file("proguard-rules.pro").asFile.absolutePath)
                            gradleUserHome.set(gradle.gradleUserHomeDir.absolutePath)
                            rootProjectDir.set(rootProject.projectDir.absolutePath)
                            dependsOn("minify${capitalised}WithR8")
                        }
                    tasks.named { it == "minify${capitalised}WithR8" }.configureEach { finalizedBy(guard) }
                }
            }

            // `lint` checks only the default variant (amazonDebug); lint the github flavor too.
            checkDependsOn("lintAmazonDebug", "lintGithubDebug")

            // Licensee hooks itself into `check`; listing it keeps the gate if a plugin update stops doing so.
            pluginManager.withPlugin("app.cash.licensee") { checkDependsOn("licensee") }
        }
}
