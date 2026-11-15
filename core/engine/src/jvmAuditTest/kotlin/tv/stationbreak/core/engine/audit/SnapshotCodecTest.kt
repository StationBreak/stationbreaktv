// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixture's values.

package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.engine.audit.SnapshotFixture.customRules
import tv.stationbreak.core.engine.audit.SnapshotFixture.full
import tv.stationbreak.core.engine.audit.SnapshotFixture.id
import tv.stationbreak.core.engine.audit.SnapshotFixture.read
import tv.stationbreak.core.engine.audit.SnapshotFixture.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotCodecTest {
    @Test
    fun everyFieldSurvivesAWriteAndARead() {
        val snapshot = full()
        val back = read(text()).snapshot
        assertEquals(snapshot, back)
        // Data class equality covers the fields; spot-check the ones a mapper could lose.
        assertEquals("", back.items[0].officialRating)
        assertEquals(" ", back.items[1].officialRating)
        assertEquals(listOf("Drama", "Crime"), back.items[0].genres)
        assertEquals("1999-12-31", back.items[0].item.premiereDate)
        assertEquals(1999, back.items[0].item.productionYear)
        assertEquals(2, back.items[1].item.seasonNumber)
        assertEquals(7, back.items[1].item.episodeNumber)
        assertEquals(5L, back.items[1].item.createdAtMs)
        assertEquals(9L, back.items[1].item.deletedAtMs)
        assertEquals(id(0xA0), back.items[0].libraryId)
        assertEquals(id(0x600), back.items[1].seasonId)
        assertNull(back.items[1].name)
        assertFalse(back.collectionsSynced)
    }

    @Test
    fun theOutputIsDeterministicAndInCanonicalOrder() {
        val a = text()
        assertEquals(a, text(full().let { it.copy(items = it.items.reversed()) }))
        assertTrue(a.indexOf(id(0x10).value) < a.indexOf(id(0x20).value))
        assertTrue(a.startsWith("{\n  \"format\": \"audit-snapshot\",\n  \"version\": 1,"))
        assertTrue(a.endsWith("}\n"))
    }

    @Test
    fun theReaderPutsItemsInCanonicalOrder() {
        // An out-of-order file, as an exporter might write it: the items reversed.
        val root = Json.parseToJsonElement(text()).jsonObject
        val items = root.getValue("items").jsonArray.reversed()
        val reversed = JsonObject(root + ("items" to JsonArray(items)))
        assertEquals(full().items, read(reversed.toString()).snapshot.items)
    }

    @Test
    fun theViewersChoicesRoundTrip() {
        val viewer = Viewer(listOf("alpha", "bravo"), listOf(CustomChannel("Mine", customRules())))
        val back = read(AuditSnapshotCodec.write(AuditInput(full(), viewer)))
        assertEquals(viewer.switchedOn, back.viewer?.switchedOn)
        assertEquals(
            "Mine",
            back.viewer
                ?.customChannels
                ?.single()
                ?.name,
        )
        assertEquals(
            customRules(),
            back.viewer
                ?.customChannels
                ?.single()
                ?.rules,
        )
    }

    @Test
    fun aSnapshotWithNoViewerHasNone() {
        assertNull(read(text()).viewer)
    }
}
