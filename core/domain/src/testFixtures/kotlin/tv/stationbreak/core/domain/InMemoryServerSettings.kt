// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId

/**
 * A [ServerSettings] in memory for tests (ADR-0004). It can be told to fail, and it records every change, so a test can
 * prove that a pin was cleared or an address kept. Follows the port's contract: it never throws for a fault.
 */
class InMemoryServerSettings(
    initial: ServerRecord? = null,
    initialLibraries: Set<ItemId>? = null,
) : ServerSettings {
    private var record: ServerRecord? = initial
    private var libraries: Set<ItemId>? = initialLibraries

    var failSave = false

    /** Every record saved, in order. */
    val saved = mutableListOf<ServerRecord>()

    /** How many times [clearPin] was called, whether or not there was a pin to drop. */
    var clearPinCalls = 0
        private set

    /** How many times [clearPin] actually dropped a pin. */
    var pinsCleared = 0
        private set

    /** Every library selection saved, in order. */
    val savedLibraries = mutableListOf<Set<ItemId>>()

    val current: ServerRecord? get() = record

    val currentLibraries: Set<ItemId>? get() = libraries

    override suspend fun load(): ServerRecord? = record

    override suspend fun save(record: ServerRecord): Boolean {
        if (failSave) return false
        // A choice of libraries belongs to the server it was made on.
        if (this.record?.address != record.address) libraries = null
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

    override suspend fun loadLibraries(): Set<ItemId>? = libraries

    override suspend fun clearLibraries(): Boolean {
        if (libraries == null) return true
        if (failSave) return false
        libraries = null
        return true
    }

    override suspend fun saveLibraries(ids: Set<ItemId>): Boolean {
        if (failSave || record == null || ids.isEmpty()) return false
        libraries = ids
        savedLibraries += ids
        return true
    }
}
