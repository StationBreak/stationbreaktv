// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * Format version 4 and its release gate (docs/adr/0026-studio-fold-2.md, Versioning). A file that says 4 matches its
 * `studio` sources, `studio` exclusions and `studios` filter under studio fold 2; a file that says 1 to 3 keeps studio
 * fold 1 exactly.
 *
 * The app reads 4 before it writes it: [WRITES_FORMAT_4] stayed false until the release gate. On 2026-11-13 the owner
 * waived the gate's reader-before-writer order for the Fire app, the only port with a release (the ADR's gate ruling:
 * it fails closed); the Roku and Vega OS ports must read 4 before their first release.
 */
object StudioFold2Gate {
    /** The first format version that matches studios under studio fold 2. */
    const val FORMAT_VERSION: Int = 4

    /**
     * True once the gate has passed: the app then writes every rules file that holds a `studio` source, a `studio`
     * exclusion or a `studios` filter at [FORMAT_VERSION], and upgrades such a file that says 3 whenever it reads,
     * imports or saves it. While false, nothing is upgraded, and a file that says 4 is still never written lower.
     */
    const val WRITES_FORMAT_4: Boolean = true
}
