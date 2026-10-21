package app.marlboroadvance.mpvex.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.database.repository.PlaybackStateRepositoryImpl
import app.marlboroadvance.mpvex.database.repository.PlaylistRepository
import app.marlboroadvance.mpvex.database.repository.RecentlyPlayedRepositoryImpl
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.marlboroadvance.mpvex.domain.thumbnail.ThumbnailRepository
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Migration from version 1 (v1.0.0) to version 2 (v1.1.0)
 *
 * This is a squashed migration that handles ALL schema changes between v1.0.0 and v1.1.0
 *
 * Version 1 (v1.0.0) had 3 tables:
 * - PlaybackStateEntity (with secondarySid and secondarySubDelay columns, no videoZoom)
 * - RecentlyPlayedEntity (with columns: id, filePath, fileName, timestamp, launchSource)
 * - ExternalSubtitleEntity
 *
 * Version 2 (v1.1.0) changes:
 * - PlaybackStateEntity: Removes secondarySid and secondarySubDelay, adds videoZoom
 * - Removes ExternalSubtitleEntity table
 * - Adds video_metadata_cache table (for MediaInfo caching)
 * - Adds network_connections table (for SMB/FTP/WebDAV)
 * - Adds PlaylistEntity table (for custom playlists)
 * - Adds PlaylistItemEntity table (playlist items with foreign key)
 * - Adds 6 new columns to RecentlyPlayedEntity (videoTitle, duration, fileSize, width, height, playlistId)
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_1_2", "Starting migration from v1.0.0 to v1.1.0")

      // ===== 1. Update PlaybackStateEntity: Remove secondarySid/secondarySubDelay, add videoZoom =====
      android.util.Log.d("Migration_1_2", "Updating PlaybackStateEntity schema")

      // Create new PlaybackStateEntity table with correct schema
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `PlaybackStateEntity_new` (
          `mediaTitle` TEXT NOT NULL,
          `lastPosition` INTEGER NOT NULL,
          `playbackSpeed` REAL NOT NULL,
          `videoZoom` REAL NOT NULL DEFAULT 0.0,
          `sid` INTEGER NOT NULL,
          `subDelay` INTEGER NOT NULL,
          `subSpeed` REAL NOT NULL,
          `aid` INTEGER NOT NULL,
          `audioDelay` INTEGER NOT NULL,
          `timeRemaining` INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(`mediaTitle`)
        )
        """.trimIndent(),
      )

      // Copy data from old table (excluding secondarySid and secondarySubDelay, adding videoZoom with default)
      db.execSQL(
        """
        INSERT INTO `PlaybackStateEntity_new` 
        (`mediaTitle`, `lastPosition`, `playbackSpeed`, `videoZoom`, `sid`, `subDelay`, 
         `subSpeed`, `aid`, `audioDelay`, `timeRemaining`)
        SELECT `mediaTitle`, `lastPosition`, `playbackSpeed`, 0.0, `sid`, `subDelay`, 
               `subSpeed`, `aid`, `audioDelay`, `timeRemaining`
        FROM `PlaybackStateEntity`
        """.trimIndent(),
      )

      // Drop old table and rename new one
      db.execSQL("DROP TABLE `PlaybackStateEntity`")
      db.execSQL("ALTER TABLE `PlaybackStateEntity_new` RENAME TO `PlaybackStateEntity`")

      // ===== 2. Drop ExternalSubtitleEntity table =====
      android.util.Log.d("Migration_1_2", "Removing ExternalSubtitleEntity table")
      db.execSQL("DROP TABLE IF EXISTS `ExternalSubtitleEntity`")

      // ===== 3. Create video_metadata_cache table =====
      android.util.Log.d("Migration_1_2", "Creating video_metadata_cache table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `video_metadata_cache` (
          `path` TEXT NOT NULL,
          `size` INTEGER NOT NULL,
          `dateModified` INTEGER NOT NULL,
          `duration` INTEGER NOT NULL,
          `width` INTEGER NOT NULL,
          `height` INTEGER NOT NULL,
          `fps` REAL NOT NULL,
          `lastScanned` INTEGER NOT NULL,
          PRIMARY KEY(`path`)
        )
        """.trimIndent(),
      )

      // ===== 4. Create network_connections table =====
      android.util.Log.d("Migration_1_2", "Creating network_connections table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `network_connections` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `protocol` TEXT NOT NULL,
          `host` TEXT NOT NULL,
          `port` INTEGER NOT NULL,
          `username` TEXT NOT NULL DEFAULT '',
          `password` TEXT NOT NULL DEFAULT '',
          `path` TEXT NOT NULL DEFAULT '/',
          `isAnonymous` INTEGER NOT NULL DEFAULT 0,
          `lastConnected` INTEGER NOT NULL DEFAULT 0,
          `autoConnect` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
      )

      // ===== 5. Create PlaylistEntity table =====
      android.util.Log.d("Migration_1_2", "Creating PlaylistEntity table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `PlaylistEntity` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `createdAt` INTEGER NOT NULL,
          `updatedAt` INTEGER NOT NULL
        )
        """.trimIndent(),
      )

      // ===== 6. Create PlaylistItemEntity table with foreign key =====
      android.util.Log.d("Migration_1_2", "Creating PlaylistItemEntity table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `PlaylistItemEntity` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `playlistId` INTEGER NOT NULL,
          `filePath` TEXT NOT NULL,
          `fileName` TEXT NOT NULL,
          `position` INTEGER NOT NULL,
          `addedAt` INTEGER NOT NULL,
          `lastPlayedAt` INTEGER NOT NULL DEFAULT 0,
          `playCount` INTEGER NOT NULL DEFAULT 0,
          `lastPosition` INTEGER NOT NULL DEFAULT 0,
          FOREIGN KEY(`playlistId`) REFERENCES `PlaylistEntity`(`id`) ON DELETE CASCADE
        )
        """.trimIndent(),
      )

      // Create index for PlaylistItemEntity foreign key
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_PlaylistItemEntity_playlistId` ON `PlaylistItemEntity` (`playlistId`)",
      )

      // ===== 7. Add new columns to RecentlyPlayedEntity =====
      android.util.Log.d("Migration_1_2", "Adding columns to RecentlyPlayedEntity")
      db.execSQL("ALTER TABLE `RecentlyPlayedEntity` ADD COLUMN `videoTitle` TEXT")
      db.execSQL("ALTER TABLE `RecentlyPlayedEntity` ADD COLUMN `duration` INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE `RecentlyPlayedEntity` ADD COLUMN `fileSize` INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE `RecentlyPlayedEntity` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE `RecentlyPlayedEntity` ADD COLUMN `height` INTEGER NOT NULL DEFAULT 0")
      db.execSQL("ALTER TABLE `RecentlyPlayedEntity` ADD COLUMN `playlistId` INTEGER")

      android.util.Log.d("Migration_1_2", "Migration completed successfully")

    } catch (e: Exception) {
      android.util.Log.e("Migration_1_2", "Migration failed", e)
      throw e
    }
  }
}

/**
 * Migration from version 2 to version 3
 *
 * Changes:
 * - Adds externalSubtitles column to PlaybackStateEntity to persist external subtitle URIs
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_2_3", "Starting migration from version 2 to version 3")

      // Add externalSubtitles column to PlaybackStateEntity
      db.execSQL("ALTER TABLE `PlaybackStateEntity` ADD COLUMN `externalSubtitles` TEXT NOT NULL DEFAULT ''")

      // Add subtitleCodec column to video_metadata_cache
      db.execSQL("ALTER TABLE video_metadata_cache ADD COLUMN subtitleCodec TEXT NOT NULL DEFAULT ''")


      android.util.Log.d("Migration_2_3", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_2_3", "Migration failed", e)
      throw e
    }
  }
}

/**
 * Migration from version 3 to version 4
 *
 * Changes:
 * - Adds m3uSourceUrl and isM3uPlaylist columns to PlaylistEntity for M3U/M3U8 playlist support
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_3_4", "Starting migration from version 3 to version 4")

      // Add M3U-related columns to PlaylistEntity
      db.execSQL(
        "ALTER TABLE `PlaylistEntity` ADD COLUMN `m3uSourceUrl` TEXT DEFAULT NULL"
      )
      db.execSQL(
        "ALTER TABLE `PlaylistEntity` ADD COLUMN `isM3uPlaylist` INTEGER NOT NULL DEFAULT 0"
      )

      android.util.Log.d("Migration_3_4", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_3_4", "Migration failed", e)
      throw e
    }
  }
}

/**
 * Migration from version 4 to version 5
 *
 * Changes:
 * - Adds secondarySid column to PlaybackStateEntity to persist secondary subtitle track
 *   This allows saving and restoring both primary and secondary subtitle selections
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_4_5", "Starting migration from version 4 to version 5")

      // Add secondarySid column to PlaybackStateEntity (-1 means disabled)
      db.execSQL(
        "ALTER TABLE `PlaybackStateEntity` ADD COLUMN `secondarySid` INTEGER NOT NULL DEFAULT -1"
      )

      android.util.Log.d("Migration_4_5", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_4_5", "Migration failed", e)
      throw e
    }
  }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_5_6", "Starting migration from version 5 to 6")
      
      // Get existing columns to check what needs to be added
      val cursor = db.query("PRAGMA table_info(video_metadata_cache)")
      val existingColumns = mutableSetOf<String>()
      while (cursor.moveToNext()) {
        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
        existingColumns.add(columnName)
      }
      cursor.close()
      
      // Add subtitleCodec if it doesn't exist yet (should have been added in MIGRATION_2_3 but might be missing)
      if (!existingColumns.contains("subtitleCodec")) {
        try {
          db.execSQL("ALTER TABLE `video_metadata_cache` ADD COLUMN `subtitleCodec` TEXT NOT NULL DEFAULT ''")
          android.util.Log.d("Migration_5_6", "Added subtitleCodec column")
        } catch (e: Exception) {
          android.util.Log.w("Migration_5_6", "Error adding subtitleCodec column, may already exist", e)
        }
      } else {
        android.util.Log.d("Migration_5_6", "subtitleCodec column already exists, skipping")
      }
      
      // Add hasEmbeddedSubtitles if it doesn't exist
      if (!existingColumns.contains("hasEmbeddedSubtitles")) {
        try {
          db.execSQL("ALTER TABLE `video_metadata_cache` ADD COLUMN `hasEmbeddedSubtitles` INTEGER NOT NULL DEFAULT 0")
          android.util.Log.d("Migration_5_6", "Added hasEmbeddedSubtitles column")
        } catch (e: Exception) {
          android.util.Log.w("Migration_5_6", "Error adding hasEmbeddedSubtitles column, may already exist", e)
        }
      } else {
        android.util.Log.d("Migration_5_6", "hasEmbeddedSubtitles column already exists, skipping")
      }
      
      android.util.Log.d("Migration_5_6", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_5_6", "Migration failed", e)
      throw e
    }
  }
}

/**
 * Migration from version 6 to version 7
 *
 * Changes:
 * - Adds useHttps column to network_connections table for explicit HTTPS control on WebDAV
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_6_7", "Starting migration from version 6 to 7")
      
      // Add useHttps column to network_connections table
      db.execSQL("ALTER TABLE `network_connections` ADD COLUMN `useHttps` INTEGER NOT NULL DEFAULT 0")
      
      android.util.Log.d("Migration_6_7", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_6_7", "Migration failed", e)
      throw e
    }
  }
}

/**
 * Migration from version 7 to version 8
 *
 * Changes:
 * - Adds hasBeenWatched column to PlaybackStateEntity to persist watched status
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_7_8", "Starting migration from version 7 to 8")
      
      // Add hasBeenWatched column to PlaybackStateEntity
      db.execSQL("ALTER TABLE `PlaybackStateEntity` ADD COLUMN `hasBeenWatched` INTEGER NOT NULL DEFAULT 0")
      
      android.util.Log.d("Migration_7_8", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_7_8", "Migration failed", e)
      throw e
    }
  }
}

/**
 * Migration from version 8 to version 9
 *
 * This is a repair migration to fix databases that have incorrect schema at version 6/7/8.
 * Some users have databases that claim to be version 6+ but still have the old v1 schema
 * with secondarySubDelay column instead of externalSubtitles.
 *
 * Changes:
 * - Ensures PlaybackStateEntity has the correct schema by recreating the table
 * - Preserves all existing data during the migration
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_8_9", "Starting migration from version 8 to 9 (schema repair)")
      
      // Check current schema to see if we need to repair
      val cursor = db.query("PRAGMA table_info(PlaybackStateEntity)")
      val existingColumns = mutableSetOf<String>()
      while (cursor.moveToNext()) {
        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
        existingColumns.add(columnName)
      }
      cursor.close()
      
      android.util.Log.d("Migration_8_9", "Existing columns: $existingColumns")
      
      // Check if we have the wrong schema (secondarySubDelay instead of externalSubtitles)
      val hasSecondarySubDelay = existingColumns.contains("secondarySubDelay")
      val hasExternalSubtitles = existingColumns.contains("externalSubtitles")
      val hasHasBeenWatched = existingColumns.contains("hasBeenWatched")
      
      if (hasSecondarySubDelay || !hasExternalSubtitles || !hasHasBeenWatched) {
        android.util.Log.d("Migration_8_9", "Schema mismatch detected, recreating table")
        
        // Create new table with correct schema
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `PlaybackStateEntity_new` (
            `mediaTitle` TEXT NOT NULL,
            `lastPosition` INTEGER NOT NULL,
            `playbackSpeed` REAL NOT NULL,
            `videoZoom` REAL NOT NULL DEFAULT 0.0,
            `sid` INTEGER NOT NULL,
            `secondarySid` INTEGER NOT NULL DEFAULT -1,
            `subDelay` INTEGER NOT NULL,
            `subSpeed` REAL NOT NULL,
            `aid` INTEGER NOT NULL,
            `audioDelay` INTEGER NOT NULL,
            `timeRemaining` INTEGER NOT NULL DEFAULT 0,
            `externalSubtitles` TEXT NOT NULL DEFAULT '',
            `hasBeenWatched` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`mediaTitle`)
          )
          """.trimIndent()
        )
        
        // Copy data from old table, handling missing columns
        if (hasExternalSubtitles && hasHasBeenWatched) {
          // Schema is correct, just copy everything
          db.execSQL(
            """
            INSERT INTO `PlaybackStateEntity_new` 
            SELECT * FROM `PlaybackStateEntity`
            """.trimIndent()
          )
        } else if (hasExternalSubtitles && !hasHasBeenWatched) {
          // Missing hasBeenWatched only
          db.execSQL(
            """
            INSERT INTO `PlaybackStateEntity_new` 
            (`mediaTitle`, `lastPosition`, `playbackSpeed`, `videoZoom`, `sid`, `secondarySid`, 
             `subDelay`, `subSpeed`, `aid`, `audioDelay`, `timeRemaining`, `externalSubtitles`, `hasBeenWatched`)
            SELECT `mediaTitle`, `lastPosition`, `playbackSpeed`, `videoZoom`, `sid`, `secondarySid`, 
                   `subDelay`, `subSpeed`, `aid`, `audioDelay`, `timeRemaining`, `externalSubtitles`, 0
            FROM `PlaybackStateEntity`
            """.trimIndent()
          )
        } else {
          // Has old schema with secondarySubDelay, need to map columns
          db.execSQL(
            """
            INSERT INTO `PlaybackStateEntity_new` 
            (`mediaTitle`, `lastPosition`, `playbackSpeed`, `videoZoom`, `sid`, `secondarySid`, 
             `subDelay`, `subSpeed`, `aid`, `audioDelay`, `timeRemaining`, `externalSubtitles`, `hasBeenWatched`)
            SELECT `mediaTitle`, `lastPosition`, `playbackSpeed`, 
                   COALESCE(`videoZoom`, 0.0), 
                   `sid`, 
                   COALESCE(`secondarySid`, -1), 
                   `subDelay`, `subSpeed`, `aid`, `audioDelay`, 
                   COALESCE(`timeRemaining`, 0), 
                   '', 
                   0
            FROM `PlaybackStateEntity`
            """.trimIndent()
          )
        }
        
        // Drop old table and rename new one
        db.execSQL("DROP TABLE `PlaybackStateEntity`")
        db.execSQL("ALTER TABLE `PlaybackStateEntity_new` RENAME TO `PlaybackStateEntity`")
        
        android.util.Log.d("Migration_8_9", "Table recreated successfully")
      } else {
        android.util.Log.d("Migration_8_9", "Schema is correct, no repair needed")
      }
      
      android.util.Log.d("Migration_8_9", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_8_9", "Migration failed", e)
      throw e
    }
  }
}


/**
 * Migration from version 9 to version 10
 *
 * 二开版加 Emby 服务器支持：创建 emby_servers 表存储 Emby 服务器配置。
 * 原 MIGRATION_8_9 是 schema repair，不动它。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_9_10", "Creating emby_servers table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `emby_servers` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `host` TEXT NOT NULL,
          `port` INTEGER NOT NULL,
          `useHttps` INTEGER NOT NULL DEFAULT 0,
          `username` TEXT NOT NULL,
          `password` TEXT NOT NULL,
          `userId` TEXT NOT NULL DEFAULT '',
          `apiToken` TEXT NOT NULL DEFAULT '',
          `serverName` TEXT NOT NULL DEFAULT '',
          `version` TEXT NOT NULL DEFAULT '',
          `createdAt` INTEGER NOT NULL DEFAULT 0,
          `lastConnected` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent()
      )
      android.util.Log.d("Migration_9_10", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_9_10", "Migration failed", e)
      throw e
    }
  }
}


/**
 * Migration from version 10 to version 11
 *
 * ## 为什么这是一个「空迁移」，却必须存在
 *
 * v11 那次发版**只改了别的**（不是数据库），schema 与 v10 完全一致
 * （对比 `app/schemas/…/10.json` 与 `11.json`：表、列、索引逐条一致）。
 * 但 Room 的迁移是靠 `addMigrations` 里注册的相邻版本对来串路径的，
 * **不会因为 schema 没变就自动跳过** —— 缺了 10→11 这一段，
 * v10 的用户升到 v12 时找不到完整路径，会落到
 * `fallbackToDestructiveMigration(true)`：**整库删表重建**，
 * Emby 服务器配置、播放列表、播放进度、最近播放全部丢失。
 *
 * 所以哪怕 migrate 体是空的，也必须注册，Room 会在事务里自己把
 * `user_version` 递到 11，再由 [MIGRATION_11_12] 建 search_history。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // 故意空：schema 无变化。留一条日志是为了让「升级时走过这一步」在 logcat 里可查 ——
    // 用户报「数据没了」时，第一件事就是确认迁移链是否完整。
    android.util.Log.d("Migration_10_11", "Schema unchanged between v10 and v11, nothing to do")
  }
}


/**
 * Migration from version 11 to version 12
 *
 * 新增 search_history 表：首页全库搜索 + 媒体库搜索共用的搜索历史。
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_11_12", "Creating search_history table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `search_history` (
          `keyword` TEXT NOT NULL,
          `lastUsedAt` INTEGER NOT NULL,
          `useCount` INTEGER NOT NULL,
          PRIMARY KEY(`keyword`)
        )
        """.trimIndent()
      )
      android.util.Log.d("Migration_11_12", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_11_12", "Migration failed", e)
      throw e
    }
  }
}


/**
 * 12 → 13（清单 4.2）：下载相关两张表。
 *
 * - `emby_download_tasks`：下载任务从 SharedPreferences 的 JSON 迁到 Room。
 *   ⚠️ **旧数据不迁移**：prefs 里那份 JSON 在迁移脚本里没法优雅地读回来
 *   （要自己解析 + 自己映射，且 prefs 读不到就当空），而任务本身的价值是
 *   「可以断点续传」——迁移过来的任务连 targetUri 都可能过期。
 *   用户重下一次的成本远低于「迁一堆可能已经失效的记录」的风险。
 *   已下载的文件本身不会因为这次迁移消失（文件在 Download 目录里，跟数据库无关）。
 * - `emby_downloaded_media`：已落盘内容的离线元数据，首次建表。
 *
 * 列名与 [app.marlboroadvance.mpvex.database.entities.EmbyDownloadEntity] /
 * [app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity] 严格一致 ——
 * 少一列或多一列 Room 会在运行时抛 IllegalStateException（schema 校验失败）。
 * ⚠️ 索引名是 Room 自动生成的 `index_emby_download_tasks_serverId` 这类，
 * 建表语句里必须照抄，改名会导致校验失败。
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_12_13", "Creating emby_download_tasks table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `emby_download_tasks` (
          `itemId` TEXT NOT NULL,
          `serverId` INTEGER NOT NULL,
          `title` TEXT NOT NULL,
          `fileName` TEXT NOT NULL,
          `url` TEXT NOT NULL,
          `totalBytes` INTEGER NOT NULL,
          `downloadedBytes` INTEGER NOT NULL,
          `status` TEXT NOT NULL,
          `message` TEXT,
          `targetUri` TEXT,
          `locationLabel` TEXT,
          `createdAt` INTEGER NOT NULL,
          PRIMARY KEY(`itemId`)
        )
        """.trimIndent()
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_emby_download_tasks_serverId` " +
          "ON `emby_download_tasks` (`serverId`)"
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_emby_download_tasks_status` " +
          "ON `emby_download_tasks` (`status`)"
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_emby_download_tasks_createdAt` " +
          "ON `emby_download_tasks` (`createdAt`)"
      )

      android.util.Log.d("Migration_12_13", "Creating emby_downloaded_media table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `emby_downloaded_media` (
          `itemId` TEXT NOT NULL,
          `serverId` INTEGER NOT NULL,
          `title` TEXT NOT NULL,
          `type` TEXT,
          `seriesName` TEXT,
          `seasonNumber` INTEGER,
          `episodeNumber` INTEGER,
          `overview` TEXT,
          `runTimeTicks` INTEGER,
          `productionYear` INTEGER,
          `communityRating` REAL,
          `officialRating` TEXT,
          `imageTag` TEXT,
          `fileName` TEXT,
          `localUri` TEXT,
          `fileSize` INTEGER NOT NULL,
          `completedAt` INTEGER NOT NULL,
          PRIMARY KEY(`itemId`)
        )
        """.trimIndent()
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_emby_downloaded_media_serverId` " +
          "ON `emby_downloaded_media` (`serverId`)"
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_emby_downloaded_media_seriesName` " +
          "ON `emby_downloaded_media` (`seriesName`)"
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_emby_downloaded_media_completedAt` " +
          "ON `emby_downloaded_media` (`completedAt`)"
      )

      android.util.Log.d("Migration_12_13", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_12_13", "Migration failed", e)
      throw e
    }
  }
}

/**
 * v13 → v14：新增「标记为不喜欢」表。
 *
 * 纯新增表，不动任何已有表 ⇒ 老用户的服务器配置 / 播放列表 / 播放进度都不受影响。
 * 语句与 [app.marlboroadvance.mpvex.database.entities.DislikedMediaEntity] 的声明**必须逐字对应**
 * （列名、类型、复合主键），否则 Room 启动时的 schema 校验会直接抛异常。
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_13_14", "Creating disliked_media table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `disliked_media` (
          `serverId` INTEGER NOT NULL,
          `itemId` TEXT NOT NULL,
          `title` TEXT NOT NULL,
          `reason` TEXT NOT NULL,
          `markedAt` INTEGER NOT NULL,
          `imageUrl` TEXT,
          `active` INTEGER NOT NULL DEFAULT 1,
          PRIMARY KEY(`serverId`, `itemId`)
        )
        """.trimIndent()
      )
      // 管理页按标记时间倒序展示，这个索引直接服务该查询
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_disliked_media_markedAt` " +
          "ON `disliked_media` (`markedAt`)"
      )

      android.util.Log.d("Migration_13_14", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_13_14", "Migration failed", e)
      throw e
    }
  }
}


val MIGRATION_14_15 = object : Migration(14, 15) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_14_15", "Creating network_favorites table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `network_favorites` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `connectionId` INTEGER NOT NULL,
          `path` TEXT NOT NULL,
          `name` TEXT NOT NULL,
          `connectionName` TEXT NOT NULL,
          `addedAt` INTEGER NOT NULL
        )
        """.trimIndent()
      )
      // 唯一索引：(connectionId, path) 重复收藏由数据库挡掉
      db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_network_favorites_connectionId_path` " +
          "ON `network_favorites` (`connectionId`, `path`)"
      )
      // 快捷入口按收藏时间倒序列
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_network_favorites_addedAt` " +
          "ON `network_favorites` (`addedAt`)"
      )

      android.util.Log.d("Migration_14_15", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_14_15", "Migration failed", e)
      throw e
    }
  }
}


/**
 * v15 → v16：新增「网络文件夹封面缓存」表。
 *
 * 纯新增表，不动任何已有表 ⇒ 老用户的服务器配置 / 播放列表 / 播放进度都不受影响。
 * 语句与 [app.marlboroadvance.mpvex.domain.network.FolderCoverCacheEntity] 的声明
 * **必须逐字对应**（列名、类型、复合主键、索引名），否则 Room 启动时的 schema
 * 校验会直接抛异常。
 *
 * 这张表的意义：网络浏览的文件夹卡用「文件夹里第一张图片」当封面，列一次子目录
 * 才知道封面是谁。只放内存的话每进一次父目录都要重拉一遍封面请求；落盘之后
 * 第二次进来直接读库、零请求（见 Repository 的 L1→L2 两级缓存）。
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_15_16", "Creating folder_cover_cache table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `folder_cover_cache` (
          `connectionId` INTEGER NOT NULL,
          `path` TEXT NOT NULL,
          `coverPath` TEXT,
          `updatedAt` INTEGER NOT NULL,
          PRIMARY KEY(`connectionId`, `path`)
        )
        """.trimIndent()
      )
      // TTL 清理（删 30 天前的旧行）按 updatedAt 扫全表，索引直接服务那条 DELETE
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_folder_cover_cache_updatedAt` " +
          "ON `folder_cover_cache` (`updatedAt`)"
      )

      android.util.Log.d("Migration_15_16", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_15_16", "Migration failed", e)
      throw e
    }
  }
}


