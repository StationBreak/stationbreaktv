// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
    fun chronologicalOrderMatches() {
        val cases = forMode("chronological")
        assertTrue(cases.size >= 10, "chronological cases: ${cases.size}")
        for (case in cases) {
            assertEquals(case.expectedOrder(), Orderings.chronological(case.pool()).ids(), case.string("name"))
        }
    }

    @Test
    fun theWorkedExampleOfTheFormatDocIsThere() {
        val case = vectors.first { it.string("name").startsWith("chronological, the format doc's worked example") }
        val order = Orderings.chronological(case.pool()).ids()
        // special, then the three 2020-01-01 items by group ID (1, 2, movie 4), then 01-02, 01-08, and the undated last.
        assertEquals(listOf('e', 'a', 'c', '4', 'd', 'b', 'f'), order.map { it.first() })
    }

    @Test
    fun randomInOrderGroupsKeysAndWalkMatch() {
        val cases = forMode("randomInOrder")
        assertTrue(cases.size >= 10, "randomInOrder cases: ${cases.size}")
        for (case in cases) {
            val name = case.string("name")
            val seed = Seed(u64(case.getValue("seed")))
            val pool = case.pool()
            val groups = Orderings.groups(pool, seed)
            val expected = case.array("groups").objects()
            assertEquals(expected.map { it.string("groupId") }, groups.map { it.groupId.value }, "$name groups")
            for ((want, got) in expected.zip(groups)) {
                assertEquals(u64(want.getValue("key")), got.key, "$name key of ${got.groupId}")
                assertEquals(want.array("itemIds").map { it.jsonPrimitive.content }, got.items.ids(), "$name items")
            }
            val turns = Orderings.randomInOrderTurns(pool, seed, case.blockLength())
            assertEquals(case.expectedOrder(), turns.flatten().ids(), "$name order")
            assertEquals(case.expectedRuns(), turns.map { it.ids() }, "$name turns")
        }
    }

    @Test
    fun theGroupKeyUsesTheChannelSeedAndTheGroupId() {
        val case = vectors.first { it.string("name").startsWith("randomInOrder, the format doc's worked example") }
        val seed = Seed(u64(case.getValue("seed")))
        for (group in case.array("groups").objects()) {
            val id = CanonicalId.parse(group.string("groupId"))
            assertEquals(u64(group.getValue("key")), Orderings.groupKey(seed, id))
        }
    }

    private fun JsonObject.blockLength(): Int = getValue("blockLength").jsonPrimitive.content.toInt()

    private fun JsonObject.expectedRuns(): List<List<String>> = array("runs").map { run -> run.jsonArray.map { it.jsonPrimitive.content } }

    @Test
    fun theShuffleSeedNComesFromTheChannelSeedAndN() {
        val case = vectors.first { it.containsKey("n") }
        assertEquals(
            u64(case.getValue("seedN")),
            Epochs.seedN(Seed(u64(case.getValue("seed"))), case.string("n").toLong()),
        )
    }
}
