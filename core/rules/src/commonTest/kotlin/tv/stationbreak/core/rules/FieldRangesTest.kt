// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// The reader checks what the JSON Schema checks (docs/rules-format.md says readers do not rely on the schema).
class FieldRangesTest {
    private fun timing(vararg fields: Pair<String, JsonElement>) =
        minimalRules().with("timing", obj("broadcastDay" to obj("utcOffsetMinutes" to JsonPrimitive(0)), *fields))

    private fun day(vararg fields: Pair<String, JsonElement>): JsonObject {
        val timing = obj("broadcastDay" to obj(*fields))
        return minimalRules().with("timing", timing)
    }

    private fun ok(rules: JsonObject) = assertIs<RulesResult.Ok>(RulesReader.read(rules.text()), rules.text())

    private fun bad(rules: JsonObject) = assertIs<RulesResult.Invalid>(RulesReader.read(rules.text()), rules.text())

    @Test
    fun blockLengthIsOneToOneHundred() {
        for (n in listOf(1, 2, 100)) ok(timing("blockLength" to JsonPrimitive(n)))
        for (n in listOf(0, -1, 101)) bad(timing("blockLength" to JsonPrimitive(n)))
        bad(timing("blockLength" to JsonPrimitive("2")))
        bad(timing("blockLength" to JsonPrimitive(1.5)))
    }

    @Test
    fun epochHoursMustDivideTwentyFour() {
        for (n in listOf(1, 2, 3, 4, 6, 8, 12, 24)) ok(timing("epochHours" to JsonPrimitive(n)))
        for (n in listOf(0, 5, 7, 9, 10, 18, 25, 48)) bad(timing("epochHours" to JsonPrimitive(n)))
    }

    @Test
    fun alignToGridIsABoolean() {
        ok(timing("alignToGrid" to JsonPrimitive(false)))
        bad(timing("alignToGrid" to JsonPrimitive("true")))
        bad(timing("alignToGrid" to JsonNull))
    }

    @Test
    fun minRuntimeSecondsIsAWholeNumberFromOneToThirtySixHundred() {
        for (n in listOf(1, 60, 3599, 3600)) ok(timing("minRuntimeSeconds" to JsonPrimitive(n)))
        for (n in listOf(0, -1, 3601, 1_000_000)) bad(timing("minRuntimeSeconds" to JsonPrimitive(n)))
        bad(timing("minRuntimeSeconds" to JsonPrimitive("60")))
        bad(timing("minRuntimeSeconds" to JsonPrimitive(1.5)))
        bad(timing("minRuntimeSeconds" to JsonPrimitive(60.0)))
        bad(timing("minRuntimeSeconds" to JsonNull))
    }

    @Test
    fun graceHoursIsAWholeNumberFromZeroToFortyEight() {
        for (n in listOf(0, 1, 6, 47, 48)) ok(timing("graceHours" to JsonPrimitive(n)))
        for (n in listOf(-1, 49, 100, 1_000_000)) bad(timing("graceHours" to JsonPrimitive(n)))
        bad(timing("graceHours" to JsonPrimitive("6")))
        bad(timing("graceHours" to JsonPrimitive(1.5)))
        bad(timing("graceHours" to JsonPrimitive(6.0)))
        bad(timing("graceHours" to JsonNull))
    }

    @Test
    fun gridMinutesIsFifteenThirtyOrSixty() {
        for (n in listOf(15, 30, 60)) ok(timing("gridMinutes" to JsonPrimitive(n)))
        for (n in listOf(0, 1, 5, 10, 20, 45, 90, -30)) bad(timing("gridMinutes" to JsonPrimitive(n)))
        bad(timing("gridMinutes" to JsonPrimitive("30")))
        bad(timing("gridMinutes" to JsonPrimitive(30.5)))
        bad(timing("gridMinutes" to JsonNull))
    }

    @Test
    fun withAnHourlyGridTheDayMustStartOnTheHour() {
        fun hourly(start: String?) =
            minimalRules().with(
                "timing",
                obj(
                    "gridMinutes" to JsonPrimitive(60),
                    "broadcastDay" to
                        if (start == null) {
                            obj("utcOffsetMinutes" to JsonPrimitive(0))
                        } else {
                            obj("utcOffsetMinutes" to JsonPrimitive(0), "start" to JsonPrimitive(start))
                        },
                ),
            )
        ok(hourly("06:00"))
        ok(hourly("00:00"))
        ok(hourly(null)) // the default start is 06:00
        bad(hourly("06:30"))
        bad(hourly("23:30"))
        // On the other grids a half-hour start is fine.
        for (grid in listOf(15, 30)) {
            ok(
                minimalRules().with(
                    "timing",
                    obj(
                        "gridMinutes" to JsonPrimitive(grid),
                        "broadcastDay" to
                            obj("utcOffsetMinutes" to JsonPrimitive(0), "start" to JsonPrimitive("06:30")),
                    ),
                ),
            )
        }
    }

