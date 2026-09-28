package app.marlboroadvance.mpvex.presentation.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import kotlin.system.exitProcess

class GlobalExceptionHandler(
  private val context: Context,
  private val activity: Class<*>,
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(
    t: Thread,
    e: Throwable,
  ) {
    // 防循环：如果本次运行已经显示过崩溃页（crash_shown 标记存在），说明崩溃页自身又崩了，
    // 直接退出，避免「CrashActivity 自爆 → 再启动 → 再自爆」的无限白屏循环。
    // 该标记由 App.onCreate 在每次冷启动时清除，所以正常重开应用仍能看到崩溃页。
    val shownFile = File(context.filesDir, "crash_shown")
    if (runCatching { shownFile.exists() }.getOrDefault(false)) {
      exitProcess(0)
    }
    runCatching { shownFile.writeText("1") }

    // 兜底：把堆栈写到文件，万一 CrashActivity 自身也崩，至少还能从文件里看到原因。
    runCatching {
      val file = File(context.filesDir, "last_crash.txt")
      file.writeText(
        buildString {
          appendLine("Thread: ${t.name}")
          appendLine("Device: ${Build.BRAND} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
          appendLine("Time: ${System.currentTimeMillis()}")
          appendLine()
          appendLine(e.stackTraceToString())
        },
      )
    }
    val intent = Intent(context, activity)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    intent.putExtra("exception", e.stackTraceToString())
    context.startActivity(intent)
    exitProcess(0)
  }
}
