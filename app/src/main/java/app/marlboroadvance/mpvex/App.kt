package app.marlboroadvance.mpvex

import android.app.Application
import android.util.Log
import app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository
import app.marlboroadvance.mpvex.di.DatabaseModule
import app.marlboroadvance.mpvex.di.FileManagerModule
import app.marlboroadvance.mpvex.di.PreferencesModule
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import app.marlboroadvance.mpvex.presentation.crash.GlobalExceptionHandler
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.annotation.KoinExperimentalAPI
import java.io.File

@OptIn(KoinExperimentalAPI::class)
class App : Application() {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val metadataCache: VideoMetadataCacheRepository by inject()

  companion object {
    private var instance: App? = null

    /**
     * MainActivity 首帧成功渲染后调用，写入 boot_ok.txt。
     * 若上次启动在 Application 阶段就崩溃（最可能是 FastThumbnails 原生崩溃，SIGSEGV 不经过 JVM handler），
     * 则 boot_started 存在而 boot_ok 不存在 -> 下次进入安全模式跳过原生初始化，打断死循环。
     */
    fun markBootOk() {
      instance?.runCatching {
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, "boot_ok.txt").writeText("${System.currentTimeMillis()}")
      }
    }
  }

  /**
   * 启动轨迹：写到外部 files 目录 + logcat，便于无 adb 时定位卡死/崩溃发生在哪一步。
   * 路径：Android/data/app.marlboroadvance.mpvex.debug/files/startup_trace.txt
   */
  private fun trace(msg: String) {
    Log.i("StartupTrace", msg)
    runCatching {
      val dir = getExternalFilesDir(null) ?: filesDir
      File(dir, "startup_trace.txt").appendText("${System.currentTimeMillis()} $msg\n")
    }
  }

  /**
   * 启动双标记（写到外部 files 目录）：
   *  - boot_started.txt：App.onCreate 一开始即写入（覆盖式），表示「本次启动已走到 Application 阶段」。
   *  - boot_ok.txt：MainActivity 首帧渲染成功后由 markBootOk() 写入。
   * 若下次冷启动发现 boot_started 存在但 boot_ok 不存在，说明上次在 Application 阶段就崩了
   * （最可能是 FastThumbnails 加载 libmpv 的原生 SIGSEGV，它不经过 JVM 异常处理器），
   * 此时进入「安全模式」跳过 FastThumbnails 的原生初始化，打断「原生崩溃→被杀→重拉→再崩」的死循环。
   * 该机制对原生崩溃有效，而旧版依赖 handler 写标记的方式对原生崩溃完全失效。
   */
  private fun bootStartedFile(): File = File(getExternalFilesDir(null) ?: filesDir, "boot_started.txt")
  private fun bootOkFile(): File = File(getExternalFilesDir(null) ?: filesDir, "boot_ok.txt")

  override fun onCreate() {
    instance = this
    trace("App.onCreate start")
    super.onCreate()
    trace("super.onCreate done")

    // 启动双标记：写 start、清 ok，并清除上一轮的「崩溃页已显示」防循环标记（每次冷启动都是新会话）。
    val lastBootStuck = runCatching { bootStartedFile().exists() && !bootOkFile().exists() }.getOrDefault(false)
    runCatching { bootStartedFile().writeText("${System.currentTimeMillis()}") }
    runCatching { bootOkFile().delete() }
    runCatching { File(filesDir, "crash_shown").delete() }
    trace("boot markers written; lastBootStuck=$lastBootStuck")

    try {
      startKoin {
        androidContext(this@App)
        modules(
          PreferencesModule,
          DatabaseModule,
          FileManagerModule,
          app.marlboroadvance.mpvex.di.domainModule,
          app.marlboroadvance.mpvex.dlna.dlnaModule,
        )
      }
      trace("startKoin done")
    } catch (e: Throwable) {
      trace("startKoin FAILED: ${e.stackTraceToString()}")
      throw e
    }

    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))
    trace("uncaught handler set")

    // FastThumbnails 会加载 native 库（libmpv 等），在某些设备/ABI 上可能原生崩溃或抛 UnsatisfiedLinkError。
    // 用 try/catch 包住：即便它失败也只是视频缩略图不可用，绝不该把整个启动砸死。
    // 若检测到上次启动卡在 Application 阶段（lastBootStuck，通常是它自身原生崩溃），则进入安全模式跳过，
    // 打断「原生崩溃→被杀→重拉→再崩」的死循环，让应用至少能进首页、让我们能从 startup_trace 看到真相。
    trace("before FastThumbnails.initialize")
    if (lastBootStuck) {
      trace("lastBootStuck -> 安全模式：跳过 FastThumbnails.initialize，避免再次原生崩溃死循环")
    } else {
      try {
        FastThumbnails.initialize(this)
        trace("after FastThumbnails.initialize")
      } catch (e: Throwable) {
        trace("FastThumbnails.initialize FAILED (non-fatal): ${e.stackTraceToString()}")
      }
    }

    // Perform cache maintenance on app startup (non-blocking)
    applicationScope.launch {
      runCatching {
        metadataCache.performMaintenance()
      }
    }

    // Trigger media scan on app launch to detect new videos
    applicationScope.launch {
      runCatching {
        triggerMediaScanOnLaunch()
      }
    }
    trace("App.onCreate done")
  }

  /**
   * Trigger a media scan on app launch to ensure MediaStore is up-to-date
   * This helps detect videos added by external apps while the app was closed
   */
  private fun triggerMediaScanOnLaunch() {
    try {
      val externalStorage = android.os.Environment.getExternalStorageDirectory()

      android.media.MediaScannerConnection.scanFile(
        this,
        arrayOf(externalStorage.absolutePath),
        null, // Let MediaScanner detect all media types
      ) { path, uri ->
        android.util.Log.d("App", "Launch media scan completed for: $path")
        // Notify the app that media library may have changed
        MediaLibraryEvents.notifyChanged()
      }

      android.util.Log.d("App", "Triggered media scan on app launch")
    } catch (e: Exception) {
      android.util.Log.e("App", "Failed to trigger media scan on launch", e)
    }
  }
}