    @Test
    fun theUtcOffsetIsRequiredAMultipleOfFifteenAndInRange() {
        for (n in listOf(-720, -300, 0, 345, 840)) ok(day("utcOffsetMinutes" to JsonPrimitive(n)))
        for (n in listOf(-735, 7, 855, 841, -721, 10)) bad(day("utcOffsetMinutes" to JsonPrimitive(n)))
        bad(day("start" to JsonPrimitive("06:00")))
        bad(day("utcOffsetMinutes" to JsonPrimitive("0")))
        bad(day("utcOffsetMinutes" to JsonPrimitive(0.5)))
    }

    @Test
    fun theBroadcastDayStartIsOnTheHourOrHalfHour() {
        for (start in listOf("00:00", "06:00", "05:30", "23:30")) {
            ok(
                day(
                    "utcOffsetMinutes" to JsonPrimitive(0),
                    "start" to JsonPrimitive(start),
                ),
            )
        }
        for (start in listOf("06:10", "24:00", "6:00", "06:00:00", "", "0600")) {
            bad(day("utcOffsetMinutes" to JsonPrimitive(0), "start" to JsonPrimitive(start)))
        }
    }

    @Test
    fun timingAndTheBroadcastDayAreRequired() {
        bad(minimalRules().without("timing"))
        bad(minimalRules().with("timing", obj()))
        bad(minimalRules().with("timing", JsonPrimitive("x")))
    }

    @Test
    fun aYearRangeIsOneToNineThousandNineHundredNinetyNineAndNotBackwards() {
        fun years(
            from: Int,
            to: Int,
        ) = minimalRules().with(
            "sources",
            array(
                source(
                    "yearRange",
                    "from" to JsonPrimitive(from),
                    "to" to JsonPrimitive(to),
                ),
            ),
        )
        ok(years(1990, 1999))
        ok(years(1, 9999))
        ok(years(2000, 2000))
        bad(years(1999, 1990))
        bad(years(0, 5))
        bad(years(1, 10000))
    }

    @Test
    fun theRatingCeilingMustBeOnTheLadderSpelledExactly() {
        for (r in listOf("TV-Y", "Approved", "PG-13", "TV-AO", "TV-Y7-FV")) ok(minimalRules().with("maxRating", r))
        for (r in listOf("", "PG-14", "approved", "tv-14", "NR", " PG")) bad(minimalRules().with("maxRating", r))
        bad(minimalRules().with("maxRating", JsonNull))
    }

    @Test
    fun sourcesNeedAtLeastOneEntryAndTheRightShape() {
        bad(minimalRules().with("sources", array()))
        bad(minimalRules().with("sources", JsonPrimitive("library")))
        bad(minimalRules().without("sources"))
        bad(minimalRules().with("sources", array(JsonPrimitive("library"))))
        bad(minimalRules().with("sources", array(obj("id" to JsonPrimitive(PLACEHOLDER_ID)))))
    }

    @Test
    fun fillerNeedsItsFieldsEvenWhenItIsNotScheduled() {
        val source = libraryJson()
        bad(minimalRules().with("filler", obj("mode" to JsonPrimitive("betweenEveryN"), "source" to source)))
        bad(minimalRules().with("filler", obj("mode" to JsonPrimitive("padToGrid"))))
        val zero = obj("mode" to JsonPrimitive("betweenEveryN"), "every" to JsonPrimitive(0), "source" to source)
        bad(minimalRules().with("filler", zero))
        val genre = source("genre", "name" to JsonPrimitive("x"))
        bad(minimalRules().with("filler", obj("mode" to JsonPrimitive("none"), "source" to genre)))
        assertEquals(
            true,
            RulesReader.read(
                minimalRules()
                    .with(
                        "filler",
                        obj(
                            "mode" to JsonPrimitive("betweenEveryN"),
                            "every" to JsonPrimitive(3),
                            "source" to source,
                        ),
                    ).text(),
            )
                is RulesResult.Unsupported,
        )
    }

    @Test
    fun aFieldOfTheWrongKindIsInvalid() {
        bad(minimalRules().with("name", JsonPrimitive(5)))
        bad(minimalRules().with("ordering", JsonPrimitive("shuffle")))
        bad(minimalRules().with("ordering", obj()))
        bad(minimalRules().with("exclusions", JsonPrimitive("x")))
        bad(minimalRules().with("filler", JsonPrimitive("none")))
    }
}
