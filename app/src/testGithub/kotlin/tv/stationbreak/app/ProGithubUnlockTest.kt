// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** The GitHub release ships with Pro fully unlocked (Q-BIZ-1, #119): it is GPL, with no store billing. */
class ProGithubUnlockTest {
    @Test
    fun theGithubReleaseShipsWithProUnlocked() {
        assertTrue("PRO_UNLOCKED must be true in the GitHub release build", BuildConfig.PRO_UNLOCKED)
    }

    // The GitHub build stays FOSS (#99): the Amazon store adapter's source set is not compiled into it.
    @Test
    fun theGithubReleaseCarriesNoAmazonStoreCode() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName("tv.stationbreak.app.di.AmazonStore") }
    }

    // No Amazon SDK on the GitHub runtime classpath (#1063). It reads what each jar holds, not its path, because a
    // transformed AAR's path no longer names its group. True today; it guards the day the Appstore SDK is linked.
    // The githubRelease unit test is given githubReleaseRuntimeClasspath, what ships (app/build.gradle.kts, #1072); the
    // other github variants, which have no such property, scan their own unit-test classpath.
    @Test
    fun theGithubRuntimeClasspathHoldsNoAmazonDeviceArtifact() {
        val shipped = System.getProperty("stationbreak.shippedClasspath")
        val entries = (shipped ?: System.getProperty("java.class.path")).split(File.pathSeparator).map(::File)
        // The scan sees inside jars: the Kotlin standard library is found the same way an SDK would be.
        assertTrue("the scan reads jar contents", entries.any { it.holds("kotlin/") })
        val amazon = entries.filter { it.holds("com/amazon/device/") }.map { it.name }
        assertEquals("Amazon device SDK classes on the github classpath", emptyList<String>(), amazon)
    }

    private fun File.holds(prefix: String): Boolean =
        when {
            isDirectory -> File(this, prefix).exists()
            isFile && name.endsWith(".jar") -> jarHolds(prefix)
            else -> false
        }

    private fun File.jarHolds(prefix: String): Boolean =
        ZipFile(this).use { zip -> zip.entries().asSequence().any { it.name.startsWith(prefix) } }
}
