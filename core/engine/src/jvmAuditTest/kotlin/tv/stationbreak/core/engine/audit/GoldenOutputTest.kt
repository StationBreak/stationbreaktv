// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The audit's output on each profile library is pinned: `core/engine/src/audit/golden/KEY.txt` and `KEY.json`. A change
 * to the planner, the curation, the catalog or the audit that moves them shows as a diff of these files. Make them
 * again, after reading the diff, with `./gradlew :core:engine:lineupAuditGoldens`.
 */
class GoldenOutputTest {
    private val dir = File(System.getProperty("audit.golden.dir") ?: error("The golden folder is not set"))

    private fun check(
        key: String,
        extension: String,
        actual: String,
    ) {
        val file = File(dir, "$key.$extension")
        assertTrue(file.isFile, "${file.name} is missing: run :core:engine:lineupAuditGoldens")
        // The words of the failure name the file and the task; the diff is in git, so the text is not printed.
        val hint = "${file.name} is out of date: run :core:engine:lineupAuditGoldens and read the diff"
        assertEquals(file.readText(), actual, hint)
    }

    @Test
    fun everyProfileLibraryHasItsPinnedTextAndJson() {
        for (profile in ProfileLibraries.all()) {
            val report = LineupAudit.run(profile.input)
            check(profile.key, "txt", AuditText.render(report))
            check(profile.key, "json", AuditJson.render(report))
        }
    }

    @Test
    fun theAuditIsDeterministic() {
        val profile = ProfileLibraries.build("sitcom-household")
        assertEquals(
            AuditJson.render(LineupAudit.run(profile.input)),
            AuditJson.render(LineupAudit.run(ProfileLibraries.build("sitcom-household").input)),
        )
    }

    @Test
    fun noGoldenIsLeftOverFromAProfileThatWentAway() {
        val expected = ProfileLibraries.keys.flatMap { listOf("$it.txt", "$it.json") }.toSet()
        assertEquals(expected, dir.list().orEmpty().toSet())
    }
}
