// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * What the app narrows a pool by, after the rules' own exclusions (ADR-0019, ADR-0020). It is the app's narrowing, not
 * rules-format behaviour: rules format v1 knows nothing of it.
 */
sealed interface PoolPolicy {
    /** No narrowing: exactly the evaluation docs/rules-format.md describes. */
    data object Open : PoolPolicy

    /** A kid channel: only what is made for kids ([KidHold.isMadeForKids]) under the kid rating table. */
    data object KidsOnly : PoolPolicy

    /** A grown-up channel: nothing with a children's rating ([KidHold.hasChildrensRating]); the ladder applies. */
    data object GrownUp : PoolPolicy
}
