package app.marlboroadvance.mpvex.domain.emby

import android.os.Looper
import android.util.Log
import app.marlboroadvance.mpvex.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全App 共享的 OkHttp 基础设施（对应清单 1.3「网络层收口」）。
 *
 * ## 为什么要抽这个
 *
 * 原来项目里有 **6 个各自 `OkHttpClient.Builder()` 造出来的客户端**
 * （[EmbyClient] / `EmbyDownloadManager` / `EmbyImageLoader` / `PlayerActivity` /
 * `NetworkStreamingProxy` / `UpdateFeature`），后果是：
 *
 * - **连接池不共享** —— 6 套连接、6 套线程池，反复进出页面就在建/毁 TCP 连接；
 * - **token 注入靠手写** —— `X-Emby-Token` 与 `?api_key=` 在 `EmbyClient` 里各写了 6 处；
 * - **重试 / 超时 / 日志各不相同** —— 同一个请求在不同入口表现不一样；
 * - **401 只能靠上层手动 `ensureLoggedIn` 补**，token 过期时用户看到的是
 *   「筛选面板空着」这种莫名其妙的表现。
 *
 * ## 现在
 *
 * 统一到这里，按 [shared]（默认策略，全 App 通用） / [streaming]（长连接、大超时）
 * / [images]（图片，超时短、并发高）三种档位派生。派生实例**共用同一个连接池**
 * （[connectionPool]）与线程池，所以连接是真的复用的，不是各建各的。
 */
object EmbyHttp {

  private const val TAG = "EmbyHttp"

