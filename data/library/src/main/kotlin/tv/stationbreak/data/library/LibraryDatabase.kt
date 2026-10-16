// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The saved library's database. It is a cache: it can be deleted and fetched again, so a schema change drops and
 * rebuilds it ([LibraryDatabases.builder]) instead of migrating. The schema is still versioned and exported to
 * `schemas/`, so a change shows in review. When [VERSION] goes up, the exported file of the new version is committed.
 */
@Database(
    entities = [
        LibraryEntity::class,
        ItemEntity::class,
        GenreEntity::class,
        TagEntity::class,
        CheckpointEntity::class,
        AccountStateEntity::class,
    ],
    version = LibraryDatabase.VERSION,
    exportSchema = true,
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        const val VERSION = 1
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
        return base
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
    }
}
