// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 12 to 13 (ADR-0029): `series_version` gains `providerIds`, so a day built as of a cut-off reads the ids a
 * series had then, as it reads its rating and tags (the kid hold's bump list matches on them). The statement is what
 * Room declares in the exported `13.json`, because Room checks the migrated schema against its own.
 *
 * Existing version rows get `''`, no ids: a version written before now never recorded them and they cannot be
 * recovered (the series row holds only today's ids). So a cut-off older than the first id change after this update
 * reads no ids. No row is written, no backfill is set, the content generation does not move, and there is no way down:
 * a downgrade drops and rebuilds the cache ([LibraryDatabases]).
 */
internal class Migration12To13 : Migration(FROM, TO) {
    override fun migrate(connection: SQLiteConnection) {
        for (sql in STATEMENTS) connection.execSQL(sql)
    }

    companion object {
        private const val FROM = 12
        private const val TO = 13

        /** The statements, in order. */
        val STATEMENTS: List<String> =
            listOf("ALTER TABLE `series_version` ADD COLUMN `providerIds` TEXT NOT NULL DEFAULT ''")
    }
}
