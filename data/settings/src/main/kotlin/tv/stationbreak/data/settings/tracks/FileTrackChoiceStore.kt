// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.tracks

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.tracks.SubtitlePreference
import tv.stationbreak.core.domain.tracks.TrackChoiceStore
import tv.stationbreak.core.domain.tracks.TrackPreferences
import tv.stationbreak.core.domain.tracks.VersionMemory
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.data.settings.server.DiskRecordFile
import tv.stationbreak.data.settings.server.RecordFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The [TrackChoiceStore] on one small text file per account in `noBackupFilesDir/trackchoices`, replaced atomically.
 * The file is named by the SHA-256 of `server/user`, so no server or user ID appears in a path. Its lines are
 * `a=<language>`, `s=off`, `s=on:<language>` or `s=on:<language>:sdh`, and `v=<item>\t<source>` (oldest first, at most
 * [TrackChoiceStore.MAX_VERSIONS]); a line it cannot read is skipped. It holds languages and IDs, never a token, and
 * nothing in it is sent to the server. Sign-out leaves it. Faults come back as an empty record or `false`, never as an
 * exception, and the store logs nothing.
 *
 * Cancellation behaves as for `FileLastChannelStore`.
 */
@Suppress("TooManyFunctions") // One override per port call, and the record's reader and writer.
class FileTrackChoiceStore internal constructor(
    private val open: (name: String) -> RecordFile,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrackChoiceStore {
    constructor(context: Context) : this(
        { name -> DiskRecordFile(File(context.noBackupFilesDir, DIR_NAME).resolve(name)) },
    )

    private class Record(
        val preferences: TrackPreferences = TrackPreferences(),
        val versions: VersionMemory = VersionMemory(),
    )

    // One writer at a time, and a read never sees a file mid-rename.
    private val lock = Mutex()

    override suspend fun preferences(account: LibraryAccount): TrackPreferences =
        locked { read(account)?.preferences } ?: TrackPreferences()

    override suspend fun saveAudioLanguage(
        account: LibraryAccount,
        language: String?,
    ): Boolean =
        language?.let(::validLanguage) != false &&
            update(account) { Record(it.preferences.copy(audioLanguage = language), it.versions) }

    override suspend fun saveSubtitles(
        account: LibraryAccount,
        choice: SubtitlePreference,
    ): Boolean =
        (choice as? SubtitlePreference.On)?.let { validLanguage(it.language) } != false &&
            update(account) { Record(it.preferences.copy(subtitles = choice), it.versions) }

    override suspend fun version(
        account: LibraryAccount,
        item: ItemId,
    ): MediaSourceId? = locked { read(account)?.versions?.get(item) }

    override suspend fun saveVersion(
        account: LibraryAccount,
        item: ItemId,
        source: MediaSourceId,
    ): Boolean = validId(source.value) && update(account) { Record(it.preferences, it.versions.with(item, source)) }

    // A constant: nothing about the contents may reach a log through this.
    override fun toString(): String = "FileTrackChoiceStore"

    private suspend fun update(
        account: LibraryAccount,
        change: (Record) -> Record,
    ): Boolean =
        locked {
            try {
                val file = open(fileName(account))
                val current = parse(file.read())
                file.write(render(change(current)))
                true
            } catch (_: IOException) {
                false
            }
        }

    private fun read(account: LibraryAccount): Record? =
        try {
            parse(open(fileName(account)).read())
        } catch (_: IOException) {
            null
        }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun parse(text: String?): Record {
        var audio: String? = null
        var subtitles: SubtitlePreference? = null
        var versions = VersionMemory()
        text?.lineSequence()?.take(MAX_LINES)?.forEach { line ->
            val value = line.substringAfter('=', "")
            when (line.substringBefore('=')) {
                "a" -> if (validLanguage(value)) audio = value
                "s" -> subtitles = subtitleOf(value) ?: subtitles
                "v" -> versions = versionOf(value)?.let { (item, source) -> versions.with(item, source) } ?: versions
            }
        }
        return Record(TrackPreferences(audio, subtitles), versions)
    }

    private fun subtitleOf(value: String): SubtitlePreference? =
        when {
            value == "off" -> SubtitlePreference.Off
            value.startsWith("on:") -> subtitlesOn(value.removePrefix("on:"))
            else -> null
        }

    /** `<language>` or `<language>:sdh`. */
    private fun subtitlesOn(value: String): SubtitlePreference? {
        val language = value.substringBefore(':')
        val flag = value.substringAfter(':', "")
        return when {
            !validLanguage(language) -> null
            flag.isEmpty() -> SubtitlePreference.On(language)
            flag == SDH -> SubtitlePreference.On(language, hearingImpaired = true)
            else -> null
        }
    }

    private fun versionOf(value: String): Pair<ItemId, MediaSourceId>? {
        val item = value.substringBefore('\t')
        val source = value.substringAfter('\t', "")
        if (item.length > MAX_ID || source.length > MAX_ID || !validId(source)) return null
        return try {
            ItemId(item) to MediaSourceId(source)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun render(record: Record): String =
        buildList {
            record.preferences.audioLanguage?.let { add("a=$it") }
            when (val choice = record.preferences.subtitles) {
                null -> Unit
                SubtitlePreference.Off -> add("s=off")
                is SubtitlePreference.On -> add("s=on:${choice.language}" + if (choice.hearingImpaired) ":$SDH" else "")
            }
            record.versions.entries.forEach { (item, source) -> add("v=${item.value}\t${source.value}") }
        }.joinToString("\n")

    /** A language is a short code (letters, digits, '-'): it is written bare into a line of the record. */
    private fun validLanguage(value: String): Boolean {
        val short = value.length in 1..MAX_LANGUAGE
        return short && value.all { it.isLetterOrDigit() || it == '-' }
    }

    /** An ID goes after a tab in one line: it may hold neither a line break nor a tab, and is not blank or huge. */
    private fun validId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_ID && value.none { it == '\t' || it == '\n' || it == '\r' }

    private fun fileName(account: LibraryAccount): String {
        val key = "${account.server.value}/${account.user.value}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { HEX[it.toInt() and BYTE_MASK] }
    }

    private companion object {
        const val DIR_NAME = "trackchoices"
        const val SDH = "sdh"
        const val MAX_LANGUAGE = 16
        const val MAX_ID = 128
        const val MAX_LINES = TrackChoiceStore.MAX_VERSIONS + 8
        const val BYTE_MASK = 0xff
        val HEX = Array(256) { (if (it < 16) "0" else "") + it.toString(16) }
    }
}
