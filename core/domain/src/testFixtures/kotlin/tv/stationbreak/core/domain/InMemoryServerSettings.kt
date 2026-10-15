// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/**
 * A [ServerSettings] in memory for tests (ADR-0004). It can be told to fail, and it records every change, so a test can
 * prove that a pin was cleared or an address kept. Follows the port's contract: it never throws for a fault.
 */
class InMemoryServerSettings(
    initial: ServerRecord? = null,
) : ServerSettings {
    private var record: ServerRecord? = initial

    var failSave = false

    /** Every record saved, in order. */
    val saved = mutableListOf<ServerRecord>()

    /** How many times [clearPin] was called, whether or not there was a pin to drop. */
    var clearPinCalls = 0
        private set

    /** How many times [clearPin] actually dropped a pin. */
    var pinsCleared = 0
        private set

    val current: ServerRecord? get() = record

    override suspend fun load(): ServerRecord? = record

    override suspend fun save(record: ServerRecord): Boolean {
        if (failSave) return false
        this.record = record
        saved += record
        return true
    }

    override suspend fun clearPin(): Boolean {
        clearPinCalls++
        val held = record ?: return true
        if (held.pin == null) return true
        if (failSave) return false
        record = held.copy(pin = null)
        pinsCleared++
        return true
    }
}
