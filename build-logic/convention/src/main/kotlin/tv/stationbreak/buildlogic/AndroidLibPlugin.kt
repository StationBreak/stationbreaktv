// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** An Android library: the app's SDK levels, desugaring, toolchain and Lint, with no flavors. */
class AndroidLibPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply(QualityPlugin::class.java)

            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                // A library has no targetSdk of its own; its tests run against the app's.
                testOptions.targetSdk = libs.intVersion("targetSdk")
            }

            checkDependsOn("lintDebug")
        }
}
