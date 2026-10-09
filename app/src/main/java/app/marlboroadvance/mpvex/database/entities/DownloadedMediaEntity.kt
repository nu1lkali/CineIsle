package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个**已下载完成**的媒体条目的离线元数据（清单 4.2）。
 *
 * ## 为什么单独一张表，而不是直接用 [EmbyDownloadEntity]
 * 两者生命周期不同：
 * - [EmbyDownloadEntity] 是**任务**，用户可以在下载管理页删任务、清记录，删完就没了
 * - 本表是**已落盘的内容**，是「离线可浏览的东西」的权威清单，
 *   跟任务列表解耦 —— 用户清了下载记录不该让已下好的片子从离线列表里消失。
 *
 * ## 为什么存「最小字段子集」而不是整个 EmbyItem
 * [app.marlboroadvance.mpvex.domain.emby.EmbyItem] 是约 40 个字段的宽类，
 * 而且 `Seasons` / `Episodes` 是**递归嵌套**的 `List<EmbyItem>`。
 * 整个 encodeToString 存进来的代价：
 * - 单集就带一整份季/集列表（Emby 对 Episode 通常也会回 Children），一条记录能到几百 KB
 * - EmbyItem 加字段 → 旧记录的 JSON 反序列化要靠 `ignoreUnknownKeys` 兜，
 *   而 `ignoreUnknownKeys` 救不了「类型变了」的情况（比如某字段从对象改成字符串）
 * - 离线页只需要标题/海报/时长/季集号这些展示字段，其余全是死重量
 *
 * 所以这里只存离线浏览与播放**用得到**的字段。字段不够时正确的做法是
 * 「联网后重新拉一次详情补上」，而不是把整个 EmbyItem 塞进数据库。
 */
@Entity(
  tableName = "emby_downloaded_media",
  indices = [Index("serverId"), Index("seriesName"), Index("completedAt")],
)
data class DownloadedMediaEntity(
  /** Emby 的 ItemId，主键。同时是下载任务的主键，一一对应 */
  @PrimaryKey val itemId: String,
  /** 所属服务器 id（用于换服务器时隔离） */
  val serverId: Long,
  /** 片名 / 集名 */
  val title: String,
  /** Emby 的 Type（Movie / Series / Episode / BoxSet …），离线页分组与图标用 */
  val type: String? = null,
  /** 剧集所属剧名；电影为 null */
  val seriesName: String? = null,
  /** 季号（Episode 才有） */
  val seasonNumber: Int? = null,
  /** 集号（Episode 才有） */
  val episodeNumber: Int? = null,
  /** 简介；离线页详情用 */
  val overview: String? = null,
  /** 时长（Emby 的 100ns ticks，播放进度按它换算） */
  val runTimeTicks: Long? = null,
  val productionYear: Int? = null,
  val communityRating: Double? = null,
  /** 分级（如「PG-13」），卡片角标用 */
  val officialRating: String? = null,
  /**
   * 海报图的 tag（`ImageTags.Primary`）。
   *
   * 只存 tag 不存 URL：URL 得拼服务器地址，而换服务器/改 host 后就失效了；
   * 真正的图片地址在播放/展示时用当前服务器重新拼。
   */
  val imageTag: String? = null,
  /** 落盘文件名（含扩展名） */
  val fileName: String? = null,
  /** 本地可播放的 uri：content://（MediaStore）或 file:// */
  val localUri: String? = null,
  /** 文件大小（字节）；-1/0 视为未知 */
  val fileSize: Long = -1L,
  /** 下载完成时刻；离线列表按它倒序（「最近下的」排在最前） */
  val completedAt: Long = 0L,
)
