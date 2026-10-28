// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 7 to 8 (spec 18): the cache starts to hold studios. It is the cache's first migration: a rebuild would drop
 * `active_account` and leave a first launch with no server showing no channels. The SQL is what Room declares in the
 * exported `8.json`, because Room checks the migrated schema against its own and a difference fails the open.
 *
 * - `item_studio` mirrors `item_genre`: the same primary key and position, and the cascade from `item`.
 * - `studios` on `item_version`, `series` and `series_version` defaults to `''`, the codec's empty list, so every row
 *   kept before now reads "no studios" and an as-of read never borrows the current ones.
 * - `sync_meta.backfillPending` asks the backfill step to read every item and series again. It is set on every
 *   row, and a row is made, seeded at [seed] as `LibraryDao.generationSeed` seeds one, for every account that has an
 *   `account_state` row and no `sync_meta` row, so a row made later with the flag off cannot lose it. `account_state`
 *   (and so `lastSuccessMs`, which routes to the first-sync screen when null) is not touched.
 *
 * There is no way down: a downgrade drops and rebuilds the cache, as it always has ([LibraryDatabases]).
 */
internal class Migration7To8(
    private val seed: () -> Long,
) : Migration(FROM, TO) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_studio` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                "`itemId` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "PRIMARY KEY(`serverId`, `userId`, `itemId`, `position`), " +
                "FOREIGN KEY(`serverId`, `userId`, `itemId`) REFERENCES `item`(`serverId`, `userId`, `itemId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        for (table in VERSIONED_TABLES) {
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `studios` TEXT NOT NULL DEFAULT ''")
        }
        connection.execSQL("ALTER TABLE `sync_meta` ADD COLUMN `backfillPending` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE `sync_meta` SET `backfillPending` = 1")
        connection
            .prepare(
                "INSERT OR IGNORE INTO `sync_meta` " +
                    "(`serverId`, `userId`, `contentGeneration`, `failedPasses`, `passRunning`, `backfillPending`) " +
                    "SELECT `serverId`, `userId`, ?, 0, 0, 1 FROM `account_state`",
            ).use { statement ->
                statement.bindLong(1, seed())
                statement.step()
            }
    }

    private companion object {
        const val FROM = 7
        const val TO = 8
        val VERSIONED_TABLES = listOf("item_version", "series", "series_version")
    }
}
