// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Spec 09 task 2.1 (Vimes M1): a version row holds its genres and tags as one canonical column each. The encoding keeps
 * the order, tells the empty list from a list of one empty name, and survives any character inside a name.
 */
class VersionCodecTest {
    private fun roundTrip(names: List<String>) = assertEquals(names, VersionCodec.decode(VersionCodec.encode(names)))

    @Test
    fun theEmptyListRoundTrips() {
        roundTrip(emptyList())
        assertEquals("", VersionCodec.encode(emptyList()))
    }

    @Test
    fun oneNameRoundTrips() = roundTrip(listOf("Comedy"))

    @Test
    fun theOrderIsKept() {
        roundTrip(listOf("b", "a", "c"))
        assertNotEquals(VersionCodec.encode(listOf("a", "b")), VersionCodec.encode(listOf("b", "a")))
    }

    @Test
    fun aListOfOneEmptyNameIsNotTheEmptyList() {
        roundTrip(listOf(""))
        roundTrip(listOf("", ""))
        assertNotEquals(VersionCodec.encode(emptyList()), VersionCodec.encode(listOf("")))
    }

    @Test
    fun separatorsAndDigitsInsideANameCannotBreakTheEncoding() {
        roundTrip(listOf("a,b", "c;d", "3:xyz", ":", ",", "\\", "\"", "[\"x\"]", "12:", "\u0000", "\n", "\u001F"))
        roundTrip(listOf("1:", "1:a"))
    }

    @Test
    fun nonAsciiNamesRoundTrip() {
        roundTrip(listOf("Comédie", "アニメ", "🎬 film", "مسلسل"))
    }

    @Test
    fun differentListsNeverShareAnEncoding() {
        val lists =
            listOf(
                emptyList(),
                listOf(""),
                listOf("", ""),
                listOf("a"),
                listOf("a", ""),
                listOf("", "a"),
                listOf("a", "b"),
                listOf("ab"),
                listOf("1:a"),
                listOf("1", "a"),
            )
        assertEquals(lists.size, lists.map(VersionCodec::encode).toSet().size)
    }

    @Test
    fun theSameListAlwaysEncodesTheSame() {
        val names = listOf("x", "y z", "é")
        assertEquals(VersionCodec.encode(names), VersionCodec.encode(names.toList()))
    }

    @Test
    fun aDamagedColumnIsRefusedNotGuessedAt() {
        listOf("x", "2:a", "-1:a", "1:ab1", "99999999999:a", "1a:b").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { VersionCodec.decode(it) }
        }
    }
}
