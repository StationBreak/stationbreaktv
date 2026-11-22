// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.scheduler.EpochPool
import tv.stationbreak.core.scheduler.Epochs
import java.io.File
import kotlin.system.exitProcess

/**
 * The gate's fixtures and its command line (docs/design/lineup-seats-and-gaps.md): `lineupGate` in Gradle runs it.
 *
 *     GateMain --fixtures DIR --golden DIR [--warnings FILE] [--update-goldens]
 *
 * It runs every case, prints the hard-fail violations and the soft warnings, writes the warnings to FILE, and compares
 * each report with `DIR/CASE.json` of the golden folder (or writes them with `--update-goldens`). Hard failures and a
 * golden that differs fail it (exit 1); warnings never do. Every `*.json` in the fixtures folder that no case names
 * runs with the defaults: the place to drop in another snapshot.
 */
internal object GateMain {
    /** The day build's epoch cut, by the scheduler's own `EpochPool.build`. */
    val SCHEDULER_EPOCH =
        EpochCut { settings, items, dayStartMs ->
            EpochPool.build(settings, items, Epochs.epochNumber(settings, dayStartMs))
        }

    /** The cases of the frozen fixtures the repository holds. A fixture file may serve more than one case. */
    val CASES: List<GateCase> =
        listOf(
            GateCase("anilist-only", "anilist-only.json", leftoverCapHours = LEFTOVER_CAP_ANILIST),
            GateCase("edge-cases", "edge-cases.json", leftoverCapHours = LEFTOVER_CAP_EDGE, kidsHomes = true),
            GateCase(
                "edge-cases-after-hours",
                "edge-cases.json",
                afterHours = true,
                leftoverCapHours = LEFTOVER_CAP_EDGE,
                kidsHomes = true,
            ),
        )

    // The pinned leftover hours per fixture (soft warnings): a little over what the goldens hold today.
    private const val LEFTOVER_CAP_ANILIST = 10
    private const val LEFTOVER_CAP_EDGE = 20
    private const val EXIT_FAILED = 1
    private const val EXIT_USAGE = 2

    /** [CASES] and a default case for each other fixture file in [dir], in a fixed order. */
    fun casesIn(dir: File): List<GateCase> {
        val named = CASES.mapTo(HashSet()) { it.file }
        val others = dir.listFiles { f -> f.extension == "json" && f.name !in named }.orEmpty().sortedBy { it.name }
        return CASES + others.map { GateCase(it.nameWithoutExtension, it.name) }
    }

    fun inputOf(
        dir: File,
        case: GateCase,
    ): AuditInput =
        when (val result = AuditSnapshotCodec.read(File(dir, case.file).readText())) {
            is SnapshotResult.Ok -> result.input
            is SnapshotResult.Invalid -> error("The fixture ${case.file} is not usable: ${result.reason}")
        }

    @JvmStatic
    fun main(args: Array<String>) {
        val values =
            args
                .toList()
                .chunked(2)
                .filter { it.size == 2 }
                .associate { it[0] to it[1] }
        val fixtures = File(values["--fixtures"] ?: usage())
        val golden = File(values["--golden"] ?: usage())
        val update = "--update-goldens" in args
        var failed = false
        val warnings = ArrayList<String>()
        for (case in casesIn(fixtures)) {
            val result = LineupGate.run(case, inputOf(fixtures, case), SCHEDULER_EPOCH)
            warnings += result.warnings
            failed = report(result, golden, update) || failed
        }
        warnings.forEach { println("warning: $it") }
        values["--warnings"]?.let { path ->
            File(path).also { it.parentFile?.mkdirs() }.writeText(warnings.joinToString("\n", postfix = "\n"))
        }
        println("${warnings.size} warnings (report only).")
        if (failed) exitProcess(EXIT_FAILED)
    }

    // Prints one case's failures; true when it failed (a violation, or a golden that differs).
    private fun report(
        result: GateResult,
        golden: File,
        update: Boolean,
    ): Boolean {
        val file = File(golden, "${result.case.key}.json")
        if (update) file.also { it.parentFile?.mkdirs() }.writeText(result.report)
        val drift = !update && (!file.isFile || file.readText() != result.report)
        result.violations.forEach { println("FAIL ${result.case.key}: $it") }
        if (drift) println("FAIL ${result.case.key}: ${file.name} is out of date: run :core:engine:lineupGateGoldens")
        println("${result.case.key}: ${result.violations.size} violations")
        return result.violations.isNotEmpty() || drift
    }

    private fun usage(): Nothing {
        System.err.println("Usage: GateMain --fixtures DIR --golden DIR [--warnings FILE] [--update-goldens]")
        exitProcess(EXIT_USAGE)
    }
}
