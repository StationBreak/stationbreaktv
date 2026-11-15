// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixture's values.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.audit.SnapshotFixture.id
import tv.stationbreak.core.engine.audit.SnapshotFixture.invalid
import tv.stationbreak.core.engine.audit.SnapshotFixture.library
import tv.stationbreak.core.engine.audit.SnapshotFixture.showId
import tv.stationbreak.core.engine.audit.SnapshotFixture.text
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The snapshot file is untrusted: the reader refuses what is not the format, and says so without quoting it. */
class SnapshotCodecRefusalTest {
    @Test
    fun theReaderRefusesWhatIsNotTheFormat() {
        invalid("not json")
        invalid("[]")
        invalid(text().replace("audit-snapshot", "something-else"))
        invalid(text().replace("\"version\": 1", "\"version\": 2"))
        invalid(text().replace("\"cutoffMs\"", "\"cutoffMs2\""))
        invalid(text().replace("\"genres\"", "\"genre\""))
        invalid(text().replace("\"kind\":\"Movie\"", "\"kind\":\"Video\""))
    }

    @Test
    fun theReaderRefusesAnIdThatIsNotCanonical() {
        val upper = library.value.uppercase()
        invalid(text().replace(library.value, "{$upper}"))
        invalid(text().replace(library.value, upper))
        invalid(text().replace(library.value, "xyz"))
    }

    @Test
    fun theReaderRefusesADuplicateItemAndAnItemThatScheduleItemRefuses() {
        invalid(text().replace(id(0x20).value, id(0x10).value))
        // A movie with series data.
        invalid(text().replaceFirst("\"seriesId\":null", "\"seriesId\":\"${showId.value}\""))
        invalid(text().replace("1999-12-31", "1999-13-31"))
        invalid(text().replace("\"runtimeMs\":5400000", "\"runtimeMs\":0"))
    }

    @Test
    fun aReasonNeverHoldsTheFilesText() {
        val secret = "Zebra Crossing Secret Title"
        val reason = invalid(text().replace("A Film", secret).replace("\"genres\"", "\"genre\""))
        assertFalse(reason.contains(secret))
        assertFalse(reason.contains("Drama"))
    }

    @Test
    fun theReaderRefusesAFileOverTheSizeLimit() {
        val result = AuditSnapshotCodec.read(text(), maxChars = 100)
        assertTrue(assertIs<SnapshotResult.Invalid>(result).reason.contains("too big", ignoreCase = true))
    }
}
