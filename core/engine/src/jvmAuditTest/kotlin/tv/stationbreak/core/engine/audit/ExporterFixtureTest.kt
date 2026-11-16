// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixture's values.

package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.Json
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exporter's fixture (tools/audit-export/fixtures): the snapshot it makes of the fake server's answers, which the
 * app's own read of the same rows makes too (`AuditExportFixtureTest` in data:library). The audit reads it, and what
 * the exporter writes is what the audit's own writer writes, field for field.
 */
class ExporterFixtureTest {
    private val dir = File(System.getProperty("audit.export.fixtures") ?: error("The fixture folder is not set"))
    private val text = File(dir, "expected-snapshot.json").readText()

    @Test
    fun theAuditReadsTheFileTheExporterWritesAndWritesTheSameSnapshotBack() {
        val read = SnapshotFixture.read(text)
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(AuditSnapshotCodec.write(read)))
    }

    @Test
    fun everyFieldTheFormatNamesIsThereForTheOddItems() {
        val snapshot = SnapshotFixture.read(text).snapshot
        val byName = snapshot.items.associateBy { it.name }
        val film = byName.getValue("Velmiren Zudar")
        assertEquals("2000-01-01", film.item.premiereDate)
        assertEquals(1999, film.item.productionYear)
        assertEquals(1_680_674_828_123L, film.item.createdAtMs)
        assertEquals(listOf("Invented Pictures", "Net One"), film.studios)
        val episode = byName.getValue("Sixth")
        assertEquals(ScheduleItemKind.Episode, episode.item.kind)
        assertEquals(1, episode.item.seasonNumber)
        assertEquals(3, episode.item.episodeNumber)
        val kinds = snapshot.collections.values.map { listOf(it.movies, it.episodes, it.series, it.seasons) }
        assertTrue(kinds.any { sets -> sets.all { it.isNotEmpty() } })
    }

    @Test
    fun theAuditRunsOnItAndIsTheSameEveryTime() {
        val input = SnapshotFixture.read(text)
        val first = AuditText.render(LineupAudit.run(input))
        assertEquals(first, AuditText.render(LineupAudit.run(SnapshotFixture.read(text))))
        assertTrue(first.contains("LIBRARY:"))
    }
}
