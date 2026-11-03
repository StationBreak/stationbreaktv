// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 8 to 9 (spec 23, D8): the history of items, series and collections starts to hold names, so the as-of
 * snapshot can give the name an item, a series or a collection had at a cut-off. The SQL is what Room declares in the
 * exported `9.json`, because Room checks the migrated schema against its own and a difference fails the open.
 *
 * - `name` is added, nullable, to `item_version`, `series_version` and `collection_version`.
 * - Each existing version row takes the name of the current row of the same `(serverId, userId, id)`. A version is an
 *   earlier state of that row, and the name it had before the rename that this cache never saw is not known; the
 *   current name is the best the file holds, and the one place it can be wrong is a rename made before the update. A
 *   tombstoned row keeps its name, so its versions get it too (E2). Rows are matched on the account, so one item ID
 *   held under two accounts gets each account's own name.
 * - Every account's `contentGeneration` moves by one (D5, E8), so a persisted day built before the update misses once
 *   and is built again with names. An account that has an `account_state` row and no `sync_meta` row reads
 *   generation 0, so a row is made for it first, seeded at [seed] ([LibraryDao.seedFrom]) as 7 to 8 seeds one, and
 *   moves with the rest: its first generation is above every old one.
 *
 * The names are text a person typed: the SQL binds none, logs none and folds none. There is no way down: a downgrade
 * drops and rebuilds the cache, as it always has ([LibraryDatabases]).
 */
internal class Migration8To9(
    private val seed: () -> Long,
) : Migration(FROM, TO) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in TABLES) {
            connection.execSQL("ALTER TABLE `${table.version}` ADD COLUMN `name` TEXT")
            connection.execSQL(
                "UPDATE `${table.version}` SET `name` = (SELECT c.`name` FROM `${table.current}` c " +
                    "WHERE c.`serverId` = `${table.version}`.`serverId` AND c.`userId` = `${table.version}`.`userId` " +
                    "AND c.`${table.idColumn}` = `${table.version}`.`${table.idColumn}`)",
            )
        }
        // An account with an `account_state` row and no `sync_meta` row reads generation 0, which a persisted day of
        // its own may hold: make the row first, seeded as `LibraryDao.generationSeed` seeds one, so the bump below
        // takes it above every old value too (Migration7To8 makes its rows the same way).
        connection
            .prepare(
                "INSERT OR IGNORE INTO `sync_meta` " +
                    "(`serverId`, `userId`, `contentGeneration`, `failedPasses`, `passRunning`, `backfillPending`) " +
                    "SELECT `serverId`, `userId`, ?, 0, 0, 0 FROM `account_state`",
            ).use { statement ->
                statement.bindLong(1, seed())
                statement.step()
            }
        connection.execSQL("UPDATE `sync_meta` SET `contentGeneration` = `contentGeneration` + 1")
    }

    private class Table(
        val version: String,
        val current: String,
        val idColumn: String,
    )

    private companion object {
        const val FROM = 8
        const val TO = 9
        val TABLES =
            listOf(
                Table("item_version", "item", "itemId"),
                Table("series_version", "series", "seriesId"),
                Table("collection_version", "collection", "collectionId"),
            )
    }
}
