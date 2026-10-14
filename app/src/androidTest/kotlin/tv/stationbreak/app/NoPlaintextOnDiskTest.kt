// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredDeviceIdProvider
import java.io.File
import javax.inject.Inject

/**
 * Spec 04, Unit 2: after the app saves a session and a device ID, neither the dummy token nor the device ID appears in
 * the app's data directory (files, `no_backup`, cache, code cache, shared prefs, databases, and any temp or `.bak` file
 * an atomic write leaves) or in its logcat. Each needle is looked for as bytes, as UTF-16, as Base64 and as hex.
 * The scanner is proven by positive controls first, so a clean result can't come from a scanner that finds nothing.
 */
@HiltAndroidTest
class NoPlaintextOnDiskTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var store: SecretStore

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val controlFiles = mutableListOf<File>()

    // The secrets under test, as named forms. The labels never contain a value, so a failure message can't leak one.
    private val needles =
        mapOf(
            "token" to SecretFixtures.DUMMY_TOKEN,
            "device id" to SecretFixtures.FAKE_DEVICE_ID,
        )

    @Before
    fun inject() {
        hilt.inject()
        wipe()
    }

    @After
    fun tidy() {
        controlFiles.forEach { it.delete() }
        wipe()
    }

    private fun wipe() {
        runBlocking {
            store.clear(SessionRepository.SESSION_KEY)
            store.clear(StoredDeviceIdProvider.KEY)
        }
    }

    private fun saveEverything() =
        runBlocking {
            SessionRepository(store, FakeMediaServer()).save(SecretFixtures.session)
            val id = StoredDeviceIdProvider(store) { SecretFixtures.FAKE_DEVICE_ID }.deviceId()
            assertEquals(SecretFixtures.FAKE_DEVICE_ID, id)
            // Exercise the other paths too, so a log line on any of them would be in the logcat below.
            val server = FakeMediaServer().apply { resumeResult = ServerResult.Success(SecretFixtures.session) }
            SessionRepository(store, server).restore(SecretFixtures.address)
        }

    // --- the forms a secret can take on disk ---

    private fun hex(
        bytes: ByteArray,
        upper: Boolean,
    ): ByteArray {
        val digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        return ByteArray(bytes.size * 2) { i ->
            val b = bytes[i / 2].toInt() and 0xff
            digits[if (i % 2 == 0) b shr 4 else b and 0x0f].code.toByte()
        }
    }

    // Base64 of the needle at each of the three byte alignments it can have inside a larger encoded value. The chars
    // that mix with a neighbouring byte are dropped, so what is left appears whatever surrounds the needle.
    private fun base64Forms(bytes: ByteArray): List<ByteArray> =
        listOf(Base64.NO_WRAP, Base64.NO_WRAP or Base64.URL_SAFE).flatMap { flags ->
            (0..2).map { shift ->
                val prefix = ByteArray(shift) { 'x'.code.toByte() }
                val encoded = Base64.encodeToString(prefix + bytes, flags or Base64.NO_PADDING)
                val lead = intArrayOf(0, 2, 3)[shift]
                encoded.substring(lead, encoded.length - 2).toByteArray(Charsets.US_ASCII)
            }
        }

    private fun forms(value: String): List<ByteArray> {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val utf16 = listOf(Charsets.UTF_16LE, Charsets.UTF_16BE).map { value.toByteArray(it) }
        return listOf(utf8, hex(utf8, false), hex(utf8, true)) + utf16 + base64Forms(utf8)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        needle.isNotEmpty() &&
            (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    // The directories to walk: the whole data dir (including no_backup, cache, code_cache, shared_prefs, databases)
    // and the app's external dirs. Symlinks are not followed: lib/ points outside the app's data.
    private fun roots(): List<File> =
        listOfNotNull(context.dataDir, context.createDeviceProtectedStorageContext().dataDir) +
            context.getExternalFilesDirs(null).filterNotNull() +
            context.externalCacheDirs.filterNotNull()

    private fun walk(
        dir: File,
        out: MutableList<File>,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (OsConstants.S_ISLNK(Os.lstat(child.path).st_mode)) continue
            if (child.isDirectory) walk(child, out) else out += child
        }
    }

    private fun allFiles(): List<File> {
        val out = mutableListOf<File>()
        roots().distinct().forEach { walk(it, out) }
        return out
    }

    // The labels of the (file, needle) pairs found, with the path under the data dir and never the value.
    private fun findings(secrets: Map<String, String>): List<String> {
        val formsByLabel = secrets.mapValues { forms(it.value) }
        val base = context.dataDir.absolutePath
        return allFiles().flatMap { file ->
            val bytes = file.readBytes()
            val path = file.absolutePath.removePrefix(base)
            formsByLabel.filterValues { list -> list.any { bytes.contains(it) } }.keys.map { "$path holds the $it" } +
                formsByLabel
                    .filterValues { list ->
                        list.any { path.toByteArray().contains(it) }
                    }.keys
                    .map { "$path is named after the $it" }
        }
    }

    // --- tests ---

    @Test
    fun theScannerFindsEachFormInEachKindOfDirectory() {
        val control = "dummy-control-0002"
        val utf8 = control.toByteArray()
        val pad = ByteArray(3) { 'q'.code.toByte() }
        // Base64 of the control embedded in a longer value, at each of the three alignments, in both alphabets.
        val embedded =
            listOf(Base64.NO_WRAP, Base64.NO_WRAP or Base64.URL_SAFE).flatMap { flags ->
                (0..2).map { shift ->
                    "b64-$flags-$shift" to Base64.encode(pad.copyOf(shift) + utf8 + "tail".toByteArray(), flags)
                }
            }
        val bodies =
            listOf(
                "plain" to utf8,
                "utf16le" to control.toByteArray(Charsets.UTF_16LE),
                "utf16be" to control.toByteArray(Charsets.UTF_16BE),
                "hex-lower" to hex(utf8, false),
                "hex-upper" to hex(utf8, true),
            ) + embedded
        val dirs = listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir, context.codeCacheDir)
        for (dir in dirs) {
            for ((name, body) in bodies) {
                val file = File(dir, "control-$name.tmp").also { controlFiles += it }
                file.writeBytes(byteArrayOf(0, 1, 2) + body + byteArrayOf(3, 4))
                val found = findings(mapOf("control" to control)).any { it.contains(file.name) }
                file.delete()
                assertTrue("the scanner missed $name in ${dir.name}", found)
            }
        }
    }

    @Test
    fun theDataDirectoryHoldsTheBlobsButNeitherTheTokenNorTheDeviceId() {
        saveEverything()

        val blobs = File(context.noBackupFilesDir, "keystore").listFiles().orEmpty()
        assertEquals("one blob per secret", 2, blobs.size)
        assertTrue("the data dir was walked", allFiles().size >= blobs.size)
        assertEquals(emptyList<String>(), findings(needles))
    }

    @Test
    fun noSecretReachesLogcatForThisProcess() {
        saveEverything()
        // A positive control, so the check below can't pass on an empty dump.
        val control = "dummy-control-0003"
        Log.d("NoPlaintextOnDiskTest", "control $control")

        val dump = logcat()
        assertTrue("the logcat dump is empty", dump.isNotEmpty())
        assertTrue("logcat did not show the control line", dump.contains(control.toByteArray()))
        // The whole dump, not only this PID's lines: stricter, and the dummy values appear nowhere else.
        for ((label, value) in needles) {
            assertTrue("logcat holds the $label", forms(value).none { dump.contains(it) })
        }
        assertTrue("this process logged nothing", dump.contains(" ${Process.myPid()} ".toByteArray()))
    }

    private fun logcat(): ByteArray {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        return bytes
    }
}
