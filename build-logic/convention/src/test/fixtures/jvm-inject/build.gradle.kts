// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    `java-library`
    id("stationbreak.jvm-inject")
}

// Every external dependency declared on any configuration, as "configuration group:name:version".
tasks.register("printDeclared") {
    val declared =
        provider {
            configurations.flatMap { configuration ->
                configuration.dependencies.map { "${configuration.name} ${it.group}:${it.name}:${it.version}" }
            }
        }
    doLast { declared.get().sorted().forEach { println("DECLARED $it") } }
}