/**
 * v16 → v17：给 `network_connections` 补一个「置顶」位。
 *
 * 只是加一列、带默认值 ⇒ 老用户的连接一条不丢、也不用重新添加（默认不置顶）。
 * ⚠️ 列定义必须与 [app.marlboroadvance.mpvex.domain.network.NetworkConnection.pinned]
 * 完全一致（`INTEGER` + `NOT NULL` + `DEFAULT 0`），否则 Room 启动时的 schema
 * 校验会直接抛异常。Room 把 Kotlin `Boolean` 存成 INTEGER 0/1。
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_16_17", "Adding pinned column to network_connections")
      db.execSQL(
        "ALTER TABLE `network_connections` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"
      )

      android.util.Log.d("Migration_16_17", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_16_17", "Migration failed", e)
      throw e
    }
  }
}


/**
 * v17 → v18：新建「聚合库」表 `aggregate_libraries`（清单：本地聚合多个同类型媒体库）。
 *
 * 纯新增表、不动任何既有列 ⇒ 老用户的服务器配置 / 播放进度 / 收藏原样保留。
 * ⚠️ 列定义必须与 [app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity]
 * 逐字段一致（Room 启动时会校验 schema，差一个默认值都会抛异常）。
 * Room 把 Kotlin 可空引用存成可空列，所以 `collectionType` 一列不带 NOT NULL。
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_17_18", "Creating aggregate_libraries table")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `aggregate_libraries` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `serverId` INTEGER NOT NULL,
          `name` TEXT NOT NULL,
          `memberLibraryIds` TEXT NOT NULL,
          `collectionType` TEXT,
          `sortIndex` INTEGER NOT NULL,
          `createdAt` INTEGER NOT NULL
        )
        """.trimIndent(),
      )
      db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_aggregate_libraries_serverId` " +
          "ON `aggregate_libraries` (`serverId`)"
      )

      android.util.Log.d("Migration_17_18", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_17_18", "Migration failed", e)
      throw e
    }
  }
}


/**
 * v18 → v19：搜索历史按「服务器 + 用户」隔离（主键 (serverId, keyword)）。
 *
 * ## 旧数据去哪了（兼容策略）
 *
 * v18 及以前的 search_history 没有归属列，一份历史全库混用。升级时把这些
 * 旧行整体挂到**默认服务器**名下：
 *
 * - 默认服务器 = `emby_servers` 里**已登录**（有 apiToken + userId）且
 *   **最近连接过**（lastConnected 最新）的那一条 —— 用户最常用的就是它，
 *   历史挂过去之后老用户升级完无感。
 * - 一台已登录服务器都没有（纯本地播放用户）时挂 serverId = 0。
 *   这一段没有任何界面会去读（搜索只在有服务器的 Emby 页面发生），
 *   但**不删**：万一用户以后又加了服务器，旧词不会和任何账号串。
 *
 * ## 为什么必须重建表
 *
 * SQLite 改不了主键，只能「建新表 → 搬数据 → 删旧表 → 改名」四步。
 * 新表结构与 [app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity]
 * **逐字对应**（列名 / 类型 / 复合主键），否则 Room 启动时的 schema 校验会抛异常。
 *
 * 一次 `record` 只写当前服务器；trim 按服务器各自封顶 ⇒ 总量有界
 * （服务器数 × 20），重建时的数据量不是问题。
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
  override fun migrate(db: SupportSQLiteDatabase) {
    try {
      android.util.Log.d("Migration_18_19", "Isolating search_history by serverId")

      // 1) 定默认服务器：已登录 + 最近连接。取不到就 0（见 KDoc 的兼容策略）
      var defaultServerId = 0L
      val cursor = db.query(
        "SELECT id FROM emby_servers " +
          "WHERE apiToken <> '' AND userId <> '' " +
          "ORDER BY lastConnected DESC, id DESC LIMIT 1",
      )
      if (cursor.moveToFirst()) {
        defaultServerId = cursor.getLong(0)
      }
      cursor.close()
      android.util.Log.d("Migration_18_19", "Default server for legacy rows: $defaultServerId")

      // 2) 建新表（结构与 SearchHistoryEntity 一致）
      db.execSQL("DROP TABLE IF EXISTS `search_history_new`")
      db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `search_history_new` (
          `serverId` INTEGER NOT NULL,
          `keyword` TEXT NOT NULL,
          `lastUsedAt` INTEGER NOT NULL,
          `useCount` INTEGER NOT NULL,
          PRIMARY KEY(`serverId`, `keyword`)
        )
        """.trimIndent(),
      )

      // 3) 搬旧数据：全部挂到默认服务器名下（参数化绑定，避免注入/转义问题）
      db.execSQL(
        "INSERT INTO `search_history_new` (`serverId`, `keyword`, `lastUsedAt`, `useCount`) " +
          "SELECT ?, `keyword`, `lastUsedAt`, `useCount` FROM `search_history`",
        arrayOf<Any?>(defaultServerId),
      )

      // 4) 旧表换新表
      db.execSQL("DROP TABLE `search_history`")
      db.execSQL("ALTER TABLE `search_history_new` RENAME TO `search_history`")

      android.util.Log.d("Migration_18_19", "Migration completed successfully")
    } catch (e: Exception) {
      android.util.Log.e("Migration_18_19", "Migration failed", e)
      throw e
    }
  }
}


val DatabaseModule =
  module {
    single<Json> {
      Json {
        isLenient = true
        ignoreUnknownKeys = true
      }
    }

    single<MpvExDatabase> {
      val context = androidContext()
      Room
        .databaseBuilder(context, MpvExDatabase::class.java, "mpvex.db")
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        // ⚠️ 迁移链必须**逐段连续**（1→2→…→19），少一段就会退化成
        // fallbackToDestructiveMigration = 整库删表重建（服务器配置 / 播放列表 / 播放进度全丢）。
        // 任何一次改 @Database version 都要在这里补一对新的 Migration，别指望 Room 兜底。
        .addMigrations(
          MIGRATION_1_2,
          MIGRATION_2_3,
          MIGRATION_3_4,
          MIGRATION_4_5,
          MIGRATION_5_6,
          MIGRATION_6_7,
          MIGRATION_7_8,
          MIGRATION_8_9,
          MIGRATION_9_10,
          MIGRATION_10_11,
          MIGRATION_11_12,
          MIGRATION_12_13,
          MIGRATION_13_14,
          MIGRATION_14_15,
          MIGRATION_15_16,
          MIGRATION_16_17,
          MIGRATION_17_18,
          MIGRATION_18_19,
        )
        .fallbackToDestructiveMigration(true) // 最后兜底：迁移链有洞时至少不崩 App
        .build()
    }

    // Emby 服务器配置 DAO
    single { get<MpvExDatabase>().embyServerDao() }

    // 收藏目录 DAO（networkConnectionDao 的注册在下面 NetworkRepository 旁）
    single { get<MpvExDatabase>().networkFavoriteDao() }

    // 搜索历史 DAO + 仓库（首页全库搜索与媒体库搜索共用）
    single { get<MpvExDatabase>().searchHistoryDao() }

    single {
      app.marlboroadvance.mpvex.database.repository.SearchHistoryRepository(
        dao = get(),
      )
    }

    // Emby 服务器配置仓库
    single {
      app.marlboroadvance.mpvex.database.repository.EmbyServerRepository(
        dao = get(),
      )
    }

    // Emby 业务仓库（封装 API 调用）
    single {
      app.marlboroadvance.mpvex.domain.emby.EmbyRepository(
        serverRepo = get(),
        context = androidContext(),
      )
    }

    // 显示偏好服务端同步（清单 #16）。单例即可：它本身无状态，
    // 状态全在 BrowserPreferences 里。
    single {
      app.marlboroadvance.mpvex.ui.browser.emby.displayprefs.EmbyDisplayPrefsSync(
        repository = get(),
        preferences = get(),
      )
    }

    // GSY 内核的章节数据源（清单 #19）。**单例是必须的**，不是随手选的：
    // 它内部持有「itemId → 章节」的缓存，做成多实例的话每进一次播放页就是一个新缓存，
    // 章节会被反复拉取。缓存只在切集时按条目清（见 GsyChapterRepository.invalidate），
    // 所以单个单例活到进程结束是安全的。
    single {
      app.marlboroadvance.mpvex.ui.player.gsy.GsyChapterRepository(
        repository = get(),
      )
    }

    // 清单 4.2：网络连通性监听。**单例**是必须的 ——
    // registerDefaultNetworkCallback 是进程级的，一个 App 注册多个 callback 会互相覆盖，
    // 也会让「注销」这件事变得不明确（注销一次就把所有 callback 都干掉了）。
    // 所以整个 App 共用这一个实例，谁需要谁 collect。
    single { app.marlboroadvance.mpvex.utils.network.NetworkMonitor(androidContext()) }

    // 清单 4.2：下载任务表 + 已下载内容表
    single { get<MpvExDatabase>().embyDownloadDao() }
    single { get<MpvExDatabase>().downloadedMediaDao() }

    // 「标记为不喜欢」：**必须单例** —— 它内部持有一份全量内存快照，
    // 做成多实例的话播放器标记完，详情页读的还是另一个空快照，
    // 表现为"标记了立刻又变回未标记"。单例是这套"快照即真相"设计的前提。
    single { get<MpvExDatabase>().dislikeDao() }
    single { app.marlboroadvance.mpvex.domain.dislike.DislikeRepository(dao = get()) }

    // 「聚合库」：定义只有一份（管理页 / 首页 / 聚合页共用），DAO 与仓库都单例
    single { get<MpvExDatabase>().aggregateLibraryDao() }
    single {
      app.marlboroadvance.mpvex.database.repository.AggregateLibraryRepository(
        dao = get(),
      )
    }

    // 清单 4.2：离线内容仓库（纯 Room 读，断网可用）
    single { app.marlboroadvance.mpvex.domain.emby.DownloadedMediaRepository(dao = get()) }

    // Emby 离线下载管理器（支持暂停/续传，落盘到公共 Download/CineIsle）
    single {
      app.marlboroadvance.mpvex.domain.emby.EmbyDownloadManager(
        context = androidContext(),
        // 下载完成要按需回清服务器播放进度（可开关），需要按 serverId 找回服务器配置
        serverRepo = get(),
        preferenceStore = get(),
        // 任务持久化走 Room（原来是 SharedPreferences 的 JSON，见 MIGRATION_12_13）
        downloadDao = get(),
        // 已落盘内容的离线元数据；离线内容页的数据源
        downloadedMediaDao = get(),
      )
    }

    singleOf(::PlaybackStateRepositoryImpl).bind(PlaybackStateRepository::class)

    single<RecentlyPlayedRepository> {
      RecentlyPlayedRepositoryImpl(get<MpvExDatabase>().recentlyPlayedDao())
    }

    single { ThumbnailRepository(androidContext()) }

    single {
      app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository(
        context = androidContext(),
        dao = get<MpvExDatabase>().videoMetadataDao(),
      )
    }

    // MediaFileRepository is a singleton object - no DI needed

    single {
      get<MpvExDatabase>().networkConnectionDao()
    }

    single {
      app.marlboroadvance.mpvex.repository.NetworkRepository(
        dao = get(),
        favoriteDao = get(),
        coverCacheDao = get<MpvExDatabase>().folderCoverCacheDao(),
      )
    }

    single {
      PlaylistRepository(
        playlistDao = get<MpvExDatabase>().playlistDao(),
      )
    }
  }
