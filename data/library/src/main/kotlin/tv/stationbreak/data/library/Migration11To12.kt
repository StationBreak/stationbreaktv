// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 11 to 12 (#607): `item` gains `seriesName`, the server's own name for an episode's series, nullable and with
 * no default. It is a display fallback for an episode whose series row is not held, and nothing schedules on it. The
 * statement is what Room declares in the exported `12.json`, because Room checks the migrated schema against its own.
 *
 * No row is written and no backfill is set: an existing episode gets the name the next time its page is written, and
 * until then shows what it showed before. `item_version` is untouched (the name is not a schedule field), the content
 * generation does not move, and there is no way down: a downgrade drops and rebuilds the cache ([LibraryDatabases]).
 */
internal class Migration11To12 : Migration(FROM, TO) {
    override fun migrate(connection: SQLiteConnection) {
        for (sql in STATEMENTS) connection.execSQL(sql)
    }

    companion object {
        private const val FROM = 11
        private const val TO = 12

        /** The statements, in order. */
        val STATEMENTS: List<String> = listOf("ALTER TABLE `item` ADD COLUMN `seriesName` TEXT")
    }
}
