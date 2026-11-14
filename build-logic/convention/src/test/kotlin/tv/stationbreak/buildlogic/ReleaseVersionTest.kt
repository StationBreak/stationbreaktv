// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseVersionTest {
    @Test
    fun `packs MAJOR MINOR PATCH into the versionCode`() {
        assertEquals(10_000, ReleaseVersion.parse("1.0.0").code)
        assertEquals(10_203, ReleaseVersion.parse("1.2.3").code)
        assertEquals(129_999, ReleaseVersion.parse("12.99.99").code)
        assertEquals("1.2.3", ReleaseVersion.parse("1.2.3").name)
    }

    @Test
    fun `versionCode rises with the version`() {
        val codes = listOf("1.0.0", "1.0.1", "1.0.99", "1.1.0", "1.99.99", "2.0.0").map { ReleaseVersion.parse(it).code }
        assertEquals(codes.sorted().distinct(), codes)
    }

    @Test
    fun `rejects what the tag check rejects`() {
        listOf(
            "",
            "1",
            "1.2",
            "1.2.3.4",
            "0.1.0",
            "01.2.3",
            "1.02.3",
            "1.2.03",
            "1.100.0",
            "1.0.100",
            "v1.2.3",
            "1.2.3-rc1",
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
        assertEquals(2_099_999_999, ReleaseVersion.parse("209999.99.99").code)
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.parse("210000.0.1") }
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.parse("99999999999999999999.0.0") }
    }
}
