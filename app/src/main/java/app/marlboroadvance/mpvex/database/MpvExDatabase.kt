package app.marlboroadvance.mpvex.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.marlboroadvance.mpvex.database.converters.NetworkProtocolConverter
import app.marlboroadvance.mpvex.database.dao.AggregateLibraryDao
import app.marlboroadvance.mpvex.database.dao.DislikeDao
import app.marlboroadvance.mpvex.database.dao.DownloadedMediaDao
import app.marlboroadvance.mpvex.database.dao.EmbyDownloadDao
import app.marlboroadvance.mpvex.database.dao.EmbyServerDao
import app.marlboroadvance.mpvex.database.dao.FolderCoverCacheDao
import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.database.dao.NetworkFavoriteDao
import app.marlboroadvance.mpvex.database.dao.PlaybackStateDao
import app.marlboroadvance.mpvex.database.dao.PlaylistDao
import app.marlboroadvance.mpvex.database.dao.RecentlyPlayedDao
import app.marlboroadvance.mpvex.database.dao.SearchHistoryDao
import app.marlboroadvance.mpvex.database.dao.VideoMetadataDao
import app.marlboroadvance.mpvex.database.entities.AggregateLibraryEntity
import app.marlboroadvance.mpvex.database.entities.DislikedMediaEntity
import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import app.marlboroadvance.mpvex.database.entities.EmbyDownloadEntity
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.entities.PlaylistEntity
import app.marlboroadvance.mpvex.database.entities.PlaylistItemEntity
import app.marlboroadvance.mpvex.database.entities.RecentlyPlayedEntity
import app.marlboroadvance.mpvex.database.entities.SearchHistoryEntity
import app.marlboroadvance.mpvex.database.entities.VideoMetadataEntity
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.network.FolderCoverCacheEntity
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFavorite

@Database(
  entities = [
    PlaybackStateEntity::class,
    RecentlyPlayedEntity::class,
    VideoMetadataEntity::class,
    NetworkConnection::class,
    // 「网络」里收藏的目录（快捷入口）
    NetworkFavorite::class,
    // 「网络」文件夹封面缓存（文件夹内第一张图片，L2 落盘）
    FolderCoverCacheEntity::class,
    PlaylistEntity::class,
    PlaylistItemEntity::class,
    EmbyServer::class,
    SearchHistoryEntity::class,
    // 清单 4.2：下载任务从 SharedPreferences 的 JSON 迁到 Room；
    // 已下载内容单独一张表，与任务表解耦（清任务记录不该让已下的片子从离线列表消失）
    EmbyDownloadEntity::class,
    DownloadedMediaEntity::class,
    // 「标记为不喜欢」。主键 (serverId, itemId) 由数据库保证去重，
    // 见 DislikedMediaEntity 的 KDoc
    DislikedMediaEntity::class,
    // 「聚合库」：把同服务器多个同类型媒体库聚合成一个本地视图（不写服务端）。
    // 只一张小表：名字 + 逗号分隔的成员库 Id + 排序位，见 AggregateLibraryEntity 的 KDoc
    AggregateLibraryEntity::class,
  ],
  version = 18,
  exportSchema = true,
)
@TypeConverters(NetworkProtocolConverter::class)
abstract class MpvExDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao

  abstract fun videoMetadataDao(): VideoMetadataDao

  abstract fun networkConnectionDao(): NetworkConnectionDao

  abstract fun networkFavoriteDao(): NetworkFavoriteDao

  abstract fun folderCoverCacheDao(): FolderCoverCacheDao

  abstract fun playlistDao(): PlaylistDao

  abstract fun embyServerDao(): EmbyServerDao

  abstract fun searchHistoryDao(): SearchHistoryDao

  abstract fun embyDownloadDao(): EmbyDownloadDao

  abstract fun downloadedMediaDao(): DownloadedMediaDao

  abstract fun dislikeDao(): DislikeDao

  abstract fun aggregateLibraryDao(): AggregateLibraryDao
}
