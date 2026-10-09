package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条 Emby 下载任务的**持久化形态**（清单 4.2）。
 *
 * ## 为什么不用 SharedPreferences 存（原来是 `emby_downloads` prefs + JSON）
 * 1. **没法查询**：要判断「哪些已完成」「某个 server 的任务有哪些」只能把整份 JSON
 *    反序列化出来在内存里过滤。离线内容页要按 server 过滤，冷启动就得反序列化两次。
 * 2. **全量覆盖写**：原来每次状态变化都 `putString(整份 JSON)`。
 *    几十个任务时每 5 秒重写一次几十 KB 的字符串，写放大明显。
 * 3. **跟项目其余状态不在一个事务边界里**：播放进度、播放列表都在 Room，
 *    下载任务单独在 prefs，两边没有一致性保证。
 *
 * ## 与 [app.marlboroadvance.mpvex.domain.emby.EmbyDownloadTask] 的关系
 * 这个类**只存需要跨进程重启的字段**。刻意不存的两类：
 * - 运行期速度（`speedBps`）：只在内存里算，重启后没有意义
 * - 展示用快照（`title` 之外的元信息）：那些在 [DownloadedMediaEntity] 里
 *
 * 状态用 [status]（String，枚举名）而不是 enum 类型本身 ——
 * 与项目里 [app.marlboroadvance.mpvex.database.converters.NetworkProtocolConverter]
 * 的做法一致（存 `name`，读时 `valueOf`），迁移脚本里也能直接写字符串。
 */
@Entity(
  tableName = "emby_download_tasks",
  indices = [Index("serverId"), Index("status"), Index("createdAt")],
)
data class EmbyDownloadEntity(
  /** Emby 的 ItemId，同时作为任务主键（一个条目只会有一条任务） */
  @PrimaryKey val itemId: String,
  /** 所属服务器 id；离线内容页按它反查服务器档案（纯 Room 读，不需要网络） */
  val serverId: Long,
  /** 展示用标题（片名或剧集名） */
  val title: String,
  /** 落盘文件名（含扩展名） */
  val fileName: String,
  /** 下载地址（已带 api_key，可直接用 OkHttp 拉） */
  val url: String,
  /** 文件总大小；-1 表示未知（响应头没给） */
  val totalBytes: Long = -1L,
  /** 已下载字节数（断点续传就靠它算 Range 起点） */
  val downloadedBytes: Long = 0L,
  /** [app.marlboroadvance.mpvex.domain.emby.EmbyDownloadStatus] 的枚举名 */
  val status: String = "QUEUED",
  /** 附加说明（失败原因 / 中断提示），成功时为 null */
  val message: String? = null,
  /** 落盘位置：content:// uri（MediaStore）或 file:// uri（旧系统回落） */
  val targetUri: String? = null,
  /** 给人看的位置文本，如 Download/CineIsle/xxx.mkv */
  val locationLabel: String? = null,
  val createdAt: Long = 0L,
)
