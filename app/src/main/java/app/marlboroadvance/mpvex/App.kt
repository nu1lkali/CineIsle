package app.marlboroadvance.mpvex

import android.app.Application
import android.util.Log
import app.marlboroadvance.mpvex.database.repository.VideoMetadataCacheRepository
import app.marlboroadvance.mpvex.di.DatabaseModule
import app.marlboroadvance.mpvex.di.FileManagerModule
import app.marlboroadvance.mpvex.di.PreferencesModule
import app.marlboroadvance.mpvex.domain.network.FOLDER_COVER_CACHE_VERSION
import app.marlboroadvance.mpvex.presentation.crash.CrashActivity
import app.marlboroadvance.mpvex.presentation.crash.CrashReporter
import app.marlboroadvance.mpvex.presentation.crash.GlobalExceptionHandler
import app.marlboroadvance.mpvex.privacy.AppStartupCoordinator
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
  private val embyServerRepository: app.marlboroadvance.mpvex.database.repository.EmbyServerRepository by inject()
  private val networkRepository: app.marlboroadvance.mpvex.repository.NetworkRepository by inject()
  private val dislikeRepository: app.marlboroadvance.mpvex.domain.dislike.DislikeRepository by inject()
  private val browserPreferences: app.marlboroadvance.mpvex.preferences.BrowserPreferences by inject()
  private val appearancePreferences: app.marlboroadvance.mpvex.preferences.AppearancePreferences by inject()
  private val subtitlesPreferences: app.marlboroadvance.mpvex.preferences.SubtitlesPreferences by inject()

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

    // 启动双标记：写 start、清 ok，并清除此前的「崩溃页已显示」防循环标记（每次冷启动都是新会话）。
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

    // ── 隐私门禁 ──
    // 从这里往后的一切（崩溃上报、原生库、媒体扫描、凭据升级、字幕迁移…）都
    // **不再是启动必做项**：它们要么联网、要么读用户文件、要么初始化第三方 SDK。
    // 交给 AppStartupCoordinator 托管后，只有授权有效时才会执行一次；
    // 未授权时主界面换成确认页，这段代码在用户点「同意并继续」之后才跑。
    //
    // ⚠️ 上面那两段（Koin 容器、崩溃页处理器）刻意留在门禁之前：
    //    Koin 只是进程内的依赖容器，处理器只把崩溃写到本机文件再拉起崩溃页，
    //    两者都不收集也不外发信息。少了它们，未授权时一旦崩溃就没有兜底页面。
    AppStartupCoordinator.start(this) { runConsentedStartup(lastBootStuck) }
    trace("privacy gate evaluated; App.onCreate done")
  }

  /**
   * 用户同意隐私政策之后的初始化。
   *
   * ## 为什么整段搬而不是逐条判断
   *
   * 这一段原本是 `onCreate` 的连续几十行，每条都带「失败也不能砸死启动」的
   * runCatching。逐条加 `if (granted)` 会让「哪些属于个人信息处理」变得不可读，
   * 将来新增一条也容易漏判。整体搬进一个方法之后，判据只剩一处：
   * **这个方法被不被调用**。
   *
   * ## 顺序仍然有讲究
   *
   * - [CrashReporter.init] 必须排在上面的 `setDefaultUncaughtExceptionHandler` 之后，
   *   否则 Bugly 会把自己挂成默认处理器，把本项目的崩溃页顶掉。
   * - FastThumbnails 的原生初始化仍然保留「安全模式」闸门（[lastBootStuck]），
   *   逻辑一字未改，只是执行时机从「开机」变成「同意后」。
   *
   * @param lastBootStuck 上次启动是否卡在 Application 阶段（原生崩溃疑似）
   */
  private fun runConsentedStartup(lastBootStuck: Boolean) {
    trace("consented startup begin")

    // ── 崩溃上报（Bugly）──
    // 内部已 runCatching、未配 App ID 或风味不含 SDK 时静默跳过，不会拖住启动。
    CrashReporter.init(this)
    trace("crash reporter init done")

    // Emby 封面图的磁盘缓存层（TTL 7 天 / 上限 256MB，设置页可查看占用与清除）
    app.marlboroadvance.mpvex.ui.browser.emby.components.EmbyImageLoader.init(this)

    // 「内容更新时间」排序结论的磁盘缓存（v352 预解析）：冷启动读回来，
    // 让记忆停在这一档的库进页面时顺序一次成形，不必等后台重算。
    // 放后台线程：读十来个几十 KB 的 JSON，不该占启动关键路径。
    kotlin.concurrent.thread(name = "folder-latest-cache-load") {
      kotlinx.coroutines.runBlocking {
        app.marlboroadvance.mpvex.domain.emby.FolderLatestCache.attachDir(applicationContext)
      }
    }

    // 启动即预热（v353）：用户的要求是「打开 App 就开始，而不是进库才开始」。
    // 等当前服务器就绪后，按偏好里记住的分类把「文件夹」档的库算一遍。
    // 全部在后台、失败静默、同一目录不重复 —— 它只是替用户先把重活干了。
    applicationScope.launch {
      runCatching {
        val repository: app.marlboroadvance.mpvex.domain.emby.EmbyRepository =
          org.koin.java.KoinJavaComponent.get(
            app.marlboroadvance.mpvex.domain.emby.EmbyRepository::class.java,
          )
        // 等当前服务器就绪：冷启动时仓库还在读库，这里挂起等第一个非空值
        val server = repository.currentServer.filterNotNull().first()
        app.marlboroadvance.mpvex.domain.emby.FolderLatestStartupPrewarm.run(server)
      }
    }

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

    // 解码能力探测：设置里「自动检测」两档要据此决定走硬解还是软解。
    // MediaCodecList 枚举全机编解码器是几十毫秒量级的活，**不能**等到
    // MPVView.initOptions（主线程）再算，开机就丢到后台预热，播放器侧只读结果。
    // 探测没跑完时消费方按「有硬解」处理（＝旧版固定行为），所以晚一点也不要紧。
    applicationScope.launch {
      app.marlboroadvance.mpvex.ui.player.DecoderCapability.probe(this@App)
    }

    // 凭据加密升级：老版本把 Emby / SMB / FTP / WebDAV 的密码以明文存在库里，
    // 这里启动后一次性改成 Keystore AES-GCM 密文（幂等，已经是密文的会跳过）。
    // 放在后台线程、整体 runCatching —— 升级失败不影响任何功能，下次启动会再试。
    applicationScope.launch {
      runCatching {
        val upgraded =
          embyServerRepository.encryptLegacyPlaintextPasswords() +
            networkRepository.encryptLegacyPlaintextPasswords()
        if (upgraded > 0) trace("credential encryption upgrade: $upgraded row(s)")
      }
    }

    // 网络文件夹封面缓存的 TTL 清理：删掉 12 小时没刷新过的 L2 行（启动时一次）。
    // 封面对的是「服务器上文件夹里当前有哪些图」，目录内容会变，
    // 一直不新鲜的缓存比没有缓存更糟（显示一个早就删掉的封面）。
    // L1 是进程内的不用管；失败吞掉 —— 少扫一次不过是多留几行旧数据。
    applicationScope.launch {
      runCatching { networkRepository.clearStaleFolderCovers() }
        .onFailure { trace("folder cover TTL cleanup failed: ${it.message}") }
    }

    // 封面**选择算法**换口径时，把落盘的旧结果整表作废一次（见
    // FOLDER_COVER_CACHE_VERSION 的说明）。不这么做的话，用户升级后看到的还是旧算法
    // 挑出来的封面，要等 12 小时 TTL 或手动下拉刷新 —— 表现就是「改了跟没改一样」。
    applicationScope.launch {
      runCatching {
        val stored = browserPreferences.folderCoverCacheVersion
        if (stored.get() < FOLDER_COVER_CACHE_VERSION) {
          networkRepository.clearAllFolderCovers()
          stored.set(FOLDER_COVER_CACHE_VERSION)
          trace("folder cover cache purged: algo v${stored.get()}")
        }
      }.onFailure { trace("folder cover purge failed: ${it.message}") }
    }

    // 「不喜欢」标记的内存快照：启动时读一次库。
    // 这是它**唯一**的读库时机 —— 之后所有查询都走内存（O(1)），
    // 所以播放器切视频时不会因为查状态而掉帧。
    // 失败必须吞掉：这是个锦上添花的功能，为它让整个 App 起不来是本末倒置。
    applicationScope.launch {
      runCatching { dislikeRepository.load() }
        .onFailure { trace("dislike load failed: ${it.message}") }
      // 出厂播种：把「不喜欢」按钮写进播放器控件布局（**只做一次**，见方法注释）。
      // 必须排在 load() 之后是刻意的：两者都碰同一批偏好/库，串起来跑省得并发。
      runCatching { seedDislikeButtonIntoPlayerLayout() }
        .onFailure { trace("dislike layout seed failed: ${it.stackTraceToString()}") }
      // 同批播种：「字幕时间线」按钮（v324 新增），只补一次。
      runCatching { seedSubtitleTimelineButton() }
        .onFailure { trace("subtitle timeline seed failed: ${it.stackTraceToString()}") }
    }

    // Trigger media scan on app launch to detect new videos
    applicationScope.launch {
      runCatching {
        triggerMediaScanOnLaunch()
      }
    }

    // 字幕编码归一化（一次性）：旧版本按字节原样落盘，私有字幕库里混着 GBK / Big5 /
    // UTF-16 的文件 —— 时间线预览能解对，mpv 挂载却是乱码。这里把它们就地重写成
    // UTF-8（原文件留 `.bak`），从此预览 / 播放 / 导出读的是同一份字节。
    // 放后台、整体 runCatching：转码失败只影响那条字幕的显示，绝不能拖住启动。
    applicationScope.launch {
      runCatching { migrateSubtitleEncoding() }
        .onFailure { trace("subtitle encoding migration failed: ${it.stackTraceToString()}") }
    }
    trace("consented startup done")
  }

  /**
   * 把私有目录里非 UTF-8 的字幕文件就地重写（只做一次）。
   *
   * ## 为什么需要它
   *
   * 落盘归一化只能管住**升级之后**新下载的字幕。用户升级前已经下载好的那些
   * （迅雷 / SubtitleCat 拉下来的 GBK、Big5 文件）仍在磁盘上，mpv 挂载时照样会
   * 自己猜一次编码 ⇒ 「预览正常、画面乱码」的老问题会继续存在。
   *
   * ## 为什么改文件是安全的
   *
   * 扫的两个目录都在应用私有存储里（`filesDir/online_subtitles`、`cacheDir/emby_subtitles`），
   * 内容本来就是我们自己下载的，用户没有别的途径去编辑它们。
   * 每条重写前都会留一份 `<name>.bak`，万一判定失误还能取回原始字节。
   */
  private fun migrateSubtitleEncoding() {
    val target = 1
    if (subtitlesPreferences.subtitleEncodingMigrationVersion.get() >= target) return
    val online =
      app.marlboroadvance.mpvex.domain.subtitle.SubtitleFileNormalizer
        .normalizeTree(File(filesDir, "online_subtitles"))
    val emby =
      app.marlboroadvance.mpvex.domain.subtitle.SubtitleFileNormalizer
        .normalizeTree(File(cacheDir, "emby_subtitles"))
    subtitlesPreferences.subtitleEncodingMigrationVersion.set(target)
    if (online > 0 || emby > 0) {
      trace("subtitle encoding migration v$target: online=$online emby=$emby rewritten as UTF-8")
    }
  }

  /**
   * 出厂播种：把「标记为不喜欢」按钮加进播放器控件布局。
   *
   * ## 为什么必须有这一步
   *
   * mpv 播放页的按钮**不是硬编码的**，它是把控件簇的 CSV（如 `top_right_controls`）
   * 解析成按钮列表后渲染的（见 `PlayerControlsShared`）。而「播放器按钮」开关的语义是
   * 「关 ⇒ 从布局里清掉；开 ⇒ 只置开关、不主动加回」—— 这个语义成立的前提是
   * **出厂时按钮已经在布局里**。
   *
   * 第一版漏了播种，结果：开关默认「开」、设置页显示「已开启」，
   * 但 mpv 播放器里**到处都找不到入口**，看上去像开关坏了。
   *
   * ## 为什么只播一次
   *
   * 用户有权把按钮从所有控件簇里删掉（那就是他的布局决定）。每次启动都塞回去
   * 等于反复撤销用户的操作，所以播完置 `dislike_layout_seeded`，之后永不再动。
   *
   * ## 为什么不能只改偏好默认值
   *
   * 控件簇的 CSV 一旦被读过就写进了本地存储，改 `getString` 的默认值对**已安装用户
   * 完全无效**（`AppearancePreferences` 里 v2/v3 换 key 的注释就是这个教训）。
   * 默认值那道保险只覆盖全新用户，老用户靠这里。
   */
  private fun seedDislikeButtonIntoPlayerLayout() {
    // ⚠️ 版本**必须随 [DislikeLayoutSync.SEED_CLUSTERS] 一起涨**：
    //  只改簇列表不改版本号的话，已经播过旧版本的用户会被跳过，新加的簇永远补不上。
    val targetVersion = 2
    if (browserPreferences.dislikeLayoutSeedVersion.get() >= targetVersion) return

    val sync = app.marlboroadvance.mpvex.ui.preferences.DislikeLayoutSync
    val prefs = sync.clusterPrefs(appearancePreferences)
    val plan = sync.planSeed(current = sync.readClusters(prefs))
    if (plan.writes.isNotEmpty()) {
      sync.writeClusters(prefs, plan.writes)
      trace("dislike layout seeded to v$targetVersion: ${plan.writes}")
    }
    // 不管这次有没有真的写入都要落版本号：
    // 已经齐了的就别再算一遍，缺的这次补完，下次也不必再算。
    browserPreferences.dislikeLayoutSeedVersion.set(targetVersion)
  }

  /**
   * 把「字幕时间线」按钮补进已有布局（**只补一次**）。
   *
   * 与 [seedDislikeButtonIntoPlayerLayout] 同一个问题：控件簇的 CSV 一旦被读过就落进
   * 本地存储，改 `getString` 默认值只对全新用户生效。老用户升级后按钮本来会「查无此键」——
   * 设置页里能看到它，播放器里却到处找不到，看上去像功能没做。
   *
   * 只往**两个出厂位置的末尾追加**，不动已有顺序：
   *  · 横屏顶栏右侧（字幕相关，与「更多」同一带）；
   *  · 竖屏底部按钮条（竖屏顶栏刻意只放 5 个，塞第 6 个会把标题压成省略号）。
   *
   * 用户后来把按钮从所有簇里删掉是他的布局决定，所以必须置标记、永不重复播种。
   */
  private fun seedSubtitleTimelineButton() {
    if (appearancePreferences.subtitleTimelineButtonSeeded.get()) return
    val target = app.marlboroadvance.mpvex.preferences.PlayerButton.SUBTITLE_TIMELINE.name
    listOf(
      appearancePreferences.topRightControls,
      appearancePreferences.portraitBottomControls,
    ).forEach { pref ->
      val csv = pref.get()
      val present = csv.split(',').any { it.trim().equals(target, ignoreCase = true) }
      if (!present) pref.set(if (csv.isBlank()) target else "$csv,$target")
    }
    appearancePreferences.subtitleTimelineButtonSeeded.set(true)
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
