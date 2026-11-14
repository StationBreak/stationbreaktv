// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

/**
 * A days file as the format-7 writer made it (`/days/days-v7.bin`), for the tests that read one.
 *
 * Its key carries the hash of the lineup inputs and the list of holds as they were when it was written, and both move
 * when a rule's inputs or a hold does (the studio fold did, in #764). These tests are about reading a format-7 body,
 * not about those, so [keyFor] puts the file's own inputs and holds into the key the test built and leaves every other
 * part as the test made it.
 */
internal object DaysV7Fixture {
    val bytes: ByteArray
        get() = checkNotNull(DaysV7Fixture::class.java.getResourceAsStream("/days/days-v7.bin")).readBytes()

    private val inputs = Regex("\"inputs\":\"([0-9a-f]+)\"")
    private val holds = Regex("\"holds\":\\[([^\\]]*)]")

    fun keyFor(key: DayKey): DayKey {
        val text = bytes.decodeToString()
        val written = checkNotNull(inputs.find(text)).groupValues[1]
        val heldThen = checkNotNull(holds.find(text)).groupValues[1].split(',').map { it.trim().trim('"') }
        return key.copy(inputs = written, holds = heldThen)
    }
}
