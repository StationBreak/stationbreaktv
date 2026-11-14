// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.TitleWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The spread end of the day (rules format version 5) for `shuffle` and `curated`, worked by hand in whole minutes on
 * a 24-hour window from minute 0 unless a case says otherwise. Ten 140-minute films end at 1400, so the leftover is 40
 * minutes; with four items to place, `k = 4` and the points are 280, 700, 980 and 1400.
 */
class SpreadDayTest {
    private val cases = SpreadCases()
    private val curated = spreadSettings(OrderingMode.Curated)
    private val shuffle = spreadSettings(OrderingMode.Shuffle)

    private fun films(
        count: Int,
        minutes: Int,
        from: Int = 1,
    ) = (from until from + count).map { cases.single("f$it", minutes) }

    @Test
    fun aCuratedDaySpreadsItsShortsAcrossTheDay() {
        val films = films(10, 140)
        val shorts = (1..4).map { cases.single("s$it", 10) }
        val laid = cases.laid(0, films.map { listOf(it) })
        val blocks = cases.spreadOf(curated, laid, shorts + films, 0, 1440, firstItem = 10)
        val expected =
            listOf(
                "0-140:f1",
                "140-280:f2",
                "280-290:s1",
                "290-430:f3",
                "430-570:f4",
                "570-710:f5",
                "710-720:s2",
                "720-860:f6",
                "860-1000:f7",
                "1000-1010:s3",
                "1010-1150:f8",
                "1150-1290:f9",
                "1290-1430:f10",
                "1430-1440:s4",
            )
        assertEquals(expected, cases.describe(blocks))
    }

    @Test
    fun aCuratedSameSeriesShortWaitsForItsGroupsRepeatGap() {
        val x1 = cases.ep("x1", 1, 1, 140)
        val x2 = cases.ep("x2", 1, 2, 10)
        val regular = listOf(cases.single("f1", 140), x1) + films(8, 140, from = 3)
        val shorts = listOf(x2) + (2..4).map { cases.single("s$it", 10) }
        val laid = cases.laid(0, regular.map { listOf(it) })
        // x1 ends at 280, so x2 may air from 280 + 180 = 460: not at the break at 280, at the one at 700.
        val blocks = cases.spreadOf(curated, laid, shorts + regular, 0, 1440, firstItem = 10)
        val expected =
            listOf(
                "0-140:f1",
                "140-280:x1",
                "280-420:f3",
                "420-560:f4",
                "560-700:f5",
                "700-710:x2",
                "710-720:s2",
                "720-860:f6",
                "860-1000:f7",
                "1000-1010:s3",
                "1010-1150:f8",
                "1150-1290:f9",
                "1290-1430:f10",
                "1430-1440:s4",
            )
        assertEquals(expected, cases.describe(blocks))
        // With no repeat gap it takes the first break.
        val noGap = spreadSettings(OrderingMode.Curated, repeatGapMs = 0L)
        val without = cases.spreadOf(noGap, laid, shorts + regular, 0, 1440, firstItem = 10)
        assertTrue("280-290:x2" in cases.describe(without))
    }

