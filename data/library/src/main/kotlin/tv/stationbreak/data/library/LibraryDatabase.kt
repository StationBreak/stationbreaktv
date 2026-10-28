// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.time.Clock

/**
 * The saved library's database. It is a cache: it can be deleted and fetched again. Up to version 6 a schema change
 * dropped and rebuilt it ([LibraryDatabases.builder]); from version 7 on it migrates (spec 18), because a rebuild also
 * drops the signed-in account and a first launch with no server would then show no channels. A file of version 6 or
 * older, or of any version newer than the code (an older APK sideloaded), is still dropped and rebuilt. A migration
 * that does not produce exactly the schema Room expects makes the open throw instead of rebuilding, so each one has a
 * test that runs it on a file made from the exported schema (Migration7To8Test, and Migration7To8OnDeviceTest on the
 * platform SQLite). The schema is versioned and exported to `schemas/`, so a change shows in review; when [VERSION]
 * goes up, the exported file of the new version is committed.
 * Version 2 added `active_account` (the signed-in account, which the sync checks before every write). Version 3 added
 * the premiere date and exact runtimes (spec 09 Unit 1, shipped in a debug build). Version 4 added the item history
 * (`item_version`, `seenFromMs`, `firstSeenMs`; spec 09 Unit 2): a v3 file cannot be amended in place, because Room
 * refuses a changed schema under an unchanged version, so it moves to 4 and the destructive fallback rebuilds it.
 * Version 5 added `series` and `series_version` and the episode `seasonId` (spec 10 Unit 1); debug builds carry version
 * 4, so a schema is never amended after its PR merges. Version 6 added `collection`, `collection_version`,
 * `collection_member` and `collection_sync` (spec 10 Unit 2); version 5 counts as shipped once Unit 1 merged. Version 7
 * added `sync_meta`, the account's content generation for the lineup refresher (spec 12, #309). Version 8 added
 * `item_studio`, the `studios` columns of `item_version`, `series` and `series_version`, and
 * `sync_meta.backfillPending` (spec 18), through [Migration7To8].
 */
@Database(
    entities = [
        LibraryEntity::class,
        ItemEntity::class,
        ItemVersionEntity::class,
        SeriesEntity::class,
        SeriesVersionEntity::class,
        CollectionEntity::class,
        CollectionVersionEntity::class,
        CollectionMemberEntity::class,
        CollectionSyncEntity::class,
        SyncMetaEntity::class,
        GenreEntity::class,
        TagEntity::class,
        StudioEntity::class,
        CheckpointEntity::class,
        AccountStateEntity::class,
        ActiveAccountEntity::class,
    ],
    version = LibraryDatabase.VERSION,
    exportSchema = true,
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    abstract fun itemHistoryDao(): ItemHistoryDao

    abstract fun seriesDao(): SeriesDao

    abstract fun collectionDao(): CollectionDao

    abstract fun cacheTransactionDao(): CacheTransactionDao

    companion object {
        const val VERSION = 8
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
     * A file of version 7 or later migrates; a schema it has no migration for, older or newer, is dropped and rebuilt
     * empty: the cache is fetched again. [clock] seeds the `sync_meta` rows a migration creates, as the repository's
     * clock seeds the ones it creates ([LibraryDao.seedFrom] for both).
     */
    fun builder(
        context: Context,
        name: String?,
        clock: Clock,
    ): RoomDatabase.Builder<LibraryDatabase> {
        val base =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            } else {
                Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            }
        // The migrations first: the fallbacks apply only where no migration path exists.
        return base
            .addCallback(SecureDeleteCallback)
            .addMigrations(Migration7To8(LibraryDao.seedFrom(clock)))
            .withDestructiveFallbacks()
    }

    /**
     * What the cache does with a schema it has no migration for, older or newer: drop everything and start empty. The
     * order matters: the downgrade call also sets "a migration is required", which the other call then clears.
     * Reversed, an upgrade from an older schema fails to open instead of being dropped and rebuilt (found by
     * CacheRebuildTest when the schema first moved to version 2). Generic, so a test can give a stand-in database of
     * another version exactly these settings.
     */
    fun <T : RoomDatabase> RoomDatabase.Builder<T>.withDestructiveFallbacks(): RoomDatabase.Builder<T> =
        fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .fallbackToDestructiveMigration(dropAllTables = true)
}
