// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import java.io.File
import kotlin.system.exitProcess

/**
 * The lineup audit's command line (tools/audit-export/README.md): `lineupAudit` in Gradle runs it.
 *
 *     AuditMain --snapshot FILE | --profile KEY|all  [--format text|json|both]  [--out DIR]
 *
 * With no `--out` the text goes to standard output (`--format json` prints the JSON). With `--out` each report is
 * written to `DIR/NAME.txt` and `DIR/NAME.json`, NAME being the profile key or `snapshot`: never a name from the file.
 * `--update-goldens DIR` is `--profile all --format both --out DIR`, the way the pinned outputs are made again.
 */
internal object AuditMain {
    private const val EXIT_USAGE = 2
    private const val EXIT_BAD_INPUT = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args.toList()) ?: usage()
        val reports = LinkedHashMap<String, AuditReport>()
        if (options.snapshot != null) {
            reports["snapshot"] = LineupAudit.run(readSnapshot(options.snapshot))
        } else {
            for (profile in profiles(options.profile)) reports[profile.key] = LineupAudit.run(profile.input)
        }
        for ((name, report) in reports) emit(name, report, options)
    }

    private fun profiles(key: String?): List<Profile> =
        when {
            key == null -> usage()
            key == "all" -> ProfileLibraries.all()
            key in ProfileLibraries.keys -> listOf(ProfileLibraries.build(key))
            else -> usage()
        }

    private fun readSnapshot(path: String): AuditInput {
        val file = File(path)
        if (!file.isFile) fail("The snapshot file is not a file.")
        if (file.length() > AuditSnapshotCodec.MAX_CHARS) fail("The snapshot file is too big.")
        return when (val result = AuditSnapshotCodec.read(file.readText())) {
            is SnapshotResult.Ok -> result.input
            is SnapshotResult.Invalid -> fail("The snapshot is not usable: ${result.reason}")
        }
    }

    private fun emit(
        name: String,
        report: AuditReport,
        options: Options,
    ) {
        val text = AuditText.render(report)
        val json = AuditJson.render(report)
        val out = options.out
        if (out == null) {
            print(if (options.format == "json") json else text)
            return
        }
        val dir = File(out).also { it.mkdirs() }
        if (options.format != "json") File(dir, "$name.txt").writeText(text)
        if (options.format != "text") File(dir, "$name.json").writeText(json)
        System.err.println("Wrote the audit of $name to the output folder.")
    }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(EXIT_BAD_INPUT)
    }

    private fun usage(): Nothing {
        System.err.println(
            "Usage: AuditMain --snapshot FILE | --profile KEY|all [--format text|json|both] [--out DIR]" +
                " | --update-goldens DIR\nProfiles: ${ProfileLibraries.keys.joinToString(", ")}",
        )
        exitProcess(EXIT_USAGE)
    }

    private class Options(
        val snapshot: String?,
        val profile: String?,
        val format: String,
        val out: String?,
    ) {
        companion object {
            private val FLAGS = setOf("--snapshot", "--profile", "--format", "--out", "--update-goldens")
            private val FORMATS = setOf("text", "json", "both")

            fun parse(args: List<String>): Options? {
                val pairs = args.chunked(2)
                val values = pairs.filter { it.size == 2 }.associate { it[0] to it[1] }
                // --update-goldens DIR is --profile all --format both --out DIR.
                val goldens = values["--update-goldens"]
                val snapshot = values["--snapshot"]
                val profile = if (goldens != null) "all" else values["--profile"]
                val format = if (goldens != null) "both" else values["--format"] ?: "text"
                val wellFormed = pairs.all { it.size == 2 && it[0] in FLAGS } && format in FORMATS
                val valid = wellFormed && (snapshot != null) != (profile != null)
                return if (valid) Options(snapshot, profile, format, goldens ?: values["--out"]) else null
            }
        }
    }
}