    @Test
    fun aCuratedShortNeverPrecedesAnEarlierItemInItsGroupsWalkQueue() {
        // A collection of three 10-minute films. Its walk queue is read from the walk, on a seed that rotates it away
        // from release order.
        val collection = filmIdOf(900)
        val members = (1..3).map { cases.named(filmOf(900 + it, 10, 2000 + it), "c${2000 + it}") }
        val groups = GroupRules(FilmCollections.of(members.associate { it.id to collection }), TitleWeights.NONE)
        var seedN = 1uL
        var queue = curatedRuns(members, seedN, groups, curated).flat()
        while (queue == members) queue = curatedRuns(members, ++seedN, groups, curated).flat()
        assertNotEquals(members, queue, "the queue is not in release order")
        val (q0, q1, q2) = queue
        val films = films(10, 150)
        // The cycle is f1, q0, f2 .. f10, q1, q2. f10 does not fit after f9 (1360), so the visit starts there.
        val regular = listOf(listOf(films[0]), listOf(q0)) + films.subList(1, 9).map { listOf(it) }
        val laid = cases.laid(0, regular)
        val visit = listOf(films[9], q1, q2, films[0], q0) + films.subList(1, 9)
        val blocks = cases.spreadOf(curated, laid, visit, 0, 1440, firstItem = 10, groups = groups)
        val (a, b, c) = queue.map { cases.label(it) }
        val expected =
            listOf(
                "0-150:f1",
                "150-160:break",
                "160-170:$a",
                "170-320:f2",
                "320-330:break",
                "330-480:f3",
                "480-630:f4",
                "630-640:$a",
                "640-650:$b",
                "650-660:$c",
                "660-670:break",
                "670-820:f5",
                "820-970:f6",
                "970-980:break",
                "980-1130:f7",
                "1130-1280:f8",
                "1280-1430:f9",
                "1430-1440:break",
            )
        assertEquals(expected, cases.describe(blocks))
        val firstAirings = cases.programmes(blocks).filter { it.startsWith("c") }.distinct()
        assertEquals(queue.map { cases.label(it) }, firstAirings, "first airings follow the walk queue")
    }

    @Test
    fun aCuratedUnitThatWrapsTheCycleAirsInWalkOrder() {
        val e2 = cases.ep("e2", 1, 2, 10)
        val e9 = cases.ep("e9", 1, 9, 10)
        val s = cases.single("s", 10)
        val films = films(10, 150)
        // The cycle is f1, e2, f2 .. f10, e9, s; the visit starts at f10 and wraps, so F holds e9, s, e2.
        val regular = listOf(listOf(films[0]), listOf(e2)) + films.subList(1, 9).map { listOf(it) }
        val laid = cases.laid(0, regular)
        val visit = listOf(films[9], e9, s, films[0], e2) + films.subList(1, 9)
        val blocks = cases.spreadOf(curated, laid, visit, 0, 1440, firstItem = 10)
        // The series' unit sits at e9's place in F (unit 0, before s) and airs e2 then e9, after e2's repeat gap.
        val expected =
            listOf(
                "0-150:f1",
                "150-160:break",
                "160-170:e2",
                "170-320:f2",
                "320-330:s",
                "330-340:break",
                "340-490:f3",
                "490-640:f4",
                "640-650:e2",
                "650-660:e9",
                "660-670:break",
                "670-820:f5",
                "820-970:f6",
                "970-980:break",
                "980-1130:f7",
                "1130-1280:f8",
                "1280-1430:f9",
                "1430-1440:break",
            )
        assertEquals(expected, cases.describe(blocks))
    }

    @Test
    fun aCuratedSameSeriesShortWithNoValidBreakIsDropped() {
        val x1 = cases.ep("x1", 1, 1, 140)
        val x2 = cases.ep("x2", 1, 2, 12)
        val regular = films(9, 140) + x1
        val shorts = listOf(x2, cases.single("s2", 10), cases.single("s3", 10), cases.single("s4", 8))
        val laid = cases.laid(0, regular.map { listOf(it) })
        // x1 ends at 1400, the last point: no break is 180 minutes after it, so x2 is dropped and its 12 minutes go
        // to the cards (3 minutes at each break).
        val blocks = cases.spreadOf(curated, laid, shorts + regular, 0, 1440, firstItem = 10)
        val expected =
            listOf(
                "0-140:f1",
                "140-280:f2",
                "280-283:break",
                "283-423:f3",
                "423-563:f4",
                "563-703:f5",
                "703-713:s2",
                "713-716:break",
                "716-856:f6",
                "856-996:f7",
                "996-1006:s3",
                "1006-1009:break",
                "1009-1149:f8",
                "1149-1289:f9",
                "1289-1429:x1",
                "1429-1437:s4",
                "1437-1440:break",
            )
        assertEquals(expected, cases.describe(blocks))
    }

