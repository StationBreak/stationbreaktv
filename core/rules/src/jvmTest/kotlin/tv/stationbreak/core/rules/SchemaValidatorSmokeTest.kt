// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Proves the test-only JSON Schema 2020-12 validator loads a schema and tells valid from invalid (spec 07, task 1.1).
// The rules-format schema tests (task 1.0) build on this.
class SchemaValidatorSmokeTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                """
                {
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "required": ["formatVersion"],
                  "properties": { "formatVersion": { "type": "integer", "minimum": 1 } }
                }
                """.trimIndent(),
            )

    @Test
    fun acceptsAValidInstance() {
        assertTrue(schema.validate("""{"formatVersion": 1}""", InputFormat.JSON).isEmpty())
    }

    @Test
    fun rejectsAnInvalidInstance() {
        assertEquals(1, schema.validate("""{"formatVersion": 0}""", InputFormat.JSON).size)
        assertTrue(schema.validate("""{}""", InputFormat.JSON).isNotEmpty())
    }
}
