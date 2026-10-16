package app.marlboroadvance.mpvex.domain.network

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「网络」里收藏的目录（快捷入口）。
 *
 * 只收**目录**不收单文件：单文件的快捷价值低（收藏夹里一长串更找不到），
 * 而目录是「这个相册 / 这部片子的文件夹」级的心智单位，与照片库「逛相册」同构。
 *
 * 唯一索引 `(connectionId, path)`：同一连接下同一路径重复收藏会被数据库挡掉，
 * 上层不需要先查再插。（同名目录在不同连接是两个不同的收藏，正常。）
 */
@Entity(
  tableName = "network_favorites",
  indices = [
    Index(value = ["connectionId", "path"], unique = true),
    Index(value = ["addedAt"]),
  ],
)
data class NetworkFavorite(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val connectionId: Long,
  /** 目录在该连接内的路径（与 NetworkBrowserScreen.currentPath 同一口径） */
  val path: String,
  /** 收藏时看到的目录名（连接改了名 / 目录被挪走也不影响已存快捷入口的显示） */
  val name: String,
  /** 收藏时看到的连接名，同上——快照，不跟连接表联动 */
  val connectionName: String,
  val addedAt: Long = System.currentTimeMillis(),
)