    @Test
    fun aShuffleShortNeverReplaysStraightAfterItsAiring() {
        val s = cases.single("s", 10)
        val head = films(4, 140)
        val f5 = cases.single("f5", 130)
        val tail = films(5, 140, from = 6)
        val g = cases.single("g", 100)
        val t1 = cases.single("t1", 10)
        val t2 = cases.single("t2", 10)
        val runs = head.map { listOf(it) } + listOf(listOf(f5, s)) + tail.map { listOf(it) }
        val laid = cases.laid(0, runs)
        val visit = listOf(g, t1, t2) + head + listOf(f5, s) + tail
        // Points 140, 420, 700, 980, 1400. s's run ends at 700, so its replay skips that break for the one at 980.
        val blocks = cases.spreadOf(shuffle, laid, visit, 0, 1440, firstItem = 11)
        val expected =
            listOf(
                "0-140:f1",
                "140-150:t1",
                "150-152:break",
                "152-292:f2",
                "292-432:f3",
                "432-442:t2",
                "442-444:break",
                "444-584:f4",
                "584-714:f5",
                "714-724:s",
                "724-726:break",
                "726-866:f6",
                "866-1006:f7",
                "1006-1016:s",
                "1016-1018:break",
                "1018-1158:f8",
                "1158-1298:f9",
                "1298-1438:f10",
                "1438-1440:break",
            )
        assertEquals(expected, cases.describe(blocks))
    }

    @Test
    fun aDayWithNoBoundaryBeforeCGetsOneClosingBreak() {
        val f1 = cases.single("f1", 1300)
        val one = cases.spreadOf(curated, cases.laid(0, listOf(listOf(f1))), listOf(f1), 0, 1440)
        assertEquals(listOf("0-1300:f1", "1300-1440:break"), cases.describe(one))
        // A late repair whose tail starts at c: one break at c, holding the short and then the card.
        val s = cases.single("s", 20)
        val late =
            cases.spreadOf(
                curated,
                cases.laid(1300, emptyList()),
                listOf(s, f1),
                1300,
                1440,
                pastAirings = listOf(PastAiring(f1, 1300 * MS_MINUTE)),
            )
        assertEquals(listOf("1300-1320:s", "1320-1440:break"), cases.describe(late))
    }

    @Test
    fun aShuffleShortPastAiringFromNeedsABoundaryAfterItsEnd() {
        val s = cases.single("s", 10)
        val t = cases.single("t", 10)
        val f = listOf(cases.single("f1", 300), cases.single("f2", 300), cases.single("f3", 280))
        val before = listOf(PastAiring(s, 540 * MS_MINUTE))
        // The tail runs from 540. s ended at 540, so its run end is 540 and the break at 840 is after it.
        val laid = cases.laid(540, f.map { listOf(it) })
        val blocks = cases.spreadOf(shuffle, laid, listOf(s, t) + f, 540, 1440, pastAirings = before)
        assertEquals(
            listOf("540-840:f1", "840-850:s", "850-1150:f2", "1150-1430:f3", "1430-1440:t"),
            cases.describe(blocks),
        )
        // With no run in the tail the only point is 540 itself, which is not after s's end: s is dropped.
        val empty = cases.spreadOf(shuffle, cases.laid(540, emptyList()), listOf(s, t), 540, 1440, pastAirings = before)
        assertEquals(listOf("540-550:t", "550-1440:break"), cases.describe(empty))
    }

