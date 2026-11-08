// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ChannelId

/** The initials rules and bands from the design partner's ChannelMarks spec (spec 27). */
class MarkInitialsTest {
    private fun ch(
        number: Int,
        name: String,
    ) = MarkChannel(ChannelId("c$number"), number, name)

    private fun initialsOf(vararg channels: MarkChannel): Map<ChannelId, String> = assignInitials(channels.toList())

    private fun Map<ChannelId, String>.at(number: Int): String? = this[ChannelId("c$number")]

    @Test
    fun theSpecSamplesGiveTheirInitials() {
        val roster =
            listOf(
                ch(8, "Sing-Along Station") to "SS",
                ch(16, "Saturday Morning") to "SM",
                ch(56, "Snowglobe") to "Sn",
                ch(102, "Laugh Track") to "LT",
                ch(110, "Rabbit Ears") to "RE",
                ch(178, "Backstage Pass") to "BP",
                ch(204, "Prestige") to "Pr",
                ch(212, "Order in the Court") to "OC",
                ch(316, "The Midnight Projector") to "MP",
                ch(404, "Tokyo After School") to "TS",
                ch(416, "Tea Time") to "TT",
                ch(504, "After Hours") to "AH",
            )
        // Computed in one call over a roster that also holds the clashing Spooky Sleepover and Hey, Science!.
        val all = roster.map { it.first } + listOf(ch(76, "Spooky Sleepover"), ch(32, "Hey, Science!"))
        val result = assignInitials(all.shuffled(java.util.Random(7)))
        for ((channel, expected) in roster) assertEquals(channel.name, expected, result[channel.id])
        assertEquals("Sp", result.at(76))
        assertEquals("HS", result.at(32))
    }

    @Test
    fun aCollisionInABandSendsTheHigherNumberToItsFirstWord() {
        val a = initialsOf(ch(76, "Spooky Sleepover"), ch(8, "Sing-Along Station"))
        assertEquals("SS", a.at(8))
        assertEquals("Sp", a.at(76))
        // Whichever is lower keeps the two-word form, whatever order they arrive in.
        val b = initialsOf(ch(8, "Spooky Sleepover"), ch(76, "Sing-Along Station"))
        assertEquals("SS", b.at(8))
        assertEquals("Si", b.at(76))
        // A third clash that the fallback cannot fix is kept, and never grows past two characters.
        val c = initialsOf(ch(4, "Sing Song"), ch(8, "Sing-Along Station"), ch(12, "Sing Sleep"), ch(16, "Sad Song"))
        assertEquals("SS", c.at(4))
        assertEquals("Si", c.at(8))
        assertEquals("Si", c.at(12))
        assertEquals("Sa", c.at(16))
    }

    @Test
    fun aCollisionAcrossBandsIsNotACollision() {
        val r = initialsOf(ch(8, "Sing-Along Station"), ch(108, "Sunday Showcase"), ch(299, "Slow Stories"))
        assertEquals("SS", r.at(8))
        assertEquals("SS", r.at(108))
        assertEquals("SS", r.at(299))
    }

    @Test
    fun emptyNamesGiveASafeFallback() {
        val r = initialsOf(ch(1, ""), ch(2, "   "), ch(3, "The"), ch(4, "&"), ch(5, "A and The of in an"), ch(6, "!!!"))
        for (n in 1..6) assertEquals("?", r.at(n))
    }

    @Test
    fun nonLatinAndEmojiNamesGiveOneOrTwoCharacters() {
        val party = "🎉" // a surrogate pair
        val r =
            initialsOf(
                ch(1, "$party Party Night"),
                ch(2, party),
                ch(3, "Привет Мир"),
                ch(4, "東京 夜"),
                ch(5, "Привет"),
                ch(6, "${party}Fun"),
            )
        assertEquals("$party" + "N", r.at(1))
        assertEquals(party, r.at(2))
        assertEquals("ПМ", r.at(3))
        assertEquals("東夜", r.at(4))
        assertEquals("Пр", r.at(5))
        for (n in 1..6) {
            val s = r.at(n)!!
            assertTrue("$n: $s", s.codePointCount(0, s.length) in 1..2)
            // Never half a surrogate pair.
            for ((k, ch) in s.withIndex()) {
                val paired =
                    when {
                        Character.isHighSurrogate(ch) -> k + 1 < s.length && Character.isLowSurrogate(s[k + 1])
                        Character.isLowSurrogate(ch) -> k > 0 && Character.isHighSurrogate(s[k - 1])
                        else -> true
                    }
                assertTrue("$n: split pair", paired)
            }
        }
    }

    @Test
    fun neverThrowsAndNeverReturnsMoreThanTwoCharacters() {
        val party = "🎉"
        val names =
            listOf(
                "",
                " ",
                "     ",
                "!",
                "?!?!",
                "---",
                "...",
                "The",
                "A",
                "A & B",
                "&",
                "a",
                "ab",
                "1",
                "24",
                "24 Hours",
                "x".repeat(200),
                "word ".repeat(40),
                "$party$party$party",
                "Latin Кириллица 日本語",
                "العربية قناة",
                "\u0000\u0001",
                "ß",
                "ǆ ǅ",
                "İstanbul",
                "O'Neil's Place",
                "Kids' Odds & Ends",
                "-a-b-c-",
                "\uD83C",
                "\uDF89 x",
                "á b́",
                "ΑΒΓ Δ",
            )
        assertEquals(32, names.size)
        val channels = names.mapIndexed { i, n -> ch(100 + i, n) }
        val r = assignInitials(channels)
        assertEquals(names.size, r.size)
        for (c in channels) {
            val s = checkNotNull(r[c.id])
            assertTrue("'${c.name}' gave '$s'", s.isNotEmpty() && s.codePointCount(0, s.length) <= 2)
        }
        // And the same names all in one band as duplicates, so every fallback path runs.
        val dup = names.mapIndexed { i, n -> ch(100 + i, n) } + names.mapIndexed { i, n -> ch(150 + i, n) }
        assignInitials(dup).values.forEach { assertTrue(it.codePointCount(0, it.length) in 1..2) }
    }

    @Test
    fun punctuationHyphensAndStopWordsAreHandled() {
        val r =
            initialsOf(
                ch(1, "Hey, Science!"),
                ch(2, "Sing-Along Station"),
                ch(3, "Kids' Odds & Ends"),
                ch(4, "tea time"),
            )
        assertEquals("HS", r.at(1))
        assertEquals("SS", r.at(2))
        assertEquals("KE", r.at(3))
        assertEquals("TT", r.at(4))
    }

    @Test
    fun markBandOfCoversTheBandsAndTheEdges() {
        assertEquals(MarkBand.Kids, markBandOf(0))
        assertEquals(MarkBand.Kids, markBandOf(-5))
        assertEquals(MarkBand.Kids, markBandOf(1))
        assertEquals(MarkBand.Kids, markBandOf(99))
        assertEquals(MarkBand.General, markBandOf(100))
        assertEquals(MarkBand.General, markBandOf(199))
        assertEquals(MarkBand.Drama, markBandOf(200))
        assertEquals(MarkBand.Genre, markBandOf(399))
        assertEquals(MarkBand.World, markBandOf(400))
        assertEquals(MarkBand.Premium, markBandOf(500))
        assertEquals(MarkBand.Premium, markBandOf(599))
        assertEquals(MarkBand.Custom, markBandOf(600))
        assertEquals(MarkBand.Custom, markBandOf(1000))
        assertEquals(7, MarkBand.entries.size)
    }
}
