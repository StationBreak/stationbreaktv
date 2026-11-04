// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.scheduler.vectors.DayCases.caps
import tv.stationbreak.core.scheduler.vectors.DayCases.films
import tv.stationbreak.core.scheduler.vectors.DayCases.keyOrderItem
import tv.stationbreak.core.scheduler.vectors.KeyOrderV2VectorFile
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
 * The version 2 orders against `docs/rules-format/vectors/key-order-v2.json`, which an independent reference computed
 * from the format text: rotation turns, the capped shuffle and in order. A vector is never changed to suit the code.
 */
class KeyOrderV2VectorsTest {
    private val vectors = VectorJson.parse(KeyOrderV2VectorFile.text).array("vectors").objects()

    private fun forMode(mode: String) = vectors.filter { it.string("mode") == mode }

    private fun JsonObject.pool(): List<ScheduleItem> = array("items").objects().map { it.keyOrderItem() }

    private fun JsonObject.rules() = GroupRules((this["films"] as? JsonObject).films(), noWeights())

    private fun JsonObject.strings(name: String): List<String> = array(name).map { it.jsonPrimitive.content }

    private fun JsonObject.blockLength(): Int = getValue("blockLength").jsonPrimitive.content.toInt()

    private fun JsonObject.expectedRuns(): List<List<String>> {
        val runs = array("runs")
        return runs.map { run -> run.jsonArray.map { it.jsonPrimitive.content } }
    }

    private fun List<ScheduleItem>.ids(): List<String> = map { it.id.value }

    private companion object {
        const val HOURS_PER_DAY = 24L
    }

    @Test
    fun theFileHasTheCasesThePlanPromises() {
        assertTrue(vectors.size >= 25, "the file has ${vectors.size} cases")
        assertEquals(setOf("rotation", "shuffle", "inOrder"), vectors.map { it.string("mode") }.toSet())
    }

    @Test
    fun rotationGroupsKeysAndTurnsMatch() {
        val cases = forMode("rotation")
        assertTrue(cases.size >= 10, "rotation cases: ${cases.size}")
        for (case in cases) {
            val name = case.string("name")
            val seed = Seed(u64(case.getValue("seed")))
            val caps = case.obj("caps").caps()
            val rules = case.rules()
            val pool = case.pool()
            val groups = Orderings.groups(pool, seed, rules)
            val expected = case.array("groups").objects()
            assertEquals(expected.map { it.string("groupId") }, groups.map { it.groupId.value }, "$name groups")
            for ((want, got) in expected.zip(groups)) {
                assertEquals(u64(want.getValue("key")), got.key, "$name key of ${got.groupId}")
                assertEquals(want.strings("itemIds"), got.items.ids(), "$name items of ${got.groupId}")
                assertEquals(
                    want
                        .getValue("cap")
                        .jsonPrimitive.content
                        .toInt(),
                    rules.cap(got.items.first(), caps),
                    name,
                )
            }
            val epochMs = (case["epochHours"]?.jsonPrimitive?.content?.toLong() ?: HOURS_PER_DAY) * MS_HOUR
            val turns = Orderings.rotationTurns(pool, seed, caps, rules, epochMs)
            assertEquals(case.strings("order"), turns.flatten().ids(), "$name order")
            assertEquals(case.expectedRuns(), turns.map { it.ids() }, "$name turns")
        }
    }

    @Test
    fun cappedShuffleKeysOrderAndRunsMatch() {
        val cases = forMode("shuffle")
        assertTrue(cases.size >= 10, "capped shuffle cases: ${cases.size}")
        for (case in cases) {
            val name = case.string("name")
            val seed = Seed(u64(case.getValue("seed")))
            val seedN = u64(case.getValue("seedN"))
            assertEquals(seedN, Epochs.seedN(seed, case.string("n").toLong()), "$name seed_n")
            val caps = case.obj("caps").caps()
            val rules = case.rules()
            val pool = case.pool()
            for (key in case.array("keys").objects()) {
                val id = CanonicalId.parse(key.string("id"))
                assertEquals(u64(key.getValue("key")), Orderings.shuffleKey(seedN, id), "$name key of $id")
            }
            val shuffled = Orderings.shuffle(pool, seedN)
            assertEquals(case.strings("shuffleOrder"), shuffled.ids(), "$name shuffle order")
            for (group in case.array("groups").objects()) {
                val members = shuffled.filter { rules.groupId(it).value == group.string("groupId") }
                assertEquals(group.strings("itemIds"), members.ids(), "$name items of ${group.string("groupId")}")
                assertEquals(
                    group
                        .getValue("cap")
                        .jsonPrimitive.content
                        .toInt(),
                    rules.cap(members.first(), caps),
                    name,
                )
            }
            val order = cappedShuffle(pool, seedN, caps, rules)
            assertEquals(case.strings("order"), order.ids(), "$name order")
            assertEquals(case.expectedRuns(), Runs.form(order, case.blockLength()).map { it.items.ids() }, "$name runs")
        }
    }

    @Test
    fun inOrderGroupsOrderAndRunsMatch() {
        val cases = forMode("inOrder")
        assertTrue(cases.size >= 4, "inOrder cases: ${cases.size}")
        for (case in cases) {
            val name = case.string("name")
            val order = Orderings.inOrder(case.pool())
            assertEquals(case.strings("order"), order.ids(), "$name order")
            assertEquals(
                case.array("groups").objects().flatMap { it.strings("itemIds") },
                order.ids(),
                "$name groups, one after another",
            )
            assertEquals(case.expectedRuns(), Runs.form(order, case.blockLength()).map { it.items.ids() }, "$name runs")
        }
    }
}