    @Test
    fun placedSumDecidesBetweenUnairedAndAll() {
        val u = cases.single("u", 25)
        val v = cases.single("v", 10)
        val r = cases.single("r", 15)
        val g = cases.single("g", 100)
        val f = films(9, 140)
        val f10 = cases.single("f10", 125)
        val laid = cases.laid(0, f.map { listOf(it) } + listOf(listOf(r), listOf(f10)))
        val visit = listOf(g, u, v) + f + listOf(r, f10)
        // A = {u, r} sums 40, but r ended at 1275 and cannot replay before 1455, so A places 25; U = {u, v} places 35.
        val blocks = cases.spreadOf(curated, laid, visit, 0, 1440, firstItem = 11)
        val expected =
            listOf(
                "0-140:f1",
                "140-165:u",
                "165-166:break",
                "166-306:f2",
                "306-446:f3",
                "446-456:v",
                "456-457:break",
                "457-597:f4",
                "597-737:f5",
                "737-738:break",
                "738-878:f6",
                "878-1018:f7",
                "1018-1019:break",
                "1019-1159:f8",
                "1159-1299:f9",
                "1299-1314:r",
                "1314-1439:f10",
                "1439-1440:break",
            )
        assertEquals(expected, cases.describe(blocks))
        // With a 60-minute gap r can replay at 1400, so A places all 40 and wins.
        val shortGap = spreadSettings(OrderingMode.Curated, repeatGapMs = 60 * MS_MINUTE)
        val replayed = cases.spreadOf(shortGap, laid, visit, 0, 1440, firstItem = 11)
        val withReplay =
            listOf(
                "0-140:f1",
                "140-280:f2",
                "280-420:f3",
                "420-560:f4",
                "560-700:f5",
                "700-725:u",
                "725-865:f6",
                "865-1005:f7",
                "1005-1145:f8",
                "1145-1285:f9",
                "1285-1300:r",
                "1300-1425:f10",
                "1425-1440:r",
            )
        assertEquals(withReplay, cases.describe(replayed))
    }

    @Test
    fun firstFitWinsWhenTheCapLeavesTheBestShortsOut() {
        val f = cases.single("f", 1400)
        val a = (0 until 64).map { cases.single("a$it", 15) }
        val z = cases.single("z", 10)
        // U and A see only the first 64 shorts (30 minutes); first fit reaches z beyond them and fills all 40.
        val capped = cases.spreadOf(shuffle, cases.laid(0, listOf(listOf(f))), a + z + f, 0, 1440, firstItem = 1)
        assertEquals(listOf("0-1400:f", "1400-1415:a0", "1415-1430:a1", "1430-1440:z"), cases.describe(capped))

        // A tie between U and the others keeps U: the unaired a and b, not s again.
        val g = cases.single("g", 1410)
        val s = cases.single("s", 15)
        val b = cases.single("b", 15)
        val tieU =
            cases.spreadOf(
                shuffle,
                cases.laid(0, listOf(listOf(g))),
                listOf(s, a[0], b, g),
                0,
                1440,
                pastAirings = listOf(PastAiring(s, 0L)),
            )
        assertEquals(listOf("0-1410:g", "1410-1425:a0", "1425-1440:b"), cases.describe(tieU))

        // Everything aired before: U is empty; A = {12, 18} and first fit = {10, 12, 8} both fill 30, and A is kept.
        val w = listOf(cases.single("w10", 10), cases.single("w12", 12), cases.single("w18", 18), cases.single("w8", 8))
        val tieA =
            cases.spreadOf(
                shuffle,
                cases.laid(0, listOf(listOf(g))),
                w + g,
                0,
                1440,
                pastAirings = w.map { PastAiring(it, 0L) },
            )
        assertEquals(listOf("0-1410:g", "1410-1422:w12", "1422-1440:w18"), cases.describe(tieA))
    }

    @Test
    fun aGridShuffleKeepsFillEndsSelectionWhenItEndsLater() {
        val grid = spreadSettings(OrderingMode.Shuffle, grid = true)
        val f = cases.single("f", 1380)
        val b = cases.single("b", 10)
        val a = cases.single("a", 50)
        val c = cases.single("c", 25)
        // By slot, best fit takes a alone (60 minutes, ending at 1430); first fit takes b and c, ending at 1435.
        val blocks = cases.spreadOf(grid, cases.laid(0, listOf(listOf(f))), listOf(b, a, c, f), 0, 1440)
        assertEquals(
            listOf("0-1380:f", "1380-1390:b", "1390-1410:slate", "1410-1435:c", "1435-1440:break"),
            cases.describe(blocks),
        )
    }

