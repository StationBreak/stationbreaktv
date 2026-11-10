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
        assertTrue(repairs.any { r -> r.blocks.any { it.itemId != null } }, "some day airs programmes after it")
        val seqs = RepairMatrix.scenarios().map { s -> RepairMatrix.referenceOf(s.name).map { it.record.lastSeq } }
        assertTrue(seqs.any { it.size > 1 && it.distinct().size > 1 }, "some scenario changes twice, on repaired days")
        assertTrue(all.map { it.digest }.toSet().size > all.size / 2, "the stored files differ between scenarios")
        val byCutoff = RepairMatrix.itemIdsByCutoff()
        assertTrue(byCutoff.size > 1, "the days read more than one cut-off (${byCutoff.keys})")
        assertTrue(byCutoff.values.toSet().size > 1, "the snapshots of the cut-offs hold different items")
    }

    private companion object {
        // The SHA-256 of the lineup file stored after each step of each scenario, as the reference records it.
        val GOLDEN: Map<String, List<String>> =
            mapOf(
                "catalog: untick the 250-item library, days laid out" to
                    listOf(
                        "a520426a5953d5ca32a3741d6b8b56eadafaea0b4fb07b8cb2d479923dd8fb7c",
                    ),
                "catalog: untick the 250-item library, days not laid out" to
                    listOf(
                        "a520426a5953d5ca32a3741d6b8b56eadafaea0b4fb07b8cb2d479923dd8fb7c",
                    ),
                "catalog: second untick on repaired days" to
                    listOf(
                        "a520426a5953d5ca32a3741d6b8b56eadafaea0b4fb07b8cb2d479923dd8fb7c",
                        "ce9401e3277a074fd1638c23ab43c2681d2e31eb0d28166e2a3c66e9f4e665af",
                    ),
                "catalog: untick right after an add" to
                    listOf(
                        "a26476309d32a99ebe02fae549ed568fc06048e27ecf926a259996c1617f78b6",
                    ),
                "catalog: untick the one-genre library" to
                    listOf(
                        "da2f3b8ebb062f4110692f9f3ec8ae7611c4e1b2d158ddd1694adc13f0bb7df2",
                    ),
                "catalog: untick the biggest library, whose series another shares" to
                    listOf(
                        "5945f96ecf5f26a57a862ac2f3b28360781faf14634cb516a53ff21a90744a45",
                    ),
                "catalog: untick the library that shares a series" to
                    listOf(
                        "5268e8ed5caeb53527bb349163c8f6f34beba6b8f149b2aadf82ee971823352f",
                    ),
                "catalog: an add that lands with a remove" to
                    listOf(
                        "14ded88470374a6d40df92cebe7613fad43e423d43f49ef404613a4ac5eab669",
                    ),
                "catalog: tiny libraries, untick two at once" to
                    listOf(
                        "ddbc5bbc07073b4abf4aa8483fc3a4808607f1f9e1f652e784ee0bf786dacd3c",
                    ),
                "hand: no extras filter, untick the 50-item library" to
                    listOf(
                        "14d857597136d827aaa4f6763938ecbd14d07a48ec7e2f6631a43f27cafe0083",
                    ),
                "hand: extras filter from the start of time" to
                    listOf(
                        "7636ec54c9b27a60d7a7f5b5d9f526fced219f71d754ad68c48ba6ab524415f6",
                    ),
                "hand: extras cut inside the day, two cut-offs" to
                    listOf(
                        "21bc14045e1d0728ebf212ceac5c789ea67240f092927ef9164120bec3bc2796",
                    ),
                "hand: two cut-offs, items change between them" to
                    listOf(
                        "a58552b3f11dfc6ef34eedfdf3a382e51fcdc903d1daedc99d87dd6bb5960f69",
                    ),
                "hand: items deleted after the cut-off, then an untick" to
                    listOf(
                        "e0b3c49699f205790056d62bc907cf79050ca69ad91ab824c7a23c3407f63ec7",
                    ),
                "hand: items deleted, a second untick on the repaired days" to
                    listOf(
                        "21bc14045e1d0728ebf212ceac5c789ea67240f092927ef9164120bec3bc2796",
                        "28bb1747e3836e5f7c59858a4d2910f4fe51d4c7a8ccdbde44eb905dd561f5d8",
                    ),
                "hand: untick down to one library" to
                    listOf(
                        "78f9848d80f980424253ffa685cd0a9def87ea765ae0a189f932c87f119faaf3",
                    ),
                "hand: an empty selection after (S - R = {})" to
                    listOf(
                        "35874ef60d011e1999cb1f8de6b9cba691c34715edf5bf169633626a9de9dc39",
                    ),
                "hand: days not laid out, untick the biggest" to
                    listOf(
                        "b187908159ff62c28ceb11c2f918229cc5420521cb7238ad30e82e008f74770d",
                    ),
            )
    }
}
