// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.model.Seed
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
    private val highBit = 1uL shl 63

    private fun kind(name: String): AutoSeedKind? = AutoSeedKind.entries.firstOrNull { it.wireName == name }

    private fun JsonObject.derive(): Seed {
        val kind = requireNotNull(kind(string("kind")))
        return AutoSeed.derive(string("serverId"), kind, string("key"))
    }

    private fun JsonObject.nonAsciiGenre(): Boolean = string("kind") == "genre" && string("key").any { it.code > 127 }

    @Test
    fun everyVectorDerivesTheSameBytesHashAndSeed() {
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val kind = requireNotNull(kind(row.string("kind")))
            val context = "${row.string("kind")} ${row.string("key")}"
            val bytes = AutoSeed.bytes(row.string("serverId"), kind, row.string("key"))
            assertContentEquals(VectorJson.hexToBytes(row.string("bytesHex")), bytes, "bytes of $context")
            assertEquals(u64(row.getValue("fnv1a64")), fnv1a64(bytes), "hash of $context")
            assertEquals(u64(row.getValue("seed")), row.derive().value, context)
        }
    }

    @Test
    fun theFileHasALibraryACollectionARecipeANonAsciiGenreAndAHighBitSeed() {
        assertEquals(setOf("library", "collection", "genre", "recipe"), rows.map { it.string("kind") }.toSet())
        assertTrue(rows.any { it.nonAsciiGenre() })
        val nonAscii = rows.filter { it.nonAsciiGenre() }
        assertTrue(nonAscii.any { u64(it.getValue("seed")) >= highBit }, "non-ASCII, high bit")
        assertTrue(rows.any { u64(it.getValue("seed")) < highBit })
    }

    @Test
    fun aGenreIsNotCaseFoldedOrNormalised() {
        val genres = rows.filter { it.string("kind") == "genre" }
        val animes = genres.filter { it.string("key").startsWith("Anim", ignoreCase = true) }
        assertTrue(animes.size >= 4)
        val seeds = animes.map { u64(it.getValue("seed")) }
        assertEquals(seeds.size, seeds.toSet().size, "Animé, animé and the decomposed form differ")
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
            assertFailsWith<IllegalArgumentException>(row.string("why")) {
                AutoSeed.derive(row.string("serverId"), kind, row.string("key"))
            }
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
            assertEquals((row.getValue("seed") as JsonObject).string("dec"), row.derive().format())
        }
    }
}
