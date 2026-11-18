// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the Kotlin port to the "Stan In Motion" motion study (the source of truth): for every scene and every time in
 * the fixture, the frame must be the same, shape for shape and in the same order, with the same path strings and the
 * same rounded numbers.
 *
 * The fixture, `src/test/resources/stan/stan-scenes-fixture.json`, is the study's own `SCENE[k](t)` for each scene at
 * t in {0, 0.4, 1, 1.5, 2, 2.7, 3, 3.6, 4.2, 5, 6.5, 9.1, 13.3}. It was generated from the pure part of the study's
 * script (`stan-motion-core.js`, no DOM) with its `fixtures.js`, which is deterministic:
 *
 *     node fixtures.js > stan-scenes-fixture.json
 *
 * If the motion study changes, regenerate the fixture the same way and update the port until this passes again.
 */
class StanFixtureTest {
    private val fixture: JsonObject =
        Json
            .parseToJsonElement(
                checkNotNull(javaClass.getResourceAsStream("/stan/stan-scenes-fixture.json")) { "fixture missing" }
                    .bufferedReader()
                    .readText(),
            ).jsonObject

    @Test
    fun theFixtureCoversEveryScene() {
        assertEquals(StanScene.entries.map { it.key }.toSet(), fixture.keys)
        for (scene in StanScene.entries) assertEquals(TIMES.size, fixture.getValue(scene.key).jsonObject.size)
    }

    @Test
    fun everySceneAtEveryTimeIsTheStudysFrame() {
        for (scene in StanScene.entries) {
            val times = fixture.getValue(scene.key).jsonObject
            for ((time, expected) in times) {
                assertSame("${scene.key} at t=$time", expected, scene.frame(time.toDouble()).toJson())
            }
        }
    }

    @Test
    fun theComparisonSeesADifference() {
        // Guard against a comparison that passes whatever it is given.
        val frame = StanScene.Standby.frame(1.0).toJson()
        val expected = fixture.getValue("standby").jsonObject.getValue("1")
        assertSame("control", expected, frame)
        val other = fixture.getValue("standby").jsonObject.getValue("0")
        val failed = runCatching { assertSame("control", other, frame) }.isFailure
        assertTrue(failed)
    }

    private fun assertSame(
        where: String,
        expected: JsonElement,
        actual: JsonElement,
    ) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals("$where: keys", expected.keys, actual.keys)
                for ((k, v) in expected) assertSame("$where.$k", v, actual.getValue(k))
            }

            expected is JsonArray && actual is JsonArray -> {
                assertEquals("$where: length", expected.size, actual.size)
                expected.indices.forEach { assertSame("$where[$it]", expected[it], actual[it]) }
            }

            expected is JsonPrimitive && actual is JsonPrimitive -> {
                assertPrimitive(where, expected, actual)
            }

            else -> {
                assertEquals(where, expected, actual)
            }
        }
    }

    private fun assertPrimitive(
        where: String,
        expected: JsonPrimitive,
        actual: JsonPrimitive,
    ) {
        val number = !expected.isString && expected.content.toDoubleOrNull() != null
        if (number) {
            assertEquals(where, expected.double, actual.double, 0.0)
        } else {
            assertEquals(where, expected, actual)
        }
    }

    private companion object {
        val TIMES = listOf(0.0, 0.4, 1.0, 1.5, 2.0, 2.7, 3.0, 3.6, 4.2, 5.0, 5.5, 5.9, 6.1, 6.5, 9.1, 11.5, 13.3)
    }
}

// The study's JSON shape for a frame, so the comparison is on the study's own field names.

private fun StanFrame.toJson(): JsonObject =
    buildJsonObject {
        put("bg", if (bg == StanBackground.Left) "L" else "C")
        put("back", back.toJson())
        put(
            "shadow",
            buildJsonObject {
                put("cx", shadow.cx)
                put("cy", shadow.cy)
                put("rx", shadow.rx)
                put("ry", shadow.ry)
            },
        )
        put("x", x)
        put("y", y)
        put("sc", sc)
        put("rot", rot)
        put("sy", sy)
        put("stan", stan.toJson())
        put("blocks", blocks?.let { JsonPrimitive(it) } ?: JsonNull)
        put(
            "title",
            title?.let {
                buildJsonObject {
                    put("a1", it.nameStationAlpha)
                    put("a2", it.nameBreakAlpha)
                    put("a3", it.taglineAlpha)
                    put("dy", it.offsetY)
                }
            } ?: JsonNull,
        )
        put("fade", fade)
    }

private fun List<StanShape>.toJson(): JsonArray = buildJsonArray { forEach { add(it.toJson()) } }

private fun StanShape.toJson(): JsonObject =
    when (val s = this) {
        is StanStroke -> {
            shape("stroke", "d" to s.d, "c" to s.c.token, "w" to s.w, "a" to s.a)
        }

        is StanFill -> {
            shape("fill", "d" to s.d, "c" to s.c.token, "a" to s.a)
        }

        is StanCircle -> {
            shape("circle", "x" to s.x, "y" to s.y, "r" to s.r, "c" to s.c.token, "a" to s.a, "sw" to s.sw)
        }

        is StanOval -> {
            shape("oval", "x" to s.x, "y" to s.y, "rx" to s.rx, "ry" to s.ry, "c" to s.c.token, "a" to s.a)
        }

        is StanRoundRect -> {
            s.toJson()
        }

        is StanGlyph -> {
            s.toJson()
        }

        is StanPush -> {
            shape("push", "rot" to s.rot, "px" to s.px, "py" to s.py)
        }

        is StanClip -> {
            shape("clip", "d" to s.d)
        }

        StanPop -> {
            shape("pop")
        }

        is StanSnow -> {
            shape("snow", "a" to s.a, "tile" to s.tile, "roll" to s.roll)
        }

        is StanCrescent -> {
            s.toJson()
        }
    }

private fun shape(
    kind: String,
    vararg fields: Pair<String, Any>,
): JsonObject =
    buildJsonObject {
        put("k", kind)
        for ((name, value) in fields) {
            when (value) {
                is String -> put(name, value)
                is Double -> put(name, value)
                is Int -> put(name, value)
                is Boolean -> put(name, value)
                else -> error("unexpected $name")
            }
        }
    }

private fun StanRoundRect.toJson() =
    shape(
        "rrect",
        "x" to x,
        "y" to y,
        "w" to w,
        "h" to h,
        "r" to r,
        "c" to c.token,
        "rot" to rot,
        "px" to px,
        "py" to py,
        "sc" to sc?.token.orEmpty(),
        "sw" to sw,
    )

private fun StanGlyph.toJson() =
    shape(
        "glyph",
        "ch" to ch,
        "x" to x,
        "y" to y,
        "size" to size,
        "c" to c.token,
        "a" to a,
        "mid" to mid,
    )

private fun StanCrescent.toJson() =
    shape(
        "crescent",
        "x" to x,
        "y" to y,
        "r" to r,
        "ox" to ox,
        "oy" to oy,
        "or" to or,
        "c" to c.token,
    )
