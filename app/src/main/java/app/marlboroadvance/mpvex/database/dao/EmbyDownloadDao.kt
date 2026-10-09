package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.marlboroadvance.mpvex.database.entities.EmbyDownloadEntity
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务表。
 *
 * ⚠️ 本表**不做「自动清理」** —— 任务记录是用户可见的（下载管理页列的就是它），
 * 什么时候删由用户在页面上决定（删任务 / 清已完成），DAO 只提供能力。
 */
@Dao
interface EmbyDownloadDao {
  /**
   * 下载管理页的数据源。
   *
   * 排序：**未完成的在前、同组内按入队时间倒序**。
   * 用户最关心的是「现在还有什么在跑」，翻到列表底部才是在看历史 ——
   * 如果按时间正序，新建的任务会被历史挤到屏幕外，用户以为没加进去。
   *
   * 排序直接写在 SQL 里而不是 Kotlin 侧 sort：Room 的 Flow 只在表真的变了才重新发射，
   * 排序在 SQL 里能保证「发射出来的就是有序的」，不用每次 collect 都排一遍。
   */
  @Query(
    """
    SELECT * FROM emby_download_tasks
    ORDER BY
      CASE WHEN status IN ('QUEUED', 'RUNNING') THEN 0 ELSE 1 END ASC,
      createdAt DESC
    """,
  )
  fun observeAll(): Flow<List<EmbyDownloadEntity>>

  /** 冷启动载入用（一次性快照，不 Flow） */
  @Query("SELECT * FROM emby_download_tasks ORDER BY createdAt DESC")
  suspend fun getAll(): List<EmbyDownloadEntity>

  @Query("SELECT * FROM emby_download_tasks WHERE itemId = :itemId LIMIT 1")
  suspend fun find(itemId: String): EmbyDownloadEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(task: EmbyDownloadEntity)

  /**
   * 用 [tasks] **整表替换**当前内容（单事务）。
   *
   * 存在的理由：内存态的任务列表就是权威值，落盘时最不容易出错的写法是「把内存里有什么就写什么」，
   * 而不是「每次状态变化记得调对应的 update/delete」。后者漏一处就会留下
   * 「界面显示已暂停、重启后又是运行中」这种只在冷启动才暴露的 bug。
   *
   * ⚠️ 必须包在事务里 —— 不然「先清空、再逐条插入」之间进程被杀，
   * 用户的下载记录会**整表消失**。
   */
  @Transaction
  suspend fun replaceAll(tasks: List<EmbyDownloadEntity>) {
    deleteAll()
    if (tasks.isNotEmpty()) upsertAll(tasks)
  }

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(tasks: List<EmbyDownloadEntity>)

  @Query("DELETE FROM emby_download_tasks")
  suspend fun deleteAll()

  @Query("DELETE FROM emby_download_tasks WHERE itemId = :itemId")
  suspend fun delete(itemId: String)

  /**
   * 清掉已完成任务的记录（文件保留在 Download 目录里）。
   *
   * ⚠️ 只删**任务表**，不碰 `emby_downloaded_media` —— 那是「已落盘内容」的权威清单，
   * 用户清了下载记录不该让已下好的片子从离线列表里消失。
   */
  @Query("DELETE FROM emby_download_tasks WHERE status = 'COMPLETED'")
  suspend fun deleteCompleted()

  /** 换服务器 / 卸载前清空整表 */
  @Query("DELETE FROM emby_download_tasks")
  suspend fun clear()
}
