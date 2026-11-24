// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The vector files of the hold versions before `kid-hold-11` are frozen byte for byte (#987, #1124): a later version
 * adds a delta file and names the rows it supersedes, it never edits an earlier file.
 */
class FrozenHoldVectorsTest {
    private val dir = File(requireNotNull(System.getProperty("rulesFormat.dir")) { "rulesFormat.dir is not set" })

    @Test
    fun theEarlierVectorFilesAreUnchanged() {
        for ((name, sha256) in FROZEN) {
            val bytes = File(dir, "vectors/$name").readBytes()
            val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            assertEquals(sha256, actual, name)
        }
    }

    private companion object {
        val FROZEN =
            mapOf(
                "kid-hold-4.json" to "e583e36ca67e8b65143128d7189fc48468f44419468c038febbc925c685765d0",
                "kid-hold-5.json" to "172530af5c74d1cfea171b7ddedeee4ecccd406eae2e6c00ba33d8bf77148dbb",
                "kid-hold-6.json" to "9a935211861d0286f4439ba95817c49557af0f88904b54d4cf610cbca5705738",
                "kid-hold-7.json" to "02be19de775dd24f59c89504729c4e06444e5d3c44a2073f8002d857f26a0701",
                "kid-hold-8.json" to "ddaf5329c4d8a2b12f225a70545d2293ac11fc2345b7a177897ccb8f3c499ccc",
                "kid-hold-9.json" to "5f405cf8583a4d47b290a78ea9d912b57eed9e7fff1942ba0ea6ad4cb140122b",
                "kid-hold-10.json" to "4b009b1c5cfe071cae9e7f30db4c87e8ba457a6cf7462d62dc5302e20d3b3ae7",
                "grown-up-hold-9.json" to "34af6084afa59a62264f0b53cb3f6f26f5a21719484251461cb5563f98154fbd",
            )
    }
}
