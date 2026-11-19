// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestReleaseVersionTest {
    @Test
    fun `accepts 0 x versions and pre-release suffixes and keeps the name as given`() {
        listOf("0.9.0-rc1", "0.9.0", "1.0.0-rc.2", "1.0.0-beta.1", "1.2.3-alpha0", "1.0.0", "12.99.99-rc.29").forEach { raw ->
            assertEquals(raw, TestReleaseVersion.parse(raw).name)
        }
    }

    @Test
    fun `a pre-release sorts below its final release and above the version before it`() {
        val codes =
            listOf(
                "0.99.99",
                "1.0.0-alpha.0",
                "1.0.0-alpha.29",
                "1.0.0-beta.0",
                "1.0.0-beta.9",
                "1.0.0-rc.1",
                "1.0.0-rc.2",
                "1.0.0",
                "1.0.1-rc.1",
                "1.0.1",
                "1.1.0-rc.1",
            ).map { TestReleaseVersion.parse(it).code }
        assertEquals(codes.sorted().distinct(), codes)
    }

    @Test
    fun `rc1 and rc dot 1 are the same build number`() {
        assertEquals(TestReleaseVersion.parse("0.9.0-rc.1").code, TestReleaseVersion.parse("0.9.0-rc1").code)
        assertEquals(0 * 10_000 + 9 * 100, TestReleaseVersion.parse("0.9.0").code / 100)
    }

    @Test
    fun `rejects what is not a plain pre-release`() {
        listOf(
            "",
            "1",
            "1.2",
            "1.2.3.4",
            "01.2.3",
            "1.02.3",
            "1.100.0",
            "v1.0.0",
            "1.0.0-",
            "1.0.0-rc",
            "1.0.0-rc.",
            "1.0.0-rc.01",
            "1.0.0-rc.30",
            "1.0.0-dev.1",
            "1.0.0-rc.1.2",
            "1.0.0-rc.1+build5",
            "1.0.0+1",
            " 1.0.0",
            "1.0.0\n",
        ).forEach { raw -> assertFailsWith<IllegalArgumentException>("'$raw'") { TestReleaseVersion.parse(raw) } }
    }

    @Test
    fun `rejects a versionCode above Android's limit`() {
        assertEquals(2_099_999_999, TestReleaseVersion.parse("2099.99.99").code)
        assertFailsWith<IllegalArgumentException> { TestReleaseVersion.parse("2100.99.99") }
        assertFailsWith<IllegalArgumentException> { TestReleaseVersion.parse("99999999999999999999.0.0") }
    }

    @Test
    fun `the strict release parser still rejects them`() {
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.parse("0.9.0-rc1") }
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.parse("1.0.0-rc.2") }
    }
}
