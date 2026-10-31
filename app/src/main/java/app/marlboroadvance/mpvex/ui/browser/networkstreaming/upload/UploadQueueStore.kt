package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待传队列的落盘（**只存「还没传完的」那一部分**）。
 *
 * ## 为什么需要它
 *
 * 队列活在进程上（[NetworkUploadCenter] 是进程级单例），但进程随时可能被系统回收 ——
 * 传 2GB 的时候锁屏、切去别的 App、内存吃紧，用户回来会发现「上传结束」，而文件只传了
 * 一半。有这份落盘，下次打开上传面板就能问一句「上次还有 12 个没传完，继续吗」。
 *
 * ## 存什么 / 不存什么
 *
 * 只存**能重新打开的数据源**：文件路径或 `content://`。进度不存 —— 上传不支持断点续传
 * （AList 的 put 是整文件一条流），存了也用不上，反而会让「已完成 60%」这种数字
 * 在新的会话里变成假的。文件在上传过程中被用户删掉时，[load] 会把它剔除，
 * 不会恢复出一条永远失败的任务。
 *
 * ## 为什么每次传完一个文件就写一次
 *
 * 只在批次开始/结束时写，进程在中间被回收就什么都留不下。每条任务两三百字节的
 * JSON 写入，几百个文件也就是几十毫秒的总开销（而且写在 IO 线程）。
 */
internal object UploadQueueStore {
  private const val TAG = "UploadQueueStore"
  private const val PREFS = "upload_queue"

  private const val KEY_PICKS = "picks"
  private const val KEY_CONN = "connection_id"
  private const val KEY_DIR = "target_dir"
  private const val KEY_LABEL = "target_label"
  private const val KEY_SAVED_AT = "saved_at"

  /** 超过这么久就不再提示恢复：用户早不记得那批文件是什么了，弹出来只是打扰 */
  private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

  /**
   * 一份可恢复的队列。
   *
   * @param picks 按**原始顺序**挑出来的未完成项（不含已成功的），
   *   直接喂给 [NetworkUploadCenter.start] 即可
   */
  data class Snapshot(
    val connectionId: Long,
    val targetDir: String,
    val targetLabel: String,
    val picks: List<UploadPick>,
  ) {
    val sizeBytes: Long get() = picks.sumOf { it.sizeBytes }
  }

  fun save(
    context: Context,
    connectionId: Long,
    targetDir: String,
    targetLabel: String,
    picks: List<UploadPick>,
  ) {
    runCatching {
      val arr = JSONArray()
      picks.forEach { pick ->
        val uri = pick.contentUri?.toString()
        val path = pick.file?.absolutePath
        // 两样都没有 = 这个 pick 本来就打不开，存进去只会在恢复时变成一条必失败的任务
        if (uri == null && path == null) return@forEach
        arr.put(
          JSONObject().apply {
            put("r", pick.relativePath)
            put("s", pick.sizeHint)
            if (uri != null) put("u", uri)
            if (path != null) put("p", path)
          },
        )
      }
      if (arr.length() == 0) {
        clear(context)
        return@save
      }
      context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_PICKS, arr.toString())
        .putLong(KEY_CONN, connectionId)
        .putString(KEY_DIR, targetDir)
        .putString(KEY_LABEL, targetLabel)
        .putLong(KEY_SAVED_AT, System.currentTimeMillis())
        .apply()
    }
      .onFailure { Log.w(TAG, "保存待传队列失败", it) }
  }

  /** 读出可恢复的队列；没有 / 太旧 / 文件已被删光时返回 null */
  fun load(context: Context): Snapshot? =
    runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PICKS, null) ?: return null
        if (System.currentTimeMillis() - prefs.getLong(KEY_SAVED_AT, 0L) > MAX_AGE_MS) {
          clear(context)
          return null
        }
        val arr = JSONArray(raw)
        val picks = ArrayList<UploadPick>(arr.length())
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val relativePath = obj.optString("r")
          if (relativePath.isEmpty()) continue
          val uri = obj.optString("u").takeIf { it.isNotEmpty() }?.let(Uri::parse)
          val file = obj.optString("p").takeIf { it.isNotEmpty() }?.let(::File)
          // 路径型来源的：文件已经被删/改名了就别再恢复（恢复出来只会立刻失败）
          if (uri == null && (file == null || !file.exists())) continue
          picks +=
            UploadPick(
              file = file,
              relativePath = relativePath,
              contentUri = uri,
              sizeHint = obj.optLong("s", -1L),
            )
        }
        if (picks.isEmpty()) {
          clear(context)
          return null
        }
        Snapshot(
          connectionId = prefs.getLong(KEY_CONN, -1L),
          targetDir = prefs.getString(KEY_DIR, "").orEmpty(),
          targetLabel = prefs.getString(KEY_LABEL, "").orEmpty(),
          picks = picks,
        )
      }
      .getOrElse {
        Log.w(TAG, "读取待传队列失败", it)
        null
      }

  fun clear(context: Context) {
    runCatching {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
  }
}
