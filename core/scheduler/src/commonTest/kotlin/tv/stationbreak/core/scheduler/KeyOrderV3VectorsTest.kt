// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.scheduler.vectors.DayCases.canonical
import tv.stationbreak.core.scheduler.vectors.DayCases.films
import tv.stationbreak.core.scheduler.vectors.DayCases.keyOrderItem
import tv.stationbreak.core.scheduler.vectors.KeyOrderV3VectorFile
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import tv.stationbreak.core.scheduler.vectors.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `curated` walk against `docs/rules-format/vectors/key-order-v3.json`, which an independent reference computed
 * from the format text: the groups and their keys, start indexes, weights and queues, and the runs in order. A vector
 * is never changed to suit the code.
 */
class KeyOrderV3VectorsTest {
    private val vectors = VectorJson.parse(KeyOrderV3VectorFile.text).array("vectors").objects()

    private fun List<ScheduleItem>.ids(): List<String> = map { it.id.value }

    @Test
    fun theFileHasAtLeastTwelveCases() {
        assertTrue(vectors.size >= 12, "${vectors.size} cases")
        assertEquals(vectors.size, vectors.map { it.string("name") }.toSet().size, "case names are unique")
    }

    @Test
    fun everyCaseAgreesWithTheReference() {
        val failures = vectors.flatMap { failuresOf(it) }
        val report = "${failures.size} disagreements with the reference:\n" + failures.joinToString("\n")
        assertTrue(failures.isEmpty(), report)
    }

    private fun JsonObject.intField(name: String): Int = string(name).toInt()

    private fun JsonObject.runsOf(name: String): List<List<String>> =
        array(name).map { run -> run.jsonArray.map { it.jsonPrimitive.content } }

    private fun JsonObject.weights(): TitleWeights =
        TitleWeights.of(
            obj("weights").entries.associate { (id, w) -> canonical(id) to w.jsonPrimitive.content.toInt() },
        )

    private fun JsonObject.settings() =
        testSettings(
            OrderingMode.Curated,
            alignToGrid = string("alignToGrid").toBoolean(),
            gridMinutes = intField("gridMinutes"),
            repeatGapMs = string("repeatGapMs").toLong(),
            pairUpToMs = string("pairUpToMs").toLong(),
        )

    private fun failuresOf(case: JsonObject): List<String> {
        val name = case.string("name")
        val failures = mutableListOf<String>()

        fun expect(
            what: String,
            expected: Any?,
            actual: Any?,
        ) {
            if (expected != actual) failures.add("[$name] $what: reference $expected, Kotlin $actual")
        }
        val seedN = u64(case.getValue("seedN"))
        expect("seed_n", seedN, Epochs.seedN(Seed(u64(case.getValue("seed"))), case.string("n").toLong()))
        val pool = case.array("items").objects().map { it.keyOrderItem() }
        val weights = case.weights()
        val rules = GroupRules((case["franchises"] as? JsonObject).films(), weights)
        val members = pool.groupBy { rules.groupId(it) }
        val groups = case.array("groups").objects()
        expect("group IDs", groups.map { it.string("groupId") }, members.keys.map { it.value }.sorted())
        for (group in groups) {
            val id = CanonicalId.parse(group.string("groupId"))
            val key = Orderings.shuffleKey(seedN, id)
            val items = members.getValue(id)
            expect("key of $id", u64(group.getValue("key")), key)
            expect("size of $id", group.intField("size"), items.size)
            expect("startIndex of $id", group.intField("startIndex"), (key % items.size.toULong()).toInt())
            expect("weight of $id", group.intField("weight"), items.maxOf { weights[it.seriesId ?: it.id] })
            expect("kind of $id", group.string("kind"), kindName(rules.kind(items.first())))
        }
        val runs = curatedRuns(pool, seedN, rules, case.settings())
        val ids = runs.map { it.items.ids() }
        expect("runs", case.runsOf("runs"), ids)
        expect("order", case.array("order").map { it.jsonPrimitive.content }, ids.flatten())
        val walked = case.array("walk").objects().map { step -> step.array("run").map { it.jsonPrimitive.content } }
        expect("walk, run by run", walked, ids)
        return failures
    }

    private fun kindName(kind: GroupKind): String =
        when (kind) {
            GroupKind.Series -> "series"
            GroupKind.Collection -> "franchise"
            GroupKind.Single -> "single"
        }
}
