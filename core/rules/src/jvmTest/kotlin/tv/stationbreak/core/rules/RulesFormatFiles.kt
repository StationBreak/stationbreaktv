// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import tools.jackson.databind.json.JsonMapper
import java.io.File

/** Reads the published rules-format files (schema, examples, document) from the repository (spec 07, Unit 1). */
internal object RulesFormatFiles {
    val dir: File =
        File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" })

    val schemaFile: File = File(dir, "channel-rules.v1.schema.json")

    /** `docs/rules-format.md`, which sits beside the `docs/rules-format/` folder. */
    val docFile: File = File(dir.parentFile, "rules-format.md")

    private val mapper = JsonMapper()

    /** The JSON files directly in `examples/` (or `examples/invalid/`) by name; empty if the folder is missing. */
    private fun jsonFiles(sub: String): Map<String, String> =
        File(dir, sub)
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .associate { it.name to it.readText() }

    val valid: Map<String, String> get() = jsonFiles("examples")
    val invalid: Map<String, String> get() = jsonFiles("examples/invalid")

    /** Parses JSON text into plain maps, lists, strings, numbers and booleans. */
    fun parse(text: String): Any? = mapper.readValue(text, Any::class.java)
}
