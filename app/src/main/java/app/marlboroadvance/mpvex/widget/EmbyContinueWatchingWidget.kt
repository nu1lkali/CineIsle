package app.marlboroadvance.mpvex.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import app.marlboroadvance.mpvex.MainActivity
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.emby.EmbyClient
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 桌面小组件：**继续观看**。
 *
 * 显示当前服务器上最多 3 条「看到一半」的内容（封面 + 片名 + 进度），点一下打开 App。
 * 数据走 [EmbyRepository.getResumeItems]，和首页那一行是同一个来源，口径自然一致。
 *
 * 几个必须守住的点：
 * - **不在主线程碰网络**：[onUpdate] 在主线程被调用，所以用 `goAsync()` 换一个后台窗口，
 *   跑完必须 `finish()`，否则系统会判这个 App 卡死。
 * - **失败不清空**：拉不到数据（没配服务器、没网、服务端不认）时保持上一次的画面。
 *   把小组件刷成一片空白，比留着旧内容更让人困惑。
 * - **图片要缩**：RemoteViews 传 bitmap 走的是 binder，有约 1MB 的事务上限，
 *   直接塞原图会 `TransactionTooLargeException`。这里按显示尺寸取 240px 的小图。
 */
class EmbyContinueWatchingWidget : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        val rows = loadRows(appContext)
        withContext(Dispatchers.Main) {
          appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(appContext, rows))
          }
        }
      } catch (_: Throwable) {
        // 保持上一次的画面
      } finally {
        pendingResult.finish()
      }
    }
  }

  override fun onEnabled(context: Context) {
    super.onEnabled(context)
    // 刚被拖到桌面时先渲染一次，不必等系统下一个更新周期（可能半小时后）
    refresh(context)
  }

  companion object {
    /** 小组件最多显示几条 */
    private const val MAX_ROWS = 3

    /** 缩略图解码宽度；按 56dp×36dp 的显示尺寸留了 2 倍余量 */
    private const val THUMB_WIDTH = 240

    /** 数据行：封面（可能为 null，用占位图标）+ 片名 + 进度百分比 */
    private data class Row(val thumb: Bitmap?, val title: String, val progress: Int)

    /**
     * 主动触发一次刷新。
     *
     * App 内想立刻同步桌面时调（例如刚播完一集、或用户下拉刷新了首页）。
     * 没装小组件时直接返回 —— 一条广播都不发。
     */
    fun refresh(context: Context) {
      val manager = AppWidgetManager.getInstance(context) ?: return
      val ids = manager.getAppWidgetIds(
        ComponentName(context, EmbyContinueWatchingWidget::class.java),
      )
      if (ids.isEmpty()) return
      context.sendBroadcast(
        Intent(context, EmbyContinueWatchingWidget::class.java).apply {
          action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        },
      )
    }

    private suspend fun loadRows(context: Context): List<Row> {
      // 小组件跑在 App 进程里，Koin 在 Application.onCreate 已经起好了；
      // 万一没有（比如被系统在冷启动时先拉起 receiver），就当作「没数据」，
      // 不要抛异常把进程带崩。
      // GlobalContext.getOrNull() 直接返回 Koin?（不是 KoinApplication），拿到即可用。
      val koin = GlobalContext.getOrNull() ?: return emptyList()
      val repository = runCatching { koin.get<EmbyRepository>() }.getOrNull() ?: return emptyList()

      // 优先用「当前服务器」；冷启动时它可能还没恢复好，退回第一台
      val server = repository.currentServer.value
        ?: runCatching { repository.getServers().firstOrNull() }.getOrNull()
        ?: return emptyList()

      val items = runCatching { repository.getResumeItems(server, MAX_ROWS) }
        .getOrDefault(emptyList())

      val rows = ArrayList<Row>(items.size)
      for (item in items.take(MAX_ROWS)) {
        val url = runCatching {
          EmbyClient.imageUrl(
            server = server,
            itemId = item.Id.orEmpty(),
            imageType = "Primary",
            tag = item.ImageTags["Primary"],
            maxWidth = THUMB_WIDTH,
          )
        }.getOrNull()
        val thumb =
          if (url != null) {
            runCatching { EmbyImageLoader.load(url, THUMB_WIDTH) }.getOrNull()
          } else {
            null
          }
        rows +=
          Row(
            thumb = thumb,
            title = item.Name ?: item.SeriesName ?: "",
            progress = progressOf(item),
          )
      }
      return rows
    }

    private fun progressOf(item: EmbyItem): Int {
      val total = item.RunTimeTicks ?: return 0
      if (total <= 0) return 0
      val position = item.UserData?.PlaybackPositionTicks ?: 0L
      return ((position * 100) / total).toInt().coerceIn(0, 100)
    }

    private fun buildViews(context: Context, rows: List<Row>): RemoteViews {
      val views = RemoteViews(context.packageName, R.layout.widget_emby_continue)

      val rowIds = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3)
      val thumbIds = intArrayOf(R.id.widget_thumb_1, R.id.widget_thumb_2, R.id.widget_thumb_3)
      val nameIds = intArrayOf(R.id.widget_name_1, R.id.widget_name_2, R.id.widget_name_3)
      val progressIds =
        intArrayOf(R.id.widget_progress_1, R.id.widget_progress_2, R.id.widget_progress_3)

      rows.forEachIndexed { index, row ->
        views.setViewVisibility(rowIds[index], View.VISIBLE)
        views.setTextViewText(nameIds[index], row.title)
        views.setProgressBar(progressIds[index], 100, row.progress, false)
        val thumb = row.thumb
        if (thumb != null) {
          views.setViewVisibility(thumbIds[index], View.VISIBLE)
          views.setImageViewBitmap(thumbIds[index], thumb)
        } else {
          // 不用 GONE：那样标题会左移，几行的对齐会跳；INVISIBLE 保留占位
          views.setViewVisibility(thumbIds[index], View.INVISIBLE)
        }
        // 点整行都打开 App（目前只做到「打开」，不带深链参数）
        views.setOnClickPendingIntent(rowIds[index], openAppIntent(context, index))
      }
      // 多出来的行收掉；太少时下面留白，不做拉伸
      for (index in rows.size until MAX_ROWS) {
        views.setViewVisibility(rowIds[index], View.GONE)
      }
      views.setViewVisibility(R.id.widget_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
      views.setOnClickPendingIntent(R.id.widget_title, openAppIntent(context, -1))
      return views
    }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
      val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        // IMMUTABLE 是 Android 12+ 的硬性要求；UPDATE_CURRENT 让重复点击复用同一个 PendingIntent
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}
