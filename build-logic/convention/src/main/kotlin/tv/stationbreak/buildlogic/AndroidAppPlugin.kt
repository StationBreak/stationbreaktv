// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import tv.stationbreak.buildlogic.architecture.ArchitecturePlugin

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

            // `lint` checks only the default variant (amazonDebug); lint the github flavor too.
            checkDependsOn("lintAmazonDebug", "lintGithubDebug")

            // Licensee hooks itself into `check`; listing it keeps the gate if a plugin update stops doing so.
            pluginManager.withPlugin("app.cash.licensee") { checkDependsOn("licensee") }
        }
}