  /**
   * 全 App 共享的连接池。
   *
   * 关键点：所有派生客户端都传入**同一个** [okhttp3.ConnectionPool] 实例，
   * 否则「共享」只是嘴上说说 —— OkHttp 的连接复用完全由它决定。
   * 5 个空闲连接、5 分钟存活是 OkHttp 默认值，这里显式写出来便于调。
   */
  private val connectionPool = okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)

  /**
   * 统一的 UA。
   *
   * ⚠️ **拉流必须与 [EmbyClient.STREAM_USER_AGENT] 保持一致**：网盘直链的签名常把UA
   * 算进校验，探测与真正拉流两边不一致时会「探测能过、播放被 403」
   * （这个坑踩过，见 player.md）。
   */
  private const val USER_AGENT = EmbyClient.STREAM_USER_AGENT

  /**
   * 401 自动重登回调。
   *
   * 由 [EmbyClient] 在登录成功后注入：**只有真正发起过请求才可能收到 401**，
   * 而重登动作放在这里（而不是每个调用点）才能一处收口。
   */
  @Volatile
  var onUnauthorized: (() -> Unit)? = null

  /**
   * 全 App 默认客户端：连接池共享、token 由 [authInterceptor] 注入、401 自动重登一次。
   *
   * [authInterceptor] 只是把 token 塞进 header；真正需要 `?api_key=` 的场景
   * （要拼完整 URL 交给播放器 / 图片组件的那几处）仍走
   * [EmbyClient.authedRequestWithApiKey]，别混。
   */
  val shared: OkHttpClient by lazy { baseBuilder().build() }

  /** 拉流档：连接 / 读超时都放宽（长视频、慢网盘直链）。仍与 [shared] 共享连接池。 */
  val streaming: OkHttpClient by lazy {
    baseBuilder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .build()
  }

  /** 图片档：超时短（列表里等不起）、并发多。 */
  val images: OkHttpClient by lazy {
    baseBuilder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  /**
   * 探活档：**不带重试**、超时压到秒级。
   *
   * 用途只有一个：判断「这台服务器此刻通不通」（主备切换的判据，见 [EmbyFailoverController]）。
   * 不能复用 [shared] —— 它的重试拦截器会给一次失败补 0.5s + 1s 的退避再试两轮，
   * 一次 3 秒的探活会被拖成 10 秒以上，探活自己就成了"故障"。
   */
  val health: OkHttpClient by lazy {
    baseBuilder(withRetry = false)
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(4, TimeUnit.SECONDS)
      .callTimeout(6, TimeUnit.SECONDS)
      .build()
  }

  /**
   * 请求最终失败的回报钩子（只在 [shared] 那档生效）。
   *
   * 主备切换要的是「用户正在用的时候到底连不连得上」，而不是定时探活的结论 ——
   * 所以判据放在真实请求链路上，省掉一大堆心跳流量。
   *
   * ⚠️ 回调发生在 OkHttp 的工作线程上，**必须立刻返回**：只做计数 / 起协程，
   * 别在这里做任何网络或阻塞操作。
   */
  @Volatile
  var onTransientFailure: ((host: String, port: Int) -> Unit)? = null

  /**
   * 三个档位共同的 builder。
   *
   * ⚠️ 顺序有意义：重试拦截器放在**最后** add，才是最外层 ——
   * 这样它能看到最终响应（含 401）并决定是否重试。
   *
   * @param withRetry 探活档传 false：它要的是「一次就说清通不通」，重试会污染结论。
   */
  private fun baseBuilder(withRetry: Boolean = true): OkHttpClient.Builder {
    val builder =
      OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(userAgentInterceptor)
    if (withRetry) builder.addInterceptor(retryInterceptor)
    return builder
  }

  /** 统一 UA。 */
  private val userAgentInterceptor =
    Interceptor { chain ->
      val req = chain.request()
      // 已经显式带 UA 的（例如按服务器来源拼的 Referer/UA 组）就不覆盖
      if (req.header("User-Agent") != null) {
        chain.proceed(req)
      } else {
        chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
      }
    }

  /**
   * 指数退避重试 + 401 自动重登一次。
   *
   * 只重试**明确值得重试**的失败，避免把「服务器真的拒绝了」也重一遍：
   * - 连接失败 / 超时（`IOException`）：退避 0.5s → 1s 重试；
   * - 5xx、429：退避重试（服务端临时过载 / 限流）；
   * - **401**：先给 [onUnauthorized] 一次重登机会，重登后重放**一次**原请求。
   *
   * ⚠️ 401 只重试一次 —— 重登还没成功就别无限循环了，那只会把服务器打挂。
   * ⚠️ 请求体能否重放取决于能不能重读，所以 body 非空时**不再重试**
   * （OkHttp 的 `oneShot` 标记就是干这个的），只对 GET 这类安全方法重试。
   */
  private val retryInterceptor =
    Interceptor { chain ->
      val req = chain.request()
      if (req.body?.isOneShot() == true) return@Interceptor chain.proceed(req)

      var lastErr: IOException? = null
      repeat(MAX_ATTEMPTS) { attempt ->
        try {
          val resp = chain.proceed(req)
          if (resp.code == 401 && attempt == 0) {
            // token 过期：先收掉这次响应（不关会泄漏连接），再试着重登
            resp.close()
            val relogin = onUnauthorized
            if (relogin != null) {
              Log.i(TAG, "收到 401，尝试自动重登后重放请求：${req.url.encodedPath}")
              relogin()
              val retryReq = req.newBuilder().header("X-Emby-Token", currentToken()).build()
              return@Interceptor chain.proceed(retryReq)
            }
            return@Interceptor chain.proceed(req)
          }
          if (shouldRetryCode(resp.code) && attempt < MAX_ATTEMPTS - 1) {
            resp.close()
            sleepBackoff(attempt)
            return@repeat
          }
          // 5xx / 429 重试用尽后依然是这个码：对主备切换来说这就等于「这台不可用」
          if (shouldRetryCode(resp.code)) notifyTransientFailure(req)
          return@Interceptor resp
        } catch (e: IOException) {
          lastErr = e
          if (attempt >= MAX_ATTEMPTS - 1) {
            notifyTransientFailure(req)
            throw e
          }
          Log.w(TAG, "请求失败（${e.message}），第 ${attempt + 1} 次重试", e)
          sleepBackoff(attempt)
        }
      }
      throw lastErr ?: IOException("请求失败")
    }

  /** 退避基数（毫秒）：0.5s → 1s → … */
  private const val BACKOFF_BASE_MS = 500L

  /** 最多尝试次数（含首次） */
  private const val MAX_ATTEMPTS = 3

  /** 这些状态码值得重试：服务端临时过载 / 限流 */
  private fun shouldRetryCode(code: Int): Boolean = code in 500..599 || code == 429

  /**
   * 把「这次真的失败了」报给上层（主备切换用）。
   *
   * 吞掉回调里的一切异常：切换逻辑出问题绝不能反过来把请求本身搞挂。
   */
  private fun notifyTransientFailure(req: Request) {
    val cb = onTransientFailure ?: return
    if (!isServerHealthEvidence(req.url.encodedPath)) return
    runCatching { cb(req.url.host, req.url.port) }
  }

  /**
   * 这次失败能不能当成「服务器不可用」的证据。
   *
   * 图片 / 拉流必须排除 —— 它们的失败有一大半跟服务器本身无关：
   * strm 的真实媒体在网盘 CDN 上（403 / 限流 / 单张图坏掉都很常见），
   * 拿它们当判据会让好好的主服务器被误判成挂了。
   */
  private fun isServerHealthEvidence(path: String): Boolean {
    if (!path.startsWith("/emby/")) return false
    if (path.contains("/Images/")) return false
    if (path.startsWith("/emby/Videos/")) return false
    if (path.startsWith("/emby/Audio/")) return false
    return true
  }

  /**
   * 指数退避的阻塞等待。
   *
   * ⚠️ 这里会`Thread.sleep`，所以**调用方绝不能是主线程**（清单 #20 建议项）。
   *
   * 为什么值得写死这个约束：它是 OkHttp `Interceptor.intercept` 里调的，
   * 而 OkHttp 的同步 `execute()` 由调用线程直接跑 —— 如果哪天某个 ViewModel
   * 在主线程上调了同步 execute（很常见的一时疏忽），退避最坏要睡
   * 0.5 + 1 = 1.5 秒两次共 3 秒，**必然 ANR**。
   * 断言的价值是把这种错误在第一次发生时就打出来，而不是靠线上 ANR 报告去反查。
   *
   * 只在 debug 生效：release 里 `Looper.getMainLooper()` 这类检查纯属浪费。
   * 也不抛异常 —— 万一真有人在主线程调了，让它照常睡完即可，
   * 已经有Log 了，没必要把功能打断。
   */
  private fun sleepBackoff(attempt: Int) {
    if (BuildConfig.DEBUG && Looper.myLooper() === Looper.getMainLooper()) {
      Log.e(
        TAG,
   "sleepBackoff 在主线程被调用（attempt=$attempt）。" +
    "这意味着某个同步 execute() 跑在了 UI 线程，退避会直接卡住界面最多 3 秒。" +
     "请把该调用包进 withContext(Dispatchers.IO)。",
      )
    }
    try {
      Thread.sleep(BACKOFF_BASE_MS shl attempt)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  /**
   * 当前 token。由 [EmbyClient] 在登录 / 换服务器时写入 [tokenHolder]。
   *
   * 单独放一个holder 是为了让本对象不必依赖 [EmbyClient] 的初始化顺序
   * （`EmbyClient` 初始化时又会用到这里的 client，容易互相卡住）。
   */
  @Volatile
  private var tokenHolder: () -> String = { "" }

  /** [EmbyClient] 注入：当前该用哪个 token。重登后重放请求时要用新 token。 */
  fun installTokenSource(source: () -> String) {
    tokenHolder = source
  }

  private fun currentToken(): String = tokenHolder()
}
