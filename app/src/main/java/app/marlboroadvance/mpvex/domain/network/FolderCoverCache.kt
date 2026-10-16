package app.marlboroadvance.mpvex.domain.network

import androidx.room.Entity
import androidx.room.Index

/**
 * 网络浏览**文件夹封面缓存**（「文件夹里第一张图片」的 path）。
 *
 * ## 为什么需要它
 *
 * 网格里的文件夹卡要用文件夹内第一张图片当封面，这要列一次该子目录才知道。
 * 只放在内存里的话：**每进一次父目录、每重建一次这个页面，封面都要重拉一遍** ——
 * SMB 列目录不是零成本，大库里几十个文件夹就是几十次多余请求。
 * 这张表把「列过了，封面是谁」落盘，第二次进来直接读库，零请求。
 *
 * ## 关键语义
 *
 * - [coverPath] = **null 是一个合法的缓存值**：这个文件夹列过了、里面没有图片。
 *   没有这层「负缓存」的话，没有图片的文件夹（比如纯电影目录）每次进来都会被
 *   再列一次，而那恰恰是最常见的情况。
 * - 主键 `(connectionId, path)`：同一连接下同一路径只有一行， upsert 天然去重。
 * - **列目录失败不写缓存**（写缓存的责任在 Repository 的成功分支）：
 *   失败是暂时的（网络抖动 / 会话过期），下次进来应该重试，而不是把失败记成
 *   「没有图片」缓存 30 天。
 * - [updatedAt] 用于 TTL 清理（30 天）：共享目录的内容会变（往里加了照片），
 *   超过 TTL 的行下次会重新列一次，保证封面不会永远停在旧图上。
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
  /** 第一张图片的 path；null = 列过了，这个文件夹里没有图片 */
  val coverPath: String?,
  val updatedAt: Long,
)
