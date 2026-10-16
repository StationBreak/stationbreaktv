// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.scheduler.vectors.KeyOrderVectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import tv.stationbreak.core.scheduler.vectors.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The orderings and run formation against `docs/rules-format/vectors/key-order.json`, which an independent reference
 * computed from the format text. A vector is never changed to suit the code.
 */
class KeyOrderVectorsTest {
    private val vectors = VectorJson.parse(KeyOrderVectorFile.text).array("vectors").objects()

    private fun forMode(mode: String) = vectors.filter { it.string("mode") == mode }

    private fun JsonObject.pool(): List<ScheduleItem> = array("items").objects().map { it.toItem() }

    private fun JsonObject.toItem(): ScheduleItem {
        fun optional(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
        return ScheduleItem(
            id = CanonicalId.parse(string("id")),
            kind = if (string("kind") == "movie") ScheduleItemKind.Movie else ScheduleItemKind.Episode,
            runtimeMs = 30 * MS_MINUTE,
            createdAtMs = 0L,
            deletedAtMs = null,
            premiereDate = optional("premiere"),
            productionYear = this["year"]?.jsonPrimitive?.intOrNull,
            seriesId = optional("seriesId")?.let { CanonicalId.parse(it) },
            seasonNumber = this["season"]?.jsonPrimitive?.intOrNull,
            episodeNumber = this["episode"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.expectedOrder(): List<String> = array("order").map { it.jsonPrimitive.content }

    private fun List<ScheduleItem>.ids(): List<String> = map { it.id.value }

    @Test
    fun theFileHasTheCasesThePlanPromises() {
        assertTrue(vectors.size >= 41, "the file has ${vectors.size} cases")
        assertEquals(setOf("shuffle", "chronological", "randomInOrder"), vectors.map { it.string("mode") }.toSet())
    }

    @Test
    fun shuffleKeysAndOrderMatch() {
        val cases = forMode("shuffle")
        assertTrue(cases.size >= 8, "shuffle cases: ${cases.size}")
        for (case in cases) {
            val name = case.string("name")
            val seedN = u64(case.getValue("seedN"))
            val pool = case.pool()
            for (key in case.array("keys").objects()) {
                val id = CanonicalId.parse(key.string("id"))
                assertEquals(u64(key.getValue("key")), Orderings.shuffleKey(seedN, id), "$name key of $id")
            }
            assertEquals(case.expectedOrder(), Orderings.shuffle(pool, seedN).ids(), name)
        }
    }

    @Test
    fun theShuffleSeedNComesFromTheChannelSeedAndN() {
        val case = vectors.first { it.containsKey("n") }
        assertEquals(
            u64(case.getValue("seedN")),
            Epochs.seedN(Seed(u64(case.getValue("seed"))), case.string("n").toLong()),
        )
    }
}
