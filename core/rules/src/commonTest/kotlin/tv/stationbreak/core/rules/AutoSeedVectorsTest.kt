// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.rules.VectorJson.array
import tv.stationbreak.core.rules.VectorJson.objects
import tv.stationbreak.core.rules.VectorJson.string
import tv.stationbreak.core.rules.VectorJson.u64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The automatic-channel seed against `docs/rules-format/vectors/auto-seed.json` (computed by an independent reference):
 * `splitmix64(fnv1a64(server ID, 0x00, kind, 0x00, key))`, on every target.
 */
class AutoSeedVectorsTest {
    private val file = VectorJson.parse(RatingVectorFiles.autoSeed)
    private val rows = file.array("vectors").objects()

    private fun kind(name: String): AutoSeedKind? = AutoSeedKind.entries.firstOrNull { it.wireName == name }

    @Test
    fun everyVectorDerivesTheSameBytesHashAndSeed() {
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val kind = requireNotNull(kind(row.string("kind")))
            val context = "${row.string("kind")} ${row.string("key")}"
            val bytes = AutoSeed.bytes(row.string("serverId"), kind, row.string("key"))
            assertContentEquals(VectorJson.hexToBytes(row.string("bytesHex")), bytes, "bytes of $context")
            assertEquals(u64(row.getValue("fnv1a64")), fnv1a64(bytes), "hash of $context")
            assertEquals(u64(row.getValue("seed")), AutoSeed.derive(row.string("serverId"), kind, row.string("key")).value, context)
        }
    }

    @Test
    fun theFileHasALibraryACollectionANonAsciiGenreAndAHighBitSeed() {
        assertEquals(setOf("library", "collection", "genre"), rows.map { it.string("kind") }.toSet())
        assertTrue(rows.any { it.string("kind") == "genre" && it.string("key").any { c -> c.code > 127 } })
        val nonAsciiHigh = rows.filter { it.string("kind") == "genre" && it.string("key").any { c -> c.code > 127 } }
        assertTrue(nonAsciiHigh.any { u64(it.getValue("seed")) >= (1uL shl 63) }, "a non-ASCII genre with the high bit set")
        assertTrue(rows.any { u64(it.getValue("seed")) < (1uL shl 63) })
    }

    @Test
    fun aGenreIsNotCaseFoldedOrNormalised() {
        val seeds =
            rows.filter { it.string("kind") == "genre" && it.string("key").startsWith("Anim") }.map {
                it.string("key") to
                    u64(it.getValue("seed"))
            }
        assertTrue(seeds.size >= 4)
        assertEquals(seeds.size, seeds.map { it.second }.toSet().size, "Animé, animé and the decomposed form differ")
    }

    @Test
    fun theKindAndTheServerChangeTheSeed() {
        val server = "11111111111111111111111111111111"
        val id = "22222222222222222222222222222222"
        val a = AutoSeed.derive(server, AutoSeedKind.Library, id)
        assertTrue(a != AutoSeed.derive(server, AutoSeedKind.Collection, id))
        assertTrue(a != AutoSeed.derive("00000000000000000000000000000002", AutoSeedKind.Library, id))
    }

    @Test
    fun aNonCanonicalServerIdOrKeyIsRejected() {
        val invalid = file.array("invalid").objects()
        assertTrue(invalid.size >= 6)
        for (row in invalid) {
            val kind = kind(row.string("kind")) ?: continue // an unknown or upper-case kind cannot even be named
            assertFailsWith<IllegalArgumentException>(
                row.string("why"),
            ) { AutoSeed.derive(row.string("serverId"), kind, row.string("key")) }
        }
        assertTrue(invalid.any { kind(it.string("kind")) == null }, "the file has an unknown kind")
    }

    @Test
    fun aGenreWithAnUnpairedSurrogateIsRejectedNotSilentlyChanged() {
        assertFailsWith<IllegalArgumentException> { AutoSeed.derive("1".repeat(32), AutoSeedKind.Genre, "Bad\uD800") }
        assertFailsWith<IllegalArgumentException> { AutoSeed.derive("1".repeat(32), AutoSeedKind.Genre, "\uDC00Bad") }
    }

    @Test
    fun theSeedIsAValidCanonicalDecimalString() {
        for (row in rows) {
            val seed = AutoSeed.derive(row.string("serverId"), requireNotNull(kind(row.string("kind"))), row.string("key"))
            assertEquals(row.getValue("seed").let { (it as kotlinx.serialization.json.JsonObject).string("dec") }, seed.format())
        }
    }
}
