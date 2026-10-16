// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The saved library's database. It is a cache: it can be deleted and fetched again, so a schema change drops and
 * rebuilds it ([LibraryDatabases.builder]) instead of migrating. The schema is still versioned and exported to
 * `schemas/`, so a change shows in review. When [VERSION] goes up, the exported file of the new version is committed.
 * Version 2 added `active_account` (the signed-in account, which the sync checks before every write).
 */
@Database(
    entities = [
        LibraryEntity::class,
        ItemEntity::class,
        GenreEntity::class,
        TagEntity::class,
        CheckpointEntity::class,
        AccountStateEntity::class,
        ActiveAccountEntity::class,
    ],
    version = LibraryDatabase.VERSION,
    exportSchema = true,
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        const val VERSION = 2
    }
}

/**
 * Overwrites freed pages with zeros, so a deleted library's titles are not left in the database file (Angua review).
 * `secure_delete` needs SQLite 3.6.18, and API 25 has 3.9.
 */
private object SecureDeleteCallback : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA secure_delete = ON")
    }
}

internal object LibraryDatabases {
    /**
     * A builder for the database [name] (in the app's `databases` directory), or an in-memory one when [name] is null.
     * A schema it has no migration for, older or newer, is dropped and rebuilt empty: the cache is fetched again.
     */
    fun builder(
        context: Context,
        name: String?,
    ): RoomDatabase.Builder<LibraryDatabase> {
        val base =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            } else {
                Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            }
        // The order matters: the downgrade call also sets "a migration is required", which the other call then clears.
        // Reversed, an upgrade from an older schema fails to open instead of being dropped and rebuilt (found by
        // CacheRebuildTest when the schema first moved to version 2).
        return base
            .addCallback(SecureDeleteCallback)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .fallbackToDestructiveMigration(dropAllTables = true)
    }
}
