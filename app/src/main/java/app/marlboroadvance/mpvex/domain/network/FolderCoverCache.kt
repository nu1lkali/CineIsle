package app.marlboroadvance.mpvex.domain.network

import androidx.room.Entity
import androidx.room.Index

/**
 * 网络浏览**文件夹封面缓存**（一个文件夹解析出的封面引用）。
 *
 * ## 为什么需要它
 *
 * 网格里的文件夹卡要显示封面，这得先列一次该子目录才知道用什么。只放在内存里的话：
 * **每进一次父目录、每重建一次这个页面，封面都要重拉一遍** —— SMB 列目录不是零成本，
 * 大库里几十个文件夹就是几十次多余请求。这张表把「解析过了，封面是什么」落盘，
 * 第二次进来直接读库，零请求。
 *
 * ## 关键语义
 *
 * - [coverPath] = **null 是一个合法的缓存值**：这个文件夹解析过了、范围内没有可用封面。
 *   没有这层「负缓存」的话，没有封面的文件夹（比如纯电影目录）每次进来都会被
 *   再列一次，而那恰恰是最常见的情况。
 * - [coverPath] **不是单纯的文件路径**：它是「封面引用」，两种形态靠 scheme 区分 ——
 *   `http(s)://...` 是视频的服务端海报帧（AList `fs/list` 的 `thumb`，绝对 URL），
 *   其余是图片在挂载点内的相对 path。判据统一走
 *   [isRemoteCoverRef]，别在别处另写一套 `startsWith`。
 * - 主键 `(connectionId, path)`：同一连接下同一路径只有一行， upsert 天然去重。
 * - **列目录失败 / 解析不完整不写缓存**（写缓存的责任在 Repository 的成功分支）：
 *   失败是暂时的（网络抖动 / 会话过期），下次进来应该重试，而不是把失败记成
 *   「没有封面」缓存 12 小时。
 * - [updatedAt] 是**解析时刻**，两个用途：TTL 判据（12 小时）与「缓存是否早于目录 mtime」
 * 判据（父目录列表里这个文件夹的修改时间比它新 ⇒ 目录变过，封面要重算）。共享目录的内容会变（往里加了照片 / 视频
 *   有了新海报），超过 TTL 的行下次会重新解析，保证封面不会永远停在旧图上。
 */
@Entity(
  tableName = "folder_cover_cache",
  primaryKeys = ["connectionId", "path"],
  indices = [
    // TTL 清理按 updatedAt 扫全表，这个索引直接服务那条 DELETE
    Index(value = ["updatedAt"]),
  ],
)
data class FolderCoverCacheEntity(
  val connectionId: Long,
  /** 文件夹在该连接内的路径（与 NetworkBrowserScreen.currentPath 同一口径） */
  val path: String,
  /**
   * 封面引用：`http(s)://...` = 视频服务端海报帧；其余 = 图片的相对 path；
   * null = 解析过了，这个文件夹范围内没有可用封面。
   *
   * ⚠️ 列名沿用 `coverPath`（改列名要加迁移），语义已在 KDoc 里说明 —— 见
   * [isRemoteCoverRef] 的分流判据。
   */
  val coverPath: String?,
  val updatedAt: Long,
)
