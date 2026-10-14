// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    `java-library`
    id("stationbreak.contract-test")
}

// What the plugin set up, read back as "KEY value" lines.
tasks.register("printContract") {
    val contract = tasks.named<Test>("contractTest")
    val checkDependsOn = tasks.named("check").map { check -> check.taskDependencies.getDependencies(check).map { it.name } }
    val sourceSetNames = sourceSets.names.toList()
    doLast {
        val task = contract.get()
        println("SOURCE_SETS ${sourceSetNames.sorted().joinToString(",")}")
        println("JUNIT_PLATFORM ${task.options is org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions}")
        println("CHECK_DEPENDS ${checkDependsOn.get().sorted().joinToString(",")}")
        println("ENV_IMAGE ${task.environment["JELLYFIN_IMAGE"]}")
        println("SYSTEM_PROPERTIES ${task.systemProperties.keys.sorted().joinToString(",")}")
        println("JVM_ARGS ${task.jvmArgs}")
        println("UP_TO_DATE_ALWAYS_RERUNS ${!task.outputs.upToDateSpec.isSatisfiedBy(task)}")
    }
}
