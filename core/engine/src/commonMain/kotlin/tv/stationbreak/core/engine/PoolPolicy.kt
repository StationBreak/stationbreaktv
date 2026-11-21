// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * What the app narrows a pool by, after the rules' own exclusions (ADR-0019, ADR-0020). It is the app's narrowing, not
 * rules-format behaviour: rules format v1 knows nothing of it.
 */
sealed interface PoolPolicy {
    /** No narrowing: exactly the evaluation docs/rules-format.md describes. */
    data object Open : PoolPolicy

    /** A kid channel: only the kids tier ([KidHold.admits]) under the kid ceiling. */
    data object KidsOnly : PoolPolicy

    /**
     * A grown-up channel: nothing a kid channel could take ([GrownUpHold.dropsFromGrownUp]: the kids tier); the ladder
     * applies. Made-for-kids items no kid channel can take stay eligible.
     */
    data object GrownUp : PoolPolicy
}
