package app.marlboroadvance.mpvex.utils.net

import android.util.Log
import app.marlboroadvance.mpvex.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * **非 Emby 客户端的统一构造入口。**
 *
 * ## 现状与边界
 *
 * Emby 那条链路早就收口到 [app.marlboroadvance.mpvex.domain.emby.EmbyHttp]
 * （共享连接池 + token 注入 + 退避重试 + 401 重登）。但它**只服务 Emby**：
 * 它的拦截器会往请求里塞 Emby token、失败会退回重试并阻塞 `Thread.sleep`，
 * 拿它去访问字幕站或网盘直链既是信息泄漏也是性能事故。
 *
 * 所以这里不是要「把所有客户端合并成一个」，而是让**构造方式只有一处**：
 * 弹幕 / 在线字幕 / AList / WebDAV Range / ISO 直链 / 播放预热 各自保留独立连接池
 * （这是对的 —— 它们的目标主机、并发模型、超时语义完全不同），
 * 但「`OkHttpClient.Builder()` 怎么写」不再散在 8 个文件里。
 *
 * 好处是可审计：想知道「全 App 有哪些出网客户端、各自什么策略」，
 * 看这 8 处调用点即可，不用全文搜 `OkHttpClient`。
 *
 * ## 什么不该用这里
 *
 * · Emby 请求 → 用 `EmbyHttp`（它自带 token 与健康判定）；
 * · WebDAV 业务方法 → 走 `OkHttpSardine` 自带的池，它不接受外部 client。
 */
object HttpClients {

  private const val TAG = "HttpClients"

  /**
   * 应用自身的 UA，形如 `CineIsle/1.6.85 (Android)`。
   *
   * ⚠️ 版本**必须**取 [BuildConfig.VERSION_NAME]，不要写字面量。
   * 之前弹幕客户端写死了 `CineIsle/1.2.5`，跨了十几个版本没人发现 ——
   * 第三方按 UA 统计版本时看到的是一年前的数据，而代码里又搜不到「1.2.5」。
   */
  val appUserAgent: String by lazy { "CineIsle/${BuildConfig.VERSION_NAME} (Android)" }

  /** OkHttp 对写超时的默认值（秒）。多数 GET 场景用不到，保持默认最不容易出错。 */
  private const val DEFAULT_WRITE_TIMEOUT_SECONDS = 10L

  /**
   * 构造一个客户端。
   *
   * @param name 仅用于 debug 日志定位「这是哪一档」，不参与任何逻辑
   * @param connectTimeoutSeconds 建连超时
   * @param readTimeoutSeconds 两次读到字节之间的上限（不是整段下载上限）
   * @param writeTimeoutSeconds 写超时，默认 10s（OkHttp 默认值）
   * @param callTimeoutSeconds 整次调用上限；0 = 不设（流式下载必须为 0）
   * @param retryOnConnectionFailure 连接层重试；「越快失败越好」的场景传 false
   * @param followRedirects 是否跟随 3xx
   * @param userAgent 非空时挂一个「请求未自带 UA 才补」的拦截器，单次请求显式设的 UA 优先
   */
  fun build(
    name: String,
    connectTimeoutSeconds: Long = 15L,
    readTimeoutSeconds: Long = 30L,
    writeTimeoutSeconds: Long = DEFAULT_WRITE_TIMEOUT_SECONDS,
    callTimeoutSeconds: Long = 0L,
    retryOnConnectionFailure: Boolean = true,
    followRedirects: Boolean = true,
    userAgent: String? = null,
  ): OkHttpClient {
    val builder =
      OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(retryOnConnectionFailure)
        .followRedirects(followRedirects)
    if (callTimeoutSeconds > 0) builder.callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    if (!userAgent.isNullOrBlank()) {
      builder.addInterceptor { chain ->
        val req = chain.request()
        // 已经显式带 UA 的就不覆盖（例如按服务器来源拼的 Referer/UA 组）
        if (req.header("User-Agent") != null) {
          chain.proceed(req)
        } else {
          chain.proceed(req.newBuilder().header("User-Agent", userAgent).build())
        }
      }
    }
    if (BuildConfig.DEBUG) {
      Log.d(
        TAG,
        "build($name) connect=${connectTimeoutSeconds}s read=${readTimeoutSeconds}s " +
          "call=${callTimeoutSeconds}s retry=$retryOnConnectionFailure ua=${userAgent != null}",
      )
    }
    return builder.build()
  }
}
