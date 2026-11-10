// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId

/**
 * A [ServerSettings] in memory for tests (ADR-0004). It can be told to fail, and it records every change, so a test can
 * prove that a pin was cleared or an address kept. Follows the port's contract: it never throws for a fault, and the
 * stored selection keeps the same rules as the file ([StoredSelection.mayReplace]; a clear keeps the seq; with nothing
 * chosen only a setup's save is taken).
 */
class InMemoryServerSettings(
    initial: ServerRecord? = null,
    initialLibraries: Set<ItemId>? = null,
) : SelectionStore {
    private var record: ServerRecord? = initial
    private var selection: StoredSelection? = initialLibraries?.let(::StoredSelection)

    // What outlives a selection: a clear or another address keeps them.
    private var seq = 0L
    private var listings = 0L
    private var resetSeq = 0L

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

    /** Every stored selection saved through [saveSelection], in order. */
    val savedSelections = mutableListOf<StoredSelection>()

    val currentLibraries: Set<ItemId>? get() = selection?.chosen

    val currentSelection: StoredSelection? get() = selection

    override suspend fun load(): ServerRecord? = record

    override suspend fun save(record: ServerRecord): Boolean {
        if (failSave) return false
        // A choice of libraries belongs to the server it was made on.
        if (this.record?.address != record.address) selection = null
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

    override suspend fun loadLibraries(): Set<ItemId>? = selection?.chosen

    override suspend fun clearLibraries(): Boolean {
        if (selection == null) return true
        if (failSave) return false
        selection = null
        return true
    }

    override suspend fun loadSelection(): StoredSelection? = selection

    override suspend fun loadSelectionSeq(): Long? = if (record == null) null else seq

    // With nothing chosen only a setup's save is taken, as the file store takes it: a change never is, whatever its shape.
    override suspend fun saveSelection(
        selection: StoredSelection,
        setup: Boolean,
    ): Boolean {
        if (failSave || record == null) return false
        if (!selection.mayReplace(this.selection, seq, resetSeq)) return false
        if (this.selection == null && !setup) return false
        keep(selection)
        savedSelections += selection
        return true
    }

    override suspend fun loadPlayableLibraries(): Set<ItemId>? = selection?.takeUnless { it.gone }?.chosen

    private fun keep(stored: StoredSelection) {
        selection = stored
        seq = stored.seq
        listings = stored.listings
        resetSeq = stored.resetSeq
    }
}
