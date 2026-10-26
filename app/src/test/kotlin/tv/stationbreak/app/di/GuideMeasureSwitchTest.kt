// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The guide's measurement line is written by the debug and benchmark builds only (spec 17, ruling R22). The switch is a
 * resource each build type sets, read from the files themselves (the unit test's working directory is the module).
 */
class GuideMeasureSwitchTest {
    private fun switchIn(sourceSet: String): Boolean {
        val xml = File("src/$sourceSet/res/values/guide.xml").readText()
        val value = Regex("""<bool name="guide_measure">(true|false)</bool>""").find(xml)
        return checkNotNull(value) { "no guide_measure in $sourceSet" }.groupValues[1].toBooleanStrict()
    }

    @Test
    fun releaseWritesNoLine() {
        assertEquals(false, switchIn("main"))
    }

    @Test
    fun debugAndBenchmarkWriteTheLine() {
        assertEquals(true, switchIn("debug"))
        assertEquals(true, switchIn("benchmark"))
    }
}
