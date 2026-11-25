package app.marlboroadvance.mpvex.presentation.crash

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.BuildConfig

/**
 * Bugly 崩溃上报的接入层。
 *
 * ## 为什么不直接 `import com.tencent.bugly.crashreport.CrashReport`
 *
 * 上报 SDK 只打进 `standard` / `playstore` 两个风味（依赖写在 build.gradle.kts 的
 * **风味专属**配置里）。`fdroid` 风味按 F-Droid 的分发要求不带任何第三方上报 SDK，
 * 因此在那个变体里 `com.tencent.bugly.**` 这个包**根本不存在** —— 一旦写成编译期引用，
 * fdroid 变体直接编译失败。走反射后：类在就正常初始化，类不在就静默跳过。
 *
 * ## 为什么不把 App ID 写死
 *
 * App ID 在构建期从本机 `local.properties` 读到 `BuildConfig.BUGLY_APP_ID`
 * （该文件已被 .gitignore 忽略）。没配的设备编出来的字段是空串，这里直接返回 ——
 * 新克隆的仓库不配任何凭据也能正常编译、正常跑，只是没有上报。
 *
 * ## 初始化时机
 *
 * 必须在 `Thread.setDefaultUncaughtExceptionHandler(...)` **之后**调用：
 * Bugly 初始化时会把自己挂成默认处理器，并把当时的处理器存为「上一个」，
 * 崩溃时先落盘再转交给它。顺序反了，本项目的 [GlobalExceptionHandler]
 * （写 last_crash.txt + 拉崩溃页）就会把 Bugly 顶掉，Java 崩溃一条都收不到。
 *
 * ## 隐私合规
 *
 * 当前应用没有「同意隐私政策」的前置闸门，所以随启动初始化。若之后加入该闸门，
 * 应把调用点挪到同意之后的回调里，而不是留在这里 —— 上报会采集设备信息，
 * 先采集后同意在应用市场审核里是硬伤。
 */
internal object CrashReporter {
  private const val TAG = "CrashReporter"

  private const val CLASS_CRASH_REPORT = "com.tencent.bugly.crashreport.CrashReport"
  private const val CLASS_USER_STRATEGY = "com.tencent.bugly.crashreport.CrashReport\$UserStrategy"

  /** 是否已经初始化过（`App.onCreate` 只在进程启动时跑一次，这里主要防重复调用）。 */
  @Volatile
  private var initialized = false

  /**
   * 初始化崩溃上报。任何失败都不抛异常 —— 上报是锦上添花，不能因为它让应用起不来。
   */
  fun init(context: Context) {
    if (initialized) return
    if (!BuildConfig.ENABLE_BUGLY) {
      Log.i(TAG, "skip: 当前风味不包含崩溃上报 SDK")
      return
    }
    val appId = BuildConfig.BUGLY_APP_ID
    if (appId.isBlank()) {
      Log.i(TAG, "skip: local.properties 未配置 bugly.appId")
      return
    }
    val app = context.applicationContext
    runCatching { installReflectively(app, appId) }
      .onSuccess { initialized = true }
      .onFailure { Log.w(TAG, "init failed: ${it.message}", it) }
  }

  /**
   * 反射完成 `CrashReport.initCrashReport(context, appId, isDebug, UserStrategy)`。
   *
   * 分开写是为了让 `onFailure` 能定位到「缺类」还是「签名对不上」：
   * fdroid 变体是 `ClassNotFoundException`，SDK 升级改签名会是 `NoSuchMethodException`。
   */
  private fun installReflectively(
    context: Context,
    appId: String,
  ) {
    val crashReport = Class.forName(CLASS_CRASH_REPORT)
    // 注意：这里不调用 initCrashReport 的三个参数重载 —— 那个重载不会带 strategy，
    // 渠道与原生崩溃开关就只能在 manifest 里配，本项目没有走 manifest。
    val strategyClass = Class.forName(CLASS_USER_STRATEGY)
    val strategy = strategyClass.getConstructor(Context::class.java).newInstance(context)

    // setAppVersion / setAppChannel / setEnableNativeCrashMonitor 都定义在父类
    // BuglyStrategy 上，method 查找要沿父类走（getMethod 本身会找父类，这里显式注明）。
    strategyClass.getMethod("setAppVersion", String::class.java)
      .invoke(strategy, BuildConfig.VERSION_NAME)
    // 渠道区分发行方式，后台按渠道看崩溃率时不会把 playstore 包和自有渠道包混在一起
    strategyClass.getMethod("setAppChannel", String::class.java)
      .invoke(strategy, BuildConfig.FLAVOR)
    // 原生崩溃（SIGSEGV / SIGABRT / fdsan）是本项目线上最需要看的一类 ——
    // AAR 里带了 5 个 ABI 的 libBugly_Native.so，这里显式打开，不依赖默认值。
    strategyClass.getMethod("setEnableNativeCrashMonitor", java.lang.Boolean.TYPE)
      .invoke(strategy, true)

    crashReport
      .getMethod(
        "initCrashReport",
        Context::class.java,
        String::class.java,
        java.lang.Boolean.TYPE,
        strategyClass,
      )
      .invoke(null, context, appId, BuildConfig.DEBUG, strategy)

    Log.i(
      TAG,
      "init ok: appId=${appId.take(4)}*** channel=${BuildConfig.FLAVOR} " +
        "version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}",
    )
  }
}