    @Test
    fun aCuratedGridChannelFillsBySlotLength() {
        val grid = spreadSettings(OrderingMode.Curated, grid = true)
        val f = cases.single("f", 1350)
        val a = cases.single("a", 10)
        val b = cases.single("b", 40)
        val c = cases.single("c", 20)
        // 90 minutes to the end: slots 30 + 60 fill them, so c (which would fit by runtime) is left out.
        val blocks = cases.spreadOf(grid, cases.laid(0, listOf(listOf(f))), listOf(a, b, c, f), 0, 1440)
        assertEquals(
            listOf("0-1350:f", "1350-1360:a", "1360-1380:slate", "1380-1420:b", "1420-1440:break"),
            cases.describe(blocks),
        )
    }

    @Test
    fun aGridCuratedChannelPrefersShortsThatHaveNotAired() {
        val grid = spreadSettings(OrderingMode.Curated, grid = true)
        val f = cases.single("f", 1380)
        val s = cases.single("s", 25)
        val a = cases.single("a", 25)
        val b = cases.single("b", 25)
        // 60 minutes to the end. All items: {s, a} (slots 30 + 30); the unaired: {a, b}, as much, so they are kept.
        val blocks =
            cases.spreadOf(
                grid,
                cases.laid(0, listOf(listOf(f))),
                listOf(s, a, b, f),
                0,
                1440,
                pastAirings = listOf(PastAiring(s, 0L)),
            )
        assertEquals(
            listOf("0-1380:f", "1380-1405:a", "1405-1410:slate", "1410-1435:b", "1435-1440:break"),
            cases.describe(blocks),
        )
    }

    @Test
    fun aOneHourEpochHasOneBreak() {
        val f = cases.single("f", 25)
        val g = cases.single("g", 25)
        val u = cases.single("u", 7)
        val v = cases.single("v", 5)
        val oneHour = spreadSettings(OrderingMode.Curated, epochHours = 1)
        val laid = cases.laid(0, listOf(listOf(f), listOf(g)))
        val blocks = cases.spreadOf(oneHour, laid, listOf(u, v, f, g), 0, 60, firstItem = 2)
        assertEquals(listOf("0-25:f", "25-50:g", "50-57:u", "57-60:break"), cases.describe(blocks))
    }

    @Test
    fun aRepairTailSpreadsOnlyAfterFromAndHonoursEarlierAirings() {
        val x1 = cases.ep("x1", 1, 1, 30)
        val x2 = cases.ep("x2", 1, 2, 20)
        val shorts = listOf(x2) + listOf("u", "v", "w", "z").map { cases.single(it, 20) }
        val f = films(4, 200)
        val laid = cases.laid(540, f.map { listOf(it) })
        // A tail from 540 (15:00 on a day from 06:00): 900 minutes, so k is at most 6. x1 aired before, ending 530.
        val early =
            cases.spreadOf(
                curated,
                laid,
                shorts + f,
                540,
                1440,
                firstItem = 4,
                pastAirings = listOf(PastAiring(x1, 530 * MS_MINUTE)),
            )
        val expected =
            listOf(
                "540-740:f1",
                "740-760:x2",
                "760-780:v",
                "780-800:z",
                "800-1000:f2",
                "1000-1200:f3",
                "1200-1400:f4",
                "1400-1420:u",
                "1420-1440:w",
            )
        assertEquals(expected, cases.describe(early))
        // x1 ending at 600 makes x2 wait until 780, so it goes to the break at the end.
        val late =
            cases.spreadOf(
                curated,
                laid,
                shorts + f,
                540,
                1440,
                firstItem = 4,
                pastAirings = listOf(PastAiring(x1, 600 * MS_MINUTE)),
            )
        val waited =
            listOf(
                "540-740:f1",
                "740-760:v",
                "760-780:z",
                "780-980:f2",
                "980-1180:f3",
                "1180-1380:f4",
                "1380-1400:x2",
                "1400-1420:u",
                "1420-1440:w",
            )
        assertEquals(waited, cases.describe(late))
    }
}
