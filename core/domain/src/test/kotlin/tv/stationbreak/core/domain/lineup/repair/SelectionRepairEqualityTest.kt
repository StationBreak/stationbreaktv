// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Part of the oracle for #684 (with `ReferenceSelectionRepair`, `RepairWorld` and `RepairMatrix`): DO NOT EDIT in a
// later #684 PR, golden digests included. The mutants that may be updated are in `SelectionRepairMutantsTest`.

/**
 * The oracle for #684 (a faster untick repair, with the same bytes): the repair of a selection change that main
 * records must equal, step for step, what the frozen [ReferenceSelectionRepair] records, over the [RepairMatrix] of
 * libraries, lineups and changes. "Equal" is the recorded `ChannelRepair`s (the whole record and the change's pins)
 * and the SHA-256 of the stored lineup file, so a repair that differs in one block, one fix or one pinned library
 * fails. Later #684 PRs (the planning dispatcher, one library walk, parallel lanes) leave this test as it is and
 * must keep it green.
 *
 * Three runs: the real `TodayRepairer` against the reference (the one that gates), the production planner driven by
 * the test's own copy of the turn against the reference (proves that copy is faithful, so the mutants mean
 * something), and the reference against its own golden digests (below). The deliberately broken variants are in
 * [SelectionRepairMutantsTest].
 *
 * The matrix reaches an empty selection (`after` with nothing in it, what `S - R = {}` would be) although the
 * product cannot get there: `LibrarySelection` answers `LastLibrary` before any repair. The later PRs read `removed`
 * and `after` by position, and an empty `after` is the edge that breaks such code first.
 *
 * The reference calls live code (`LineupDays`, `KeptWindow`, `layoutTail`, `Fills`, the in-memory fake...). If a
 * change to one of them alters what the reference records, [referenceStillRecordsWhatItDid] fails: the reference's
 * own output is pinned by [GOLDEN], the SHA-256 of the file stored after each step of each scenario. A change of the
 * fixtures (`RepairMatrix`, `SyntheticLib`) moves the golden values too; they are then re-made from a run of the
 * reference and reviewed as the oracle changing.
 */
