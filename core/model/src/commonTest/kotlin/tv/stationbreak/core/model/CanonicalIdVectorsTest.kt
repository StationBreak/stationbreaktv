// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import tv.stationbreak.core.model.ordering.VectorFiles
import tv.stationbreak.core.model.ordering.VectorJson
import tv.stationbreak.core.model.ordering.VectorJson.array
import tv.stationbreak.core.model.ordering.VectorJson.objects
import tv.stationbreak.core.model.ordering.VectorJson.string
import tv.stationbreak.core.model.ordering.VectorJson.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `CanonicalId` against `docs/rules-format/vectors/canonical-id.json`, on every target. */
class CanonicalIdVectorsTest {
    private val rows = VectorJson.parse(VectorFiles.canonicalId).array("vectors").objects()

    @Test
    fun serverFormsNormaliseToTheCanonicalForm() {
        assertTrue(rows.size >= 15)
        for (row in rows) {
            val input = row.string("input")
            assertEquals(row.stringOrNull("normalised"), CanonicalId.parseOrNull(input)?.value, "normalise '$input'")
        }
    }

    @Test
    fun theStrictCheckRepairsNothing() {
        for (row in rows) {
            val input = row.string("input")
            assertEquals(row.stringOrNull("strict"), CanonicalId.parseCanonicalOrNull(input)?.value, "strict '$input'")
        }
    }

    @Test
    fun theFileCoversRejectionsAndEachRepair() {
        assertTrue(rows.any { it.stringOrNull("normalised") == null })
        assertTrue(rows.any { it.string("input").contains('-') && it.stringOrNull("normalised") != null })
        assertTrue(rows.any { it.string("input").startsWith("{") && it.stringOrNull("normalised") != null })
        assertTrue(rows.any { it.string("input").any(Char::isUpperCase) && it.stringOrNull("normalised") != null })
    }
}
