// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Query

/** What one [CacheTransactionDao.writeCollection] did. [stamp] is null when the read changed nothing and took none. */
internal data class CollectionWrite(
    val stamp: Long?,
    val inserted: Boolean,
    val revived: Boolean,
    val added: Int,
    val removed: Int,
)

/** One member of a collection as the as-of read holds it: its ID and its kind column. */
internal class HeldMember(
    val memberId: String,
    val kind: String,
)

/** One collection as this TV held it before a cut-off, with its members in member-ID order. */
internal class HeldCollection(
    val collectionId: String,
    val members: List<HeldMember>,
)

/** The SQL of the cached collections and their membership history (spec 10, Unit 2). */
@Dao
internal abstract class CollectionDao {
    // Reads, for the tests and diagnostics.

    @Query("SELECT * FROM collection WHERE serverId = :serverId AND userId = :userId ORDER BY collectionId")
    abstract suspend fun all(
        serverId: String,
        userId: String,
    ): List<CollectionEntity>

    @Query(
        "SELECT * FROM collection_version WHERE serverId = :serverId AND userId = :userId " +
            "ORDER BY collectionId, seenFromMs",
    )
    abstract suspend fun allVersions(
        serverId: String,
        userId: String,
    ): List<CollectionVersionEntity>

    @Query(
        "SELECT * FROM collection_member WHERE serverId = :serverId AND userId = :userId " +
            "ORDER BY collectionId, memberId, seenFromMs",
    )
    abstract suspend fun allMembers(
        serverId: String,
        userId: String,
    ): List<CollectionMemberEntity>
}