internal class SelectionRepairEqualityTest {
    @Test
    fun theRepairerRecordsWhatTheReferenceRecords() {
        val failures = RepairMatrix.mismatches(null)
        assertTrue(failures.isEmpty(), "the repairer differs from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun theProductionPlannerInTheTestsOwnTurnRecordsWhatTheReferenceRecords() {
        val failures = RepairMatrix.mismatches(Planners.production)
        assertTrue(failures.isEmpty(), "the planner differs from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun theReferenceInTheTestsOwnTurnRecordsWhatItsOwnRunRecords() {
        // The same planner twice gives the same bytes: nothing in the matrix (a clock, an order) varies between runs.
        val failures = RepairMatrix.mismatches(Planners.reference)
        assertTrue(failures.isEmpty(), "the reference differs from itself:\n${failures.joinToString("\n")}")
    }

    @Test
    fun referenceStillRecordsWhatItDid() {
        val actual =
            RepairMatrix.scenarios().associate { it.name to RepairMatrix.referenceOf(it.name).map { s -> s.digest } }
        val drift = actual.filter { (name, digests) -> GOLDEN[name] != digests }.keys
        assertEquals(
            emptySet<String>(),
            drift,
            "the reference no longer records what it did (golden digests moved) for these scenarios; the code it " +
                "calls changed, or the fixtures did. Now:\n" + goldenSource(actual),
        )
        assertEquals(GOLDEN.keys, actual.keys, "every scenario has a golden entry")
    }

    private fun goldenSource(actual: Map<String, List<String>>): String =
        actual.entries.joinToString("\n") { (name, digests) ->
            "        \"$name\" to listOf(${digests.joinToString { "\"$it\"" }}),"
        }

    @Test
    fun theMatrixReachesWhatItClaimsToPin() {
        val all = RepairMatrix.scenarios().flatMap { RepairMatrix.referenceOf(it.name) }
        val repairs = all.flatMap { it.record.selection }
        assertTrue(all.any { it.pins.isNotEmpty() }, "some change pins a programme of a removed library")
        assertTrue(repairs.any { it.tailHidden != null }, "some day has nothing to air after the change")
        assertTrue(repairs.any { it.pinLibrary == null }, "some day has no pin (hidden at the change, or none on air)")
        assertTrue(repairs.any { r -> r.blocks.any { it.programId() != null } }, "some day airs programmes after it")
        val seqs = RepairMatrix.scenarios().map { s -> RepairMatrix.referenceOf(s.name).map { it.record.lastSeq } }
        assertTrue(seqs.any { it.size > 1 && it.distinct().size > 1 }, "some scenario changes twice, on repaired days")
        assertTrue(all.map { it.digest }.toSet().size > all.size / 2, "the stored files differ between scenarios")
        val byCutoff = RepairMatrix.itemIdsByCutoff()
        assertTrue(byCutoff.size > 1, "the days read more than one cut-off (${byCutoff.keys})")
        assertTrue(byCutoff.values.toSet().size > 1, "the snapshots of the cut-offs hold different items")
        assertTrue(RepairMatrix.extrasDropped().all { it > 0 }, "a filtered snapshot differs from its plain one")
    }

    private companion object {
        // The SHA-256 of the lineup file stored after each step of each scenario, as the reference records it.
        val GOLDEN: Map<String, List<String>> =
            mapOf(
                "catalog: untick the 250-item library, days laid out" to
                    listOf(
                        "34980da3b2655e8e15d023a68a6e0ab978e7deb2035788a7797ffa39d55d109a",
                    ),
                "catalog: untick the 250-item library, days not laid out" to
                    listOf(
                        "34980da3b2655e8e15d023a68a6e0ab978e7deb2035788a7797ffa39d55d109a",
                    ),
                "catalog: second untick on repaired days" to
                    listOf(
                        "34980da3b2655e8e15d023a68a6e0ab978e7deb2035788a7797ffa39d55d109a",
                        "9f963925e2df7e1709586c88a087c26ab348b28a17be704c93289e92cbe9736c",
                    ),
                "catalog: untick right after an add" to
                    listOf(
                        "50f98f710e040589eba47386a4b1836323fda08734d3889d4c939758852e5815",
                    ),
                "catalog: untick the one-genre library" to
                    listOf(
                        "7cb95444d666ca7dbfe621bf4cb3b5cb5cfdcd0712f1c4c383dc2d60ebf87922",
                    ),
                "catalog: untick the biggest library, whose series another shares" to
                    listOf(
                        "663bafefd64c947a454d1b64796225605f99ccc0bce7e2e2b9bfe13754afc066",
                    ),
                "catalog: untick the library that shares a series" to
                    listOf(
                        "24e6517a62d622a7d41c336ae687b88a31d25a1fb1532bb98c4a417d5218ca36",
                    ),
                "catalog: an add that lands with a remove" to
                    listOf(
                        "1be4134647f303597fb5898d43e48c73e8c1e3a8b7a4789b8b25ba81face37ba",
                    ),
                "catalog: tiny libraries, untick two at once" to
                    listOf(
                        "e645dd06a687c8420934b2d52f1310bc98b458018e4772558779900da0fcb9fa",
                    ),
                "hand: no extras filter, untick the 50-item library" to
                    listOf(
                        "72bfdded481f4c2764fc216a1b08f4b618c9f82ea6da34c478f3025b5ce74e0a",
                    ),
                "hand: extras filter from the start of time" to
                    listOf(
                        "176a89acd8d27c95933744c14d69724e96569b133c1929bbfb068611c256d2cf",
                    ),
                "hand: extras cut inside the day, two cut-offs" to
                    listOf(
                        "a9dfdf877b1e097a563ce86d65553d80d1e84d8b0ed9141824cf0388602f8d00",
                    ),
                "hand: two cut-offs, items change between them" to
                    listOf(
                        "3b091996f9eacb83793469f02f58dc885b3ff595cd616be2de991119c4999e2c",
                    ),
                "hand: items deleted after the cut-off, then an untick" to
                    listOf(
                        "70f5964cee367f33cfbac3fa12e800f62516be3aad002314b02ae0f1609c5770",
                    ),
                "hand: items deleted, a second untick on the repaired days" to
                    listOf(
                        "a9dfdf877b1e097a563ce86d65553d80d1e84d8b0ed9141824cf0388602f8d00",
                        "0a99a2217d63e324f46dba101a8477d64ca4837c65736b3d04b5ed7453bb0165",
                    ),
                "hand: untick down to one library" to
                    listOf(
                        "36b30a0149cfaaef9e9a1accbc220e82e2b12f0dc645b7ca4247319547a7c7e8",
                    ),
                "hand: an empty selection after (S - R = {})" to
                    listOf(
                        "47e6e6b46a8c268bff54da9a9bb0c8aef72c7928decbf3283930b095376e5b29",
                    ),
                "hand: days not laid out, untick the biggest" to
                    listOf(
                        "da4fa632bf9b5bd19f697c5d3386a8daa6ca5e27edea51e0b8f6d87d23391927",
                    ),
            )
    }
}
