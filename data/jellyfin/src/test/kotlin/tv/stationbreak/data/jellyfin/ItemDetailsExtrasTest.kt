// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.model.MediaSummary
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `itemDetails`' extras for the guide's panel: the rating, the scores, the logo and the tracks' summary. */
class ItemDetailsExtrasTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun ok(body: String): ItemDetails =
        runBlocking {
            jellyfin.on("GET", "/Items/i1") { jellyfin.json(body) }
            val result = testAdapter().itemDetails(testSession(jellyfin.address), ItemId("i1"))
            (result as ServerResult.Success).value
        }

    private fun literal(text: String) = JsonPrimitive(text).toString()

    private fun streams(vararg stream: String): String {
        val tracks = stream.joinToString(",")
        return """"MediaSources":[{"Id":"s1","MediaStreams":[$tracks]}]"""
    }

    @Test
    fun readsTheRatingTheScoresTheLogoAndTheTracks() {
        val result =
            ok(
                """{"Id":"i1","OfficialRating":"PG-13","CriticRating":68.4,"CommunityRating":6.6,
                "ImageTags":{"Primary":"p1","Logo":"logo1"},
                ${streams(
                    """{"Type":"Video","Width":3840,"Height":1600}""",
                    """{"Type":"Audio","Channels":2,"IsDefault":false}""",
                    """{"Type":"Audio","Channels":6,"IsDefault":true}""",
                    """{"Type":"Subtitle"}""",
                )}}""",
            )

        assertEquals("PG-13", result.officialRating)
        assertEquals(68, result.criticRating)
        assertEquals(6.6, result.communityRating)
        assertEquals(ItemLogo(ItemId("i1"), "logo1"), result.logo)
        assertEquals(MediaSummary(3840, 1600, 6, hasSubtitles = true), result.media)
    }

    @Test
    fun asksForTheExtraFieldsInTheOneCall() {
        ok("""{"Id":"i1"}""")

        val request = jellyfin.requests("/Items/i1").single()
        val fields =
            request.url
                .queryParameter("fields")
                .orEmpty()
                .split(',')
        assertEquals(listOf("Overview", "Genres", "MediaSources"), fields)
    }

    @Test
    fun anItemWithNoneOfThemHasNone() {
        val result = ok("""{"Id":"i1","Overview":"About."}""")

        assertNull(result.officialRating)
        assertNull(result.criticRating)
        assertNull(result.communityRating)
        assertNull(result.logo)
        assertNull(result.media)
    }

    @Test
    fun anEpisodeTakesItsSeriesLogoAndItsOwnWinsOverIt() {
        val episode = ok("""{"Id":"i1","ParentLogoItemId":"series9","ParentLogoImageTag":"slogo"}""")
        assertEquals(ItemLogo(ItemId("series9"), "slogo"), episode.logo)

        val both =
            ok(
                """{"Id":"i1","ImageTags":{"Logo":"own"},"ParentLogoItemId":"series9",
                "ParentLogoImageTag":"slogo"}""",
            )
        assertEquals(ItemLogo(ItemId("i1"), "own"), both.logo)
    }

    @Test
    fun aScoreOutOfRangeOrNotANumberIsDropped() {
        assertNull(ok("""{"Id":"i1","CriticRating":101}""").criticRating)
        assertNull(ok("""{"Id":"i1","CriticRating":-1}""").criticRating)
        assertNull(ok("""{"Id":"i1","CriticRating":"n/a"}""").criticRating)
        assertNull(ok("""{"Id":"i1","CommunityRating":10.5}""").communityRating)
        assertNull(ok("""{"Id":"i1","CommunityRating":{"x":1}}""").communityRating)
        assertEquals(0, ok("""{"Id":"i1","CriticRating":0}""").criticRating)
        assertEquals(100, ok("""{"Id":"i1","CriticRating":100}""").criticRating)
        assertEquals(10.0, ok("""{"Id":"i1","CommunityRating":10}""").communityRating)
    }

    @Test
    fun aRatingIsMadeSafeAndALongOneIsDroppedNotCut() {
        assertEquals("PG", ok("""{"Id":"i1","OfficialRating":"<b>PG</b>"}""").officialRating)
        assertEquals("TV-MA", ok("""{"Id":"i1","OfficialRating":${literal("TV‮-MA\u0000")}}""").officialRating)
        assertNull(ok("""{"Id":"i1","OfficialRating":"${"X".repeat(40)}"}""").officialRating)
        assertNull(ok("""{"Id":"i1","OfficialRating":"   "}""").officialRating)
    }

    @Test
    fun anOddLogoIsNoLogoAndNeverFailsTheAnswer() {
        // The tags as a string, an array, a number; a tag with a space, a slash or too long; a blank parent.
        assertNull(ok("""{"Id":"i1","ImageTags":"Logo"}""").logo)
        assertNull(ok("""{"Id":"i1","ImageTags":["Logo"]}""").logo)
        assertNull(ok("""{"Id":"i1","ImageTags":{"Logo":5}}""").logo)
        assertNull(ok("""{"Id":"i1","ImageTags":{"Logo":"a b"}}""").logo)
        assertNull(ok("""{"Id":"i1","ImageTags":{"Logo":"../x"}}""").logo)
        assertNull(ok("""{"Id":"i1","ImageTags":{"Logo":"${"a".repeat(65)}"}}""").logo)
        assertNull(ok("""{"Id":"i1","ParentLogoItemId":"a/b","ParentLogoImageTag":"t"}""").logo)
        assertNull(ok("""{"Id":"i1","ParentLogoItemId":"p1"}""").logo)
        assertEquals("fine", ok("""{"Id":"i1","Overview":"fine","ImageTags":"x"}""").overview)
    }

    @Test
    fun theFirstAudioIsTheDefaultWhenNoneIsMarked() {
        val eight = """{"Type":"Audio","Channels":8}"""
        val two = """{"Type":"Audio","Channels":2}"""
        val result = ok("""{"Id":"i1",${streams(eight, two)}}""")

        assertEquals(MediaSummary(null, null, 8, hasSubtitles = false), result.media)
    }

    @Test
    fun oddTrackNumbersAreUnknownNotErrors() {
        val result =
            ok(
                """{"Id":"i1",${streams(
                    """{"Type":"Video","Width":"1920","Height":{"x":1}}""",
                    """{"Type":"Audio","Channels":-2}""",
                )}}""",
            )

        assertEquals(MediaSummary(1920, null, null, hasSubtitles = false), result.media)
    }

    @Test
    fun onlyTheFirstMediaSourceIsRead() {
        val result =
            ok(
                """{"Id":"i1","MediaSources":[
                {"Id":"a","MediaStreams":[{"Type":"Video","Height":480}]},
                {"Id":"b","MediaStreams":[{"Type":"Video","Height":2160},{"Type":"Subtitle"}]}]}""",
            )

        assertEquals(MediaSummary(null, 480, null, hasSubtitles = false), result.media)
    }
}
