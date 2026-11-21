// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseVersionTest {
    @Test
    fun `packs the version and a stage into the versionCode`() {
        assertEquals(1_000_099, ReleaseVersion.parse("1.0.0").code)
        assertEquals(1_020_399, ReleaseVersion.parse("1.2.3").code)
        assertEquals(90_099, ReleaseVersion.parse("0.9.0").code)
        assertEquals(1_000_031, ReleaseVersion.parse("1.0.0-beta.1").code)
        assertEquals(90_031, ReleaseVersion.parse("0.9.0-beta.1").code)
        assertEquals(90_062, ReleaseVersion.parse("0.9.0-rc.2").code)
        assertEquals(90_003, ReleaseVersion.parse("0.9.0-alpha.3").code)
    }

    @Test
    fun `the name is the version as given`() {
        listOf("0.9.0-alpha.1", "0.9.0-beta.1", "0.9.0", "1.0.0-rc.2", "1.0.0", "12.99.99-rc.29").forEach { raw ->
            assertEquals(raw, ReleaseVersion.parse(raw).name)
        }
    }

    @Test
    fun `a pre-release sorts below its final and every later final`() {
        val codes =
            listOf(
                "0.0.1",
                "0.9.0-alpha.0",
                "0.9.0-alpha.29",
                "0.9.0-beta.0",
                "0.9.0-beta.29",
                "0.9.0-rc.0",
                "0.9.0-rc.29",
                "0.9.0",
                "0.99.99",
                "1.0.0-beta.1",
                "1.0.0-beta.2",
                "1.0.0-rc.1",
                "1.0.0",
                "1.0.1-beta.1",
                "1.0.1",
                "1.1.0-rc.1",
                "2.0.0",
            ).map { ReleaseVersion.parse(it).code }
        assertEquals(codes.sorted().distinct(), codes)
    }

    @Test
    fun `rejects what the tag check rejects`() {
        listOf(
            "",
            "1",
            "1.2",
            "1.2.3.4",
            "01.2.3",
            "1.02.3",
            "1.2.03",
            "1.100.0",
            "1.0.100",
            "v1.2.3",
            "1.0.0-",
            "1.0.0-rc",
            "1.0.0-rc1",
            "1.0.0-rc.",
            "1.0.0-rc.01",
            "1.0.0-rc.30",
            "1.0.0-beta.30",
            "1.0.0-dev.1",
            "1.0.0-rc.1.2",
            "1.0.0-rc.1+build5",
            "1.0.0-nightly.5",
            "1.0.0-alpha.30",
            "1.2.3+1",
            " 1.2.3",
            "1.2.3\n",
            "-1.0.0",
            "1.-2.3",
            "1.2.x",
        ).forEach { raw -> assertFailsWith<IllegalArgumentException>("'$raw'") { ReleaseVersion.parse(raw) } }
    }

    @Test
    fun `rejects a versionCode above Android's limit`() {
        assertEquals(2_099_999_999, ReleaseVersion.parse("2099.99.99").code)
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.parse("2100.99.99") }
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.parse("99999999999999999999.0.0") }
    }
}
