package app.marlboroadvance.mpvex.ui.browser.networkstreaming.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.marlboroadvance.mpvex.MainActivity
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.utils.media.MediaFormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 上传期间的前台服务 + 通知栏进度。
 *
 * ## 为什么必须有
 *
 * 上传队列活在进程上（[NetworkUploadCenter]），而进程在后台随时可能被回收 ——
 * 传 2GB 的时候锁屏、切走、内存吃紧，用户回来只看到「上传结束」，文件却没传完。
 * 前台服务把进程抬成「正在做用户可见的工作」，系统不再随手回收；顺带把进度摆进
 * 通知栏，「切走了也能看见」这件事才成立。
 *
 * 分享上传尤其依赖它：那条路的返回键是 `moveTaskToBack`（见 `ShareUploadActivity`），
 * **应用已经在后台**，没有前台服务等于把这个设计架在半空。
 *
 * ## 为什么通知只读、不重复造数据
 *
 * 速度与剩余时间由 [NetworkUploadCenter.stats] 统一采样（同一份算法、同一套口径）。
 * 通知和进度面板若各算一次，用户能同时看到两个不一样的「剩余 3 分钟 / 剩余 5 分钟」。
 *
 * ## 为什么每秒刷新而不是每 100ms
 *
 * 通知栏的重排是有成本的（系统要把通知重新排版、动画），而且人眼在这个位置上也
 * 分辨不出更细的粒度。进度条本身用 `setProgress` 的 permille，1 秒一档足够平滑。
 */
class UploadForegroundService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var ticker: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel(this)
    // ⚠️ 必须在 5 秒内进前台，否则系统抛 ANR 级的异常。
    // 这里先摆一条「准备中」——真实进度由下面的循环补上。
    startAsForeground(buildNotification(title = "正在上传", text = "准备中…", permille = null, running = true))
    ticker?.cancel()
    ticker =
      scope.launch {
        while (true) {
          val batch = NetworkUploadCenter.batch.value
          // 只有两种状态：结束（进程里没批次了，或批次已经不在跑）与在传。
          // ⚠️ 没有「暂停」：AList 没有断点续传，停下来的唯一语义是取消（见
          // NetworkUploadCenter.cancel 的 KDoc），所以通知栏也不需要第三种形态。
          if (batch == null || !batch.running) {
            finishWithResult(resultTitle(batch), resultText(batch))
            return@launch
          }
          notify(
            buildNotification(
              title = "正在上传 ${batch.finishedCount} / ${batch.tasks.size}",
              text = formatSpeedLine(
                NetworkUploadCenter.stats.value.speedBps,
                NetworkUploadCenter.stats.value.etaSeconds,
              ),
              permille = (batch.progress * 1000).toInt().coerceIn(0, 1000),
              running = true,
            ),
          )
          delay(1000)
        }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // ⚠️ 通知栏**不放「取消上传」按钮**：取消是不可逆的（网盘没有断点续传，
    // 已经传上去的那部分作废，服务端残留还会被清掉），必须先在界面上确认。
    // 通知只负责「让你知道它在跑」，点它是回到 App。
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  /** 收尾：摆一条可点掉的「结果通知」，然后退出前台并结束服务 */
  private fun finishWithResult(title: String, text: String) {
    notify(buildNotification(title = title, text = text, permille = 1000, running = false))
    stopForegroundCompat(detach = true)
    stopSelf()
  }

  // ── 通知 ──

  private fun buildNotification(
    title: String,
    text: String,
    permille: Int?,
    running: Boolean,
  ): Notification {
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(title)
      .setContentText(text)
      .setOnlyAlertOnce(true)
      .setOngoing(running)
      .setShowWhen(false)
      .setContentIntent(open)
      .apply {
        if (permille != null) {
          setProgress(1000, permille, false)
        } else {
          setProgress(0, 0, true)
        }
        if (!running) setAutoCancel(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
          priority = NotificationCompat.PRIORITY_LOW
        }
      }
      .build()
  }

  private fun startAsForeground(notification: Notification) {
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      }
    runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
  }

  private fun notify(notification: Notification) {
    // 没有通知权限时 notify 会静默失败（Android 13+）。前台服务本身照跑，
    // 所以这里只兜住异常，不做任何提示 —— 用户没给权限就是不想看通知。
    runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
  }

  private fun stopForegroundCompat(detach: Boolean) {
    runCatching {
      ServiceCompat.stopForeground(
        this,
        if (detach) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE,
      )
    }
  }

  private fun resultTitle(batch: UploadBatch?): String =
    when {
      batch == null -> "上传结束"
      batch.failedCount > 0 -> "上传结束 · 有失败项"
      batch.cancelledCount > 0 && batch.successCount == 0 -> "上传已取消"
      batch.cancelledCount > 0 -> "上传结束 · 部分已取消"
      else -> "上传完成"
    }

  private fun resultText(batch: UploadBatch?): String {
    if (batch == null) return "点开查看详情"
    return buildString {
      append("成功 ${batch.successCount} 个")
      if (batch.failedCount > 0) append(" · 失败 ${batch.failedCount} 个")
      if (batch.cancelledCount > 0) append(" · 未传 ${batch.cancelledCount} 个")
      append(" · ${okSize(batch)}")
    }
  }

  private fun okSize(batch: UploadBatch): String = MediaFormatUtils.formatFileSize(batch.sentBytes)

  companion object {
    private const val CHANNEL_ID = "cineisle_upload_channel"
    private const val NOTIFICATION_ID = 8801

    /**
     * 起前台服务。
     *
     * ⚠️ `startForegroundService` 在 Android 12+ 有「后台不能启动前台服务」的限制。
     * 上传总是从界面（选择器 / 分享页）触发的，属于前台场景；但重试也可能由通知栏
     * 的动作触发（那时服务已经在跑），所以这里仍用 `runCatching` 兜一层：
     * 起不来最多是没有通知栏进度，不该让上传本身失败。
     */
    fun start(context: Context) {
      runCatching {
        ContextCompat.startForegroundService(
          context,
          Intent(context, UploadForegroundService::class.java),
        )
      }
    }

    fun stop(context: Context?) {
      if (context == null) return
      runCatching { context.stopService(Intent(context, UploadForegroundService::class.java)) }
    }

    fun createChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      if (manager.getNotificationChannel(CHANNEL_ID) != null) return
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "后台上传", NotificationManager.IMPORTANCE_LOW).apply {
          description = "上传到网络位置时的进度提示"
          setShowBadge(false)
        },
      )
    }
  }
}
