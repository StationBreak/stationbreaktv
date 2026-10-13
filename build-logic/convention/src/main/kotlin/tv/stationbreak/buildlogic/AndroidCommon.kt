// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import tv.stationbreak.buildlogic.architecture.ManifestCheck

// Shared by android-app and android-lib.
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = libs.intVersion("compileSdk")
    android.defaultConfig.minSdk = libs.intVersion("minSdk")
    android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    android.compileOptions.apply {
        // java.time and friends on API 25 (MODULES.md: desugaring on from day one).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    android.lint.apply {
        abortOnError = true
        checkReleaseBuilds = true
        // Raises every Security-category issue to fatal (the DSL's `fatal` takes issue IDs, not categories).
        lintConfig = repoRoot.file("config/lint/lint.xml").asFile
        targetSdk = libs.intVersion("targetSdk")
    }

    configureKotlinToolchain()

    dependencies {
        add("coreLibraryDesugaring", libs.library("desugar-jdk-libs"))
        add("testImplementation", libs.library("junit"))
    }

    // detekt's Android tasks that run type resolution (the plain `detekt` task skips those rules).
    checkDependsOn("detektMain", "detektTest")

    // R-PLAT-1: no tools:overrideLibrary in any of this module's manifests.
    val checkOverrideLibrary =
        tasks.register<ManifestCheck>("checkOverrideLibrary") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if any src/**/AndroidManifest.xml uses tools:overrideLibrary."
            manifests.from(fileTree("src") { include("**/AndroidManifest.xml") })
            report.set(layout.buildDirectory.file("reports/manifests/override-library.txt"))
        }
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checkOverrideLibrary) }
}
