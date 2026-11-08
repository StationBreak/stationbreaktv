// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 10 to 11 (spec 26, Part 4): the library row learns its lifecycle, and the cache gets the table that records
 * the other libraries listing an item. Every statement is what Room declares in the exported `11.json` (copied from it,
 * the table name filled in), because Room checks the migrated schema against its own and a difference fails the open.
 *
 * - `library.walkedStamp` and `library.coveredFromMs` are nullable and start null: no add walk has been proven, and no
 *   library owes its next incremental pass an earlier start.
 * - `library.rewalkPending` starts 0: no library is marked to be read again.
 * - `item_also_listed` starts empty. An overlap that existed before now is found by the first ID-listing reconcile
 *   after it (no `backfillPending` is set: Amendment 3, data MED-5).
 * - The `item(serverId, userId, seriesId)` index is the only real cost, one pass over the items.
 *
 * It writes no row: `sync_meta`, `account_state` and every item are as they were, and the content generation does not
 * move. There is no way down: a downgrade drops and rebuilds the cache, as it always has ([LibraryDatabases]).
 */
internal class Migration10To11 : Migration(FROM, TO) {
    override fun migrate(connection: SQLiteConnection) {
        for (sql in STATEMENTS) connection.execSQL(sql)
    }

    companion object {
        private const val FROM = 10
        private const val TO = 11

        /** The statements, in order; `Migration10To11Test` checks each CREATE against `11.json`. */
        val STATEMENTS: List<String> =
            listOf(
                "ALTER TABLE `library` ADD COLUMN `walkedStamp` INTEGER",
                "ALTER TABLE `library` ADD COLUMN `rewalkPending` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `library` ADD COLUMN `coveredFromMs` INTEGER",
                "CREATE TABLE IF NOT EXISTS `item_also_listed` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                    "`itemId` TEXT NOT NULL, `libraryId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`serverId`, `userId`, `itemId`, `libraryId`), " +
                    "FOREIGN KEY(`serverId`, `userId`, `itemId`) REFERENCES `item`(`serverId`, `userId`, `itemId`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`serverId`, `userId`, `libraryId`) " +
                    "REFERENCES `library`(`serverId`, `userId`, `libraryId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_item_also_listed_serverId_userId_libraryId` " +
                    "ON `item_also_listed` (`serverId`, `userId`, `libraryId`)",
                "CREATE INDEX IF NOT EXISTS `index_item_serverId_userId_seriesId` " +
                    "ON `item` (`serverId`, `userId`, `seriesId`)",
            )
    }
}
