// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 9 to 10 (spec 26): the cache starts to hold provider ids, and the library set learns holds and adds. The SQL
 * is what Room declares in the exported `10.json`, because Room checks the migrated schema against its own and a
 * difference fails the open.
 *
 * - `providerIds` on `item` and `series` defaults to `''`, no ids ([ProviderIdsCodec]).
 * - `heldUntilMs` and `addStamp` on `library` are nullable and start null: every library kept before now is chosen,
 *   none is held and none is being added.
 * - `sync_meta.backfillPending` is set on every row, so the studios re-read (`StayCurrentEngine.backfill`) reads every
 *   item and series again and writes their provider ids. A row is made first, seeded at [seed] as
 *   `LibraryDao.generationSeed` seeds one, for every account that has an `account_state` row and no `sync_meta` row
 *   (as 7 to 8 and 8 to 9 do), so the flag reaches every account with a saved library. A row tombstoned before now is
 *   never read again, so it keeps `''`.
 * - The content generation does not move: provider ids are no part of what a day is built from.
 *
 * `account_state` is not touched. There is no way down: a downgrade drops and rebuilds the cache, as it always has
 * ([LibraryDatabases]).
 */
internal class Migration9To10(
    private val seed: () -> Long,
) : Migration(FROM, TO) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `item` ADD COLUMN `providerIds` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `series` ADD COLUMN `providerIds` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `library` ADD COLUMN `heldUntilMs` INTEGER")
        connection.execSQL("ALTER TABLE `library` ADD COLUMN `addStamp` INTEGER")
        connection
            .prepare(
                "INSERT OR IGNORE INTO `sync_meta` " +
                    "(`serverId`, `userId`, `contentGeneration`, `failedPasses`, `passRunning`, `backfillPending`) " +
                    "SELECT `serverId`, `userId`, ?, 0, 0, 1 FROM `account_state`",
            ).use { statement ->
                statement.bindLong(1, seed())
                statement.step()
            }
        connection.execSQL("UPDATE `sync_meta` SET `backfillPending` = 1")
    }

    private companion object {
        const val FROM = 9
        const val TO = 10
    }
}
