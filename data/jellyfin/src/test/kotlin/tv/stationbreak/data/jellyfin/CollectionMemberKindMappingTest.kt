// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.CollectionMemberPage
import tv.stationbreak.core.model.CollectionPage
import tv.stationbreak.core.model.ItemId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The adapter only maps a server type to a kind (spec 10, Unit 2, Granny H3): a BoxSet is a collection and is not
 * followed, anything the cache does not hold (music, photos, a type added later) is `Other`. A member keeps its ID
 * exactly; no `MediaItem` is built for a collection or a member.
 */
class CollectionMemberKindMappingTest {
    private val jellyfin = FakeJellyfin()
    private val box = ItemId("box01")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun members(
        body: String,
        startIndex: Int = 0,
    ): ServerResult<CollectionMemberPage> =
        runBlocking {
            jellyfin.on("GET", "/Items") { jellyfin.json(body) }
            testAdapter().collectionMembers(testSession(jellyfin.address), box, startIndex, 50)
        }

    private fun member(
        id: String,
        type: String,
    ) = """{"Id":"$id","Name":"Name of $id","Type":"$type"}"""

    @Test
    fun eachMemberTypeMapsToItsKind() {
        val types =
            mapOf(
                "Movie" to CollectionMemberKind.Movie,
                "Episode" to CollectionMemberKind.Episode,
                "Series" to CollectionMemberKind.Series,
                "Season" to CollectionMemberKind.Season,
                "BoxSet" to CollectionMemberKind.Collection,
            )
        val body = types.keys.mapIndexed { n, type -> member("m$n", type) }.joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":5}")

        val page = members(body).getOrNull()

        assertEquals(types.values.toList(), page?.members?.map { it.kind })
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), page?.members?.map { it.id.value })
    }

    @Test
    fun musicPhotosAndUnknownTypesAreOther() {
        val types = listOf("Audio", "MusicAlbum", "Photo", "Video", "MusicVideo", "Playlist", "SomethingNew", "")
        val body = types.mapIndexed { n, type -> member("m$n", type) }.joinToString(",", "{\"Items\":[", "],\"TotalRecordCount\":8}")

        val page = members(body).getOrNull()

        assertEquals(List(types.size) { CollectionMemberKind.Other }, page?.members?.map { it.kind })
    }

    @Test
    fun aMemberWithNoTypeIsOtherAndKeepsItsId() {
        val page = members("""{"Items":[{"Id":"m1"}],"TotalRecordCount":1}""").getOrNull()

        assertEquals(CollectionMember(ItemId("m1"), CollectionMemberKind.Other), page?.members?.single())
    }

    @Test
    fun theFirstPageCarriesTheTotalAndALaterPageDoesNot() {
        val body = """{"Items":[],"TotalRecordCount":7}"""

        assertEquals(7, members(body, startIndex = 0).getOrNull()?.totalCount)
        assertNull(members(body, startIndex = 50).getOrNull()?.totalCount)
    }

    @Test
    fun aFirstPageWithoutATotalFailsInsteadOfLookingLikeAnEmptyCollection() {
        assertIs<MediaServerError.ServerError>(members("""{"Items":[]}""").errorOrNull())
    }

    @Test
    fun aBlankOrUnsafeMemberIdIsNotJellyfin() {
        val result = members("""{"Items":[{"Id":"a/b","Type":"Movie"}],"TotalRecordCount":1}""")

        assertIs<MediaServerError.NotJellyfin>(result.errorOrNull())
    }

    @Test
    fun aBoxSetListingDecodesIdAndNameAndNothingElse() {
        val body =
            """{"Items":[{"Id":"b1","Name":"Night films","Type":"BoxSet","OfficialRating":"X","Genres":["g"],"ChildCount":9},
            {"Id":"b2","Type":"BoxSet"}],"TotalRecordCount":2,"StartIndex":0}"""

        val page =
            runBlocking {
                jellyfin.on("GET", "/Items") { jellyfin.json(body) }
                testAdapter().collections(testSession(jellyfin.address), 0, 50).getOrNull()
            }

        assertEquals(
            CollectionPage(0, 2, listOf(CollectionInfo(ItemId("b1"), "Night films"), CollectionInfo(ItemId("b2"), ""))),
            page,
        )
    }

    @Test
    fun theIdListingReturnsIdsInPagesAndNothingElse() {
        val page =
            runBlocking {
                jellyfin.on("GET", "/Items") { jellyfin.json("""{"Items":[{"Id":"b1","Name":"x"},{"Id":"b2"}],"TotalRecordCount":2}""") }
                testAdapter().collectionIds(testSession(jellyfin.address), 0, 50).getOrNull()
            }

        assertEquals(listOf("b1", "b2"), page?.ids?.map { it.value })
        assertEquals(2, page?.totalCount)
    }
}
