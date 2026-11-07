// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 26: provider ids are stored as `key=value;key=value`, keys sorted, `''` for none. A cleaned map comes back
 * exactly, in sorted key order; text that is not what the codec writes reads as no ids and never throws. Invented ids.
 */
class ProviderIdsCodecTest {
    @Test
    fun noIdsAreTheEmptyText() {
        assertEquals("", ProviderIdsCodec.encode(emptyMap()))
        assertEquals(emptyMap<String, String>(), ProviderIdsCodec.decode(""))
    }

    @Test
    fun keysAreSortedAndACleanedMapRoundTripsExactly() {
        val punctuation = "!#\$%&'()*+,-./:<>?@[]^_`{|}~"
        val ids = mapOf("tvdb" to "81189", "imdb" to "tt0000001", "tmdb" to "1396", "a.b_c-9" to punctuation)
        val text = ProviderIdsCodec.encode(ids)

        assertEquals("a.b_c-9=$punctuation;imdb=tt0000001;tmdb=1396;tvdb=81189", text)
        val back = ProviderIdsCodec.decode(text)
        assertEquals(ids, back)
        assertEquals(ids.keys.sorted(), back.keys.toList())
    }

    @Test
    fun theLongestKeysAndValuesRoundTrip() {
        val ids = (0 until 8).associate { "k$it".padEnd(32, 'z') to "v".repeat(64) }

        assertEquals(ids, ProviderIdsCodec.decode(ProviderIdsCodec.encode(ids)))
    }

    @Test
    fun anEntryThatIsNotCleanIsLeftOutRatherThanWrittenUnreadable() {
        val ids = mapOf("imdb" to "tt1", "Bad" to "1", "tmdb" to "a;b", "tvdb" to "a=b", "x" to "", "y" to "with space")

        assertEquals("imdb=tt1", ProviderIdsCodec.encode(ids))
    }

    @Test
    fun damagedTextReadsAsNoIds() {
        val damaged =
            listOf(
                "imdb",
                "=tt1",
                "imdb=",
                "imdb=tt1;",
                ";imdb=tt1",
                "tmdb=1;imdb=tt1",
                "imdb=tt1;imdb=tt2",
                "IMDB=tt1",
                "imdb=t t1",
                "imdb=tté1",
                "imdb=tt1=2",
                "${"k".repeat(33)}=1",
                "imdb=${"v".repeat(65)}",
            )
        for (text in damaged) assertEquals(text, emptyMap<String, String>(), ProviderIdsCodec.decode(text))
    }
}
