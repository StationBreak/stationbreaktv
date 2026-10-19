// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

/**
 * One `String` per distinct value within a read: a catalogue repeats a few hundred genres and tags over 15,000 items.
 * The item, series and rule-snapshot reads share one, so the same genre is one instance across all of them.
 */
internal class Interner {
    private val strings = HashMap<String, String>()

    fun of(value: String): String {
        val known = strings[value]
        if (known != null) return known
        strings[value] = value
        return value
    }

    fun all(values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        val shared = ArrayList<String>(values.size)
        for (value in values) shared.add(of(value))
        return shared
    }
}
