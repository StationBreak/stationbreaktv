// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
    // Room's processor runs through KSP, not kapt: AGP 9's built-in Kotlin doesn't support kapt.
    alias(libs.plugins.ksp)
}

android {
    namespace = "tv.stationbreak.data.library"

    testOptions {
        // Room's JVM tests build a Context stand-in; the stubbed android.jar must answer instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // The schema is versioned and exported so a change shows in review (spec 06, Unit 2).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // The first-sync worker (spec 06 task 3.0). Never in core:* (checkArchitecture).
    implementation(libs.androidx.work.runtime)

    testImplementation(testFixtures(project(":core:domain")))
    // The generated library the cache tests write (F0.6). Test sources only (checkArchitecture).
    testImplementation(project(":core:testing"))
    // The edit-cutoff property test builds real days from the as-of read (spec 09 task 2.11). Test only (checkArchitecture).
    testImplementation(project(":core:scheduler"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The JVM build of the bundled SQLite driver: Room runs on the host in unit tests (test only, never in an APK).
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    // One test class declares a second schema, to prove a different schema is dropped and rebuilt (CacheRebuildTest).
    kspTest(libs.androidx.room.compiler)

    androidTestImplementation(libs.kotlinx.coroutines.core)
    // The real WorkManager's test driver, for the stay-current work tests on the emulator (test only).
    androidTestImplementation(libs.androidx.work.testing)
    // The heap test on the emulator pages the generated library through the scripted server (test sources only).
    androidTestImplementation(testFixtures(project(":core:domain")))
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// SchemaFile and CacheRebuildTest read the exported schema straight off disk, so Gradle must see it as an input.
tasks.withType<Test>().configureEach {
    inputs
        .dir("schemas")
        .withPropertyName("exportedSchemas")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
