package com.paperlight.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class, LibraryEntity::class, WebNovelJobEntity::class, DiscoverySourceEntity::class, RestoreTransactionEntity::class], version = 8, exportSchema = false)
abstract class BookDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        private val addChapterPage = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN chapterPage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN chapterPageCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val resetChapterCounts = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE books SET chapterPage = 0, chapterPageCount = 0")
            }
        }

        private val addLibraries = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `libraries` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT OR IGNORE INTO `libraries` (`id`, `name`, `createdAt`) VALUES ('default', 'My Library', 0)")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `libraryId` TEXT NOT NULL DEFAULT 'default'")
            }
        }

        internal val addWebNovelJobs = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `web_novel_jobs` (`id` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `optionsJson` TEXT NOT NULL, `libraryId` TEXT NOT NULL, `status` TEXT NOT NULL, `completed` INTEGER NOT NULL, `total` INTEGER NOT NULL, `label` TEXT NOT NULL, `error` TEXT, `waitUntil` INTEGER, `runnerToken` TEXT, `checkpointKey` TEXT NOT NULL, `resultBookId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_web_novel_jobs_createdAt` ON `web_novel_jobs` (`createdAt`)")
            }
        }

        internal val addWebNovelJobTitle = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `web_novel_jobs` ADD COLUMN `bookTitle` TEXT")
                db.execSQL("UPDATE `web_novel_jobs` SET `bookTitle` = (SELECT `title` FROM `books` WHERE `books`.`id` = `web_novel_jobs`.`resultBookId`) WHERE `bookTitle` IS NULL")
            }
        }

        internal val addDiscoverySources = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createDiscoverySources(db)
                seedDiscoverySources(db)
            }
        }

        internal val addBackupRecoveryAndAutomaticJobs = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `web_novel_jobs` ADD COLUMN `runnerType` TEXT")
                db.execSQL("ALTER TABLE `web_novel_jobs` ADD COLUMN `autoRun` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `web_novel_jobs` SET `autoRun` = 1 WHERE `status` IN ('QUEUED', 'WAITING_RATE_LIMIT')")
                db.execSQL("CREATE TABLE IF NOT EXISTS `backup_restore_transactions` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        private fun createDiscoverySources(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `discovery_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `configJson` TEXT NOT NULL, `cacheKey` TEXT, `cacheJson` TEXT, `cacheUpdatedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        }

        internal fun seedDiscoverySources(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO `discovery_sources` (`id`, `name`, `kind`, `baseUrl`, `enabled`, `configJson`, `cacheKey`, `cacheJson`, `cacheUpdatedAt`, `createdAt`, `updatedAt`) VALUES ('standard-ebooks', 'Standard Ebooks', 'STANDARD_EBOOKS', 'https://standardebooks.org/ebooks', 1, '{}', NULL, NULL, NULL, 0, 0)")
            db.execSQL("INSERT OR IGNORE INTO `discovery_sources` (`id`, `name`, `kind`, `baseUrl`, `enabled`, `configJson`, `cacheKey`, `cacheJson`, `cacheUpdatedAt`, `createdAt`, `updatedAt`) VALUES ('project-gutenberg', 'Project Gutenberg', 'GUTENBERG', 'https://www.gutenberg.org/ebooks/search.opds/', 1, '{}', NULL, NULL, NULL, 1, 1)")
        }

        @Volatile private var INSTANCE: BookDatabase? = null

        fun getInstance(context: Context): BookDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "paperlight-library.db"
                ).addMigrations(addChapterPage, resetChapterCounts, addLibraries, addWebNovelJobs, addWebNovelJobTitle, addDiscoverySources, addBackupRecoveryAndAutomaticJobs)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            seedDiscoverySources(db)
                        }
                    }).build().also { INSTANCE = it }
            }

        internal fun resetInstanceForTests() = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
