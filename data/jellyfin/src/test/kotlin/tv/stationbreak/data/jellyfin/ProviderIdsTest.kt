// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The provider ids of an item and of a series: lower-case keys, no blank or long values, the first eight by key. */
class ProviderIdsTest {
    private val jellyfin = FakeJellyfin()
    private val library = Library(ItemId("lib01"), "Shows", LibraryKind.TvShows)

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun itemIds(providerJson: String): Map<String, String> =
        runBlocking {
            val body = """{"Items":[{"Id":"i1","Name":"x","Type":"Movie"$providerJson}],"TotalRecordCount":1}"""
            jellyfin.on("GET", "/Items") { jellyfin.json(body) }
            val page = testAdapter().libraryItems(testSession(jellyfin.address), library, 0, 50).getOrNull()
            page?.items?.single()?.providerIds ?: error("no page")
        }

    private fun seriesIds(providerJson: String): Map<String, String> =
        runBlocking {
            val body = """{"Items":[{"Id":"s1","Name":"x","Type":"Series"$providerJson}],"TotalRecordCount":1}"""
            jellyfin.on("GET", "/Items") { jellyfin.json(body) }
            val page = testAdapter().librarySeries(testSession(jellyfin.address), library, 0, 50).getOrNull()
            page?.series?.single()?.providerIds ?: error("no page")
        }

    @Test
    fun itemPage_readsProviderIds_normalised() {
        val long = "9".repeat(65)
        val exact = "8".repeat(64)
        val json =
            ""","ProviderIds":{"Imdb":"tt0000001","TMDB":"101","Blank":"  ","Empty":"","Long":"$long","Exact":"$exact",
            "Nul":null,"Num":5}"""

        assertEquals(
            mapOf("imdb" to "tt0000001", "tmdb" to "101", "exact" to exact),
            itemIds(json),
        )
    }

    @Test
    fun missingNullAndEmptyProviderIdsAreEmpty() {
        assertEquals(emptyMap(), itemIds(""))
        assertEquals(emptyMap(), itemIds(""","ProviderIds":null"""))
        assertEquals(emptyMap(), itemIds(""","ProviderIds":{}"""))
        assertEquals(emptyMap(), itemIds(""","ProviderIds":"junk""""))
    }

    @Test
    fun moreThanEight_sortedFirstEightKept() {
        val json = ""","ProviderIds":{""" + ('j' downTo 'a').joinToString(",") { """"K$it":"v$it"""" } + "}"

        val kept = itemIds(json)

        assertEquals(('a'..'h').map { "k$it" }, kept.keys.toList())
        assertEquals("va", kept.getValue("ka"))
    }

    @Test
    fun keysThatDifferOnlyInCaseKeepOneDeterministically() {
        assertEquals(mapOf("imdb" to "a"), itemIds(""","ProviderIds":{"imdb":"b","IMDB":"a"}"""))
        assertEquals(mapOf("imdb" to "a"), itemIds(""","ProviderIds":{"IMDB":"a","imdb":"b"}"""))
    }

    @Test
    fun aSeriesPageReadsProviderIdsTheSameWay() {
        val json = ""","ProviderIds":{"Tvdb":"77","Imdb":"tt1","Zap":" "}"""

        assertEquals(mapOf("imdb" to "tt1", "tvdb" to "77"), seriesIds(json))
    }

    @Test
    fun nestedValuesAndBadKeysAreDropped() {
        val long = "k".repeat(33)
        val exact = "k".repeat(32)
        val json =
            ""","ProviderIds":{"Obj":{"a":"1"},"Arr":["1"],"":"v"," ":"v","$long":"v","$exact":"v","a b":"v","a;b":"v",
            "a=b":"v","ünï":"v","ok.k_1-x":"v","Tab\tKey":"v"}"""

        assertEquals(mapOf(exact to "v", "ok.k_1-x" to "v"), itemIds(json))
    }

    @Test
    fun valuesWithSeparatorsOrControlCharactersAreDropped() {
        val json =
            ""","ProviderIds":{"semi":"a;b","eq":"a=b","nl":"a\nb","nul":"a\u0000b","del":"a\u007Fb","us":"a\u001Fb",
            "keep":"a b-c"}"""

        assertEquals(mapOf("keep" to "a b-c"), itemIds(json))
    }

    @Test
    fun preferredKeysSurviveEightEarlierSortingKeys() {
        val earlier = (1..9).joinToString(",") { """"a$it":"v$it"""" }
        val json = ""","ProviderIds":{$earlier,"Tvdb":"3","Imdb":"1","Tmdb":"2"}"""

        val kept = itemIds(json)

        assertEquals(8, kept.size)
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "imdb", "tmdb", "tvdb"), kept.keys.toList())
    }

    @Test
    fun aSubsetOfPreferredKeysIsKeptFirstToo() {
        val earlier = (1..9).joinToString(",") { """"a$it":"v$it"""" }

        val kept = itemIds(""","ProviderIds":{$earlier,"Tmdb":"2"}""")

        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7", "tmdb"), kept.keys.toList())
    }
}
