package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.net.Uri
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.guessNetworkMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AList 原生 API 客户端（[app.marlboroadvance.mpvex.domain.network.NetworkProtocol.ALIST]）。
 *
 * ## 为什么放着 WebDAV 不用、单做一套
 *
 * 同一台 AList 服务器，WebDAV 与原生 API 的差别是数量级的：
 * - **列目录**：`POST /api/fs/list` 一个接口拿全目录（内部按每页 500 自动翻页补齐，
 *   见 [listInternal]），还白送每条的 `sign`（免认证直链签名）与 `thumb`；
 *   WebDAV 要一次 PROPFIND 再逐条取。
 * - **认证**：`POST /api/auth/login` 换一次 token，之后只带 `Authorization` 头。
 *   WebDAV 是 Basic，每请求都要解一次 Keystore 里的密码密文。
 * - **播放**：`fs/get` 给的 `raw_url` / 签名 `/d/` 直链可以直接丢给播放器，
 *   不用再走本地代理（WebDAV 必须过 [app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy]）。
 *
 * 契约实证来自 `D:\project\AlistClientN`（Flutter 版 AList 客户端）：
 * `lib/screen/login_screen.dart#_login`（登录）、`lib/entity/file_list_resp_entity.dart`
 * （列目录）、`lib/util/file_utils.dart#makeFileLink`（`/d/` 直链拼法）、
 * `lib/net/intercept.dart`（`Authorization` 是**裸 token**，不带 Bearer）。
 *
 * ## 连接管理（这一条是血泪教训，别改回去）
 *
 * 进程内**共用一个 [OkHttpClient]**（[sharedHttp]）。WebDavClient 那边是每次取流
 * 都 `OkHttpSardine()` 新建一个 —— 每个 Sardine 自带独立连接池与线程池，等于每张
 * 封面一条全新 TCP 连接、keep-alive 完全不复用，叠加错误路径上没关的响应体，连接
 * 与 fd 堆到耗尽就是「前 30 个文件夹正常、后面全挂」。
 *
 * 配套铁律：**response body 在所有出口都必须关闭**。成功路径交给调用方关流
 * （Coil 那边 okio 会传递关闭），错误路径在这里 [Response.close] —— 否则连接会一直
 * 挂在池里占着不放。
 *
 * ## ⚠️ 取文件必须带签名，`Authorization` 头在下载口上**完全无效**
 *
 * 这是「目录能正常列出来、但所有图片/封面全加载不出来」的根因，别再改回去。
 *
 * AList 服务端的下载路由是（源码 `server/router.go`）：
 * ```
 * g.GET("/d/<wildcard-path>", middlewares.Down(sign.Verify), downloadLimiter, handles.Down)
 * ```
 * ⚠️ 上面把 Gin 的通配段写成 `<wildcard-path>` 而不是原样抄：原样写会在这里制造
 *    「斜杠 + 星号」两个字符，而 **Kotlin 的块注释可以嵌套** —— 那两字符等于又开了
 *    一层块注释，会把整个类体吞进去，报错却是「文件末尾 Unclosed comment」，
 *    排查半天。写 KDoc 时别在注释正文里出现块注释起始符。
 * —— 注意它挂的是 `middlewares.Down`，**没有 `Auth`**。而 `middlewares.Down`
 * （源码 `server/middlewares/down.go`）只在 `needSign(...)` 为真时校验 `?sign=`，
 * 为真即：服务端开了「签名所有」（`conf.SignAll`）／该存储开了签名／目录设了密码。
 * 此时签名缺失或过期 → **401 直接 Abort**，请求里的 `Authorization` 根本没人读。
 *
 * 而 `/api/fs/list`（列目录）走的是 `middlewares.Auth`，照样只认 `Authorization`
 * —— 于是「目录能列、图片全挂」。见 [getFileStream] 的三级取值。
 */
class AlistClient(private val connection: NetworkConnection) : NetworkClient {
  companion object {
    private const val TAG = "AlistClient"

    /** AList 的 `code` 字段：200 = 成功；401 = token 失效需要重登 */
    private const val CODE_OK = 200

    /**
     * `fs/list` 每页条数 —— **必须是显式正数**，别写 0。
     *
     * AList 后端对 `per_page` 有归一化：`per_page <= 0` ⇒ **默认 200**，
     * `per_page > 500` ⇒ 截断成 500（官方 API 文档 `fs/list` 的归一化规则）。
     * 所以「per_page = 0 一次拿全」是个流传很广的误解：实际只拿前 200 条，
     * 大目录在界面上就只显示一部分。这里取服务端上限 500，配合翻页拉全。
     */
    private const val ALIST_PAGE_SIZE = 500

    /**
     * 翻页保护上限：500 × 50 = 25000 条。
     *
     * 用满即停并打日志，防的是「服务端 / 中间层忽略 page，每次都返回同一批」
     * 这类异常情形把列目录变成死循环（正常目录根本到不了这里）。
     */
    private const val MAX_LIST_PAGES = 50

    /**
     * 路径→签名 缓存的上限。超了**直接清空**而不是 LRU 淘汰 ——
     * 它只是「省掉一次 fs/get」的加速缓存，丢了最多多一个请求，
     * 不值得为它维护一套淘汰逻辑。
     */
    private const val SIGN_CACHE_CAP = 4000

    /**
     * 进程内共享的 HTTP 客户端。
     *
     * 所有 AList 连接共用一份连接池与线程池：切连接、反复进出目录都能复用
     * keep-alive 连接。⚠️ 绝不要改成每次请求 new —— 见类 KDoc。
     */
    @Volatile
    private var sharedHttp: OkHttpClient? = null

    private fun http(): OkHttpClient =
      sharedHttp ?: synchronized(this) {
        sharedHttp ?: OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          // 读超时是「两次读到字节之间」的上限，不是整段下载的上限；
          // 大图 / 大文件下载走的是这个流，给足 60s
          .readTimeout(60, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .build()
          .also { sharedHttp = it }
      }
  }

  private val json = Json { ignoreUnknownKeys = true }

  /** AList 有官方写接口（fs/remove、fs/rename、fs/mkdir），其余三个协议没有 */
  override val supportsWrite: Boolean
    get() = true

  /** 登录换来的 token；游客模式（用户名密码皆空）恒为 null */
  @Volatile
  private var token: String? = null

  @Volatile
  private var connected = false

  /**
   * 路径 → 签名 的进程内缓存（key = **相对路径**，与 [NetworkFile.path] 同一口径）。
   *
   * 每次 `fs/list` 服务端都会给每个条目带上 `sign`，顺手记一笔：之后取封面 / 取图
   * 就能直接拼出 `?sign=`，**省掉一次 `fs/get`**（一个 100 张图的目录就是 100 个请求）。
   * 取 [getFileMeta] 拿到的新签名同样会覆盖进来。
   *
   * ⚠️ 签名有时效，所以这里只当**加速缓存**用：取流失败就回落到 `fs/get`
   *   现换一个新鲜签名（见 [getFileStream]），再由 [failedSigns] 保证
   *   那个过期签名不会在下一次复活 —— 三者是一套，别单独改其中一个。
   */
  private val signCache = ConcurrentHashMap<String, String>()

  /**
   * **已被证伪**的签名（key = 相对路径，与 [signCache] 同口径；value = 失败过的那个签名）。
   *
   * ⭐⭐ 这一张小表是「**点重载刷不出来、下拉刷新却能刷出来**」的根因解药，
   *     删掉它这个 bug 立刻回来。完整推导见 [getFileStream] 的 KDoc，这里只说结论：
   *
   * 签名会过期。过期后 `/d/?sign=` 就是 401，取流只能靠 `fs/get` 兜底 ——
   * 而 `fs/get` 对**同一个文件**返回的是**同一个**结果。如果那个结果本身取不到，
   * 「重载」只不过是把同一串字节再请求一遍：Error → Error，界面毫无变化。
   *
   * 更阴的是：调用方（Coil 的 Fetcher）手里攥着的是**列目录那一刻**的旧签名，
   * 它在 [getFileStream] 的第 ① 级里**优先级最高** —— 于是 `fs/get` 辛辛苦苦
   * 换来的新签名（被 [rememberSign] 写进了 [signCache]）永远排不上号，
   * 旧签名每一次都从第 ① 级满血复活。
   *
   * 所以：失败过的签名必须记下来、下一次**直接跳过**。
   *
   * ⚠️ 一个路径只记「最近失败的那一个」：同一路径极少同时存在两个有效签名，
   *    记一个就够阻断复活，不值得做成集合。来了新签名（[rememberSign]）立刻作废。
   */
  private val failedSigns = ConcurrentHashMap<String, String>()

  /** 缓存键：统一去掉首尾斜杠，`/电影/a.jpg` 与 `电影/a.jpg` 视为同一条 */
  private fun keyOf(path: String): String = path.trim('/')

  /**
   * 记一笔签名（空值不记）。写缓存顺手做容量保护：超限直接清空，见 [SIGN_CACHE_CAP]。
   *
   * ⭐ **来了一个新签名，就把这个路径的「已证伪」记录作废**（[failedSigns]）——
   *    这是整条链能自愈的关键：`fs/get` 换到新签名 ⇒ 记录清掉 ⇒
   *    下一次取流第 ① 级就能用新签名直连，不必再走一趟 `fs/get`。
   *    （`fs/list` 刷新列表时同理，一批新签名让整张表全部复位。）
   */
  private fun rememberSign(path: String, sign: String?) {
    if (sign.isNullOrBlank()) return
    val key = keyOf(path)
    if (signCache.size > SIGN_CACHE_CAP) {
      signCache.clear()
      failedSigns.clear()
    }
    if (signCache.put(key, sign) != sign) failedSigns.remove(key)
  }

  // ── URL 拼装 ──

  /** `http(s)://host:port/` —— 一定带结尾斜杠 */
  private fun serverRoot(): String {
    val protocol = if (connection.useHttps) "https" else "http"
    return "$protocol://${connection.host}:${connection.port}/"
  }

  /** `.../api/<endpoint>` */
  private fun api(endpoint: String): String = "${serverRoot()}api/$endpoint"

  /**
   * 内部记账的路径 → 交给 fs 系列接口（fs/list、fs/get）的路径。
   *
   * 我们的 path 一律相对 [NetworkConnection.path]（AList 的 base_path），
   * 与 WebDavClient 保持同一套约定。
   */
  private fun fsPath(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) "/" else "/$trimmed"
  }

  /**
   * `/d/<base path>/<path>?sign=<sign>` —— AList 的下载直链。
   *
   * 拼法照抄参考项目的 `_pathEncodeFull`：**按 `/` 切段、逐段编码、再拼回去**。
   * 整段丢给 [Uri.encode] 会把段内的 `/` 一起编掉，路径就废了。
   *
   * @param sign 有就拼上，这样这条链接**不带凭据也能取**（播放器 / 外部应用要用）。
   */
  private fun directUrl(path: String, sign: String?): String {
    val base = connection.path.trim('/')
    val rel = path.trim('/')
    val segments = buildList {
      if (base.isNotEmpty()) addAll(base.split('/').filter { it.isNotEmpty() })
      if (rel.isNotEmpty()) addAll(rel.split('/').filter { it.isNotEmpty() })
    }
    val encoded = segments.joinToString("/") { Uri.encode(it) }
    val url = "${serverRoot()}d/$encoded"
    return if (!sign.isNullOrBlank()) "$url?sign=$sign" else url
  }

  // ── NetworkClient ──

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        token = null
        // 用户名与密码都空 = 游客模式（参考项目的 guest 分支）：不登录，直接列目录探活
        if (connection.username.isNotBlank() || connection.password.isNotBlank()) {
          login()
        }
        // 探活：凭据 / base path 任一不对，这里就会 401 或 404 掉
        listInternal("/")
        connected = true
      }.onFailure { e ->
        connected = false
        Log.w(TAG, "connect failed: ${connection.name}", e)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      token = null
      connected = false
      // 断开 = 这一轮的会话 / 签名全部作废，别把旧签名留给下一次连接
      signCache.clear()
      failedSigns.clear()
    }
  }

  override fun isConnected(): Boolean = connected

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      runCatching { listInternal(path) }
    }

  /**
   * 取文件流（网格缩略图 / 全屏大图 / 保存原图都走这里）。
   *
   * ## 取值顺序（每一级都可能救命，别删）
   *
   * 1. **`/d/` 直链 + 已知签名** —— 调用方给的（`fs/list` 现场拿的）优先，
   *    进程内签名缓存（[signCache]）其次。零额外请求，正常路径。
   * 2. **`fs/get`（AList 的 API）** 现换一个签名 / `raw_url`。
   *    ⚠️ 它给出的地址有**两个**，必须都试：`raw_url` 是对象存储直链，
   *    `sign` 是服务端现算的下载签名。以前只试 `raw_url`，它一坏整条链就断在这。
   *
   * ## ⭐⭐ 为什么「重载」刷不出来、下拉刷新却能刷出来（本方法最重要的一条）
   *
   * 真机反馈原话：「偶尔有几个没刷出封面图，然后重载也刷不出来」，
   * 而「正屏下拉刷新能刷出来」。三个现象串起来，根因就在这个方法里：
   *
   * - 签名**有时效**。过期后第 1 级的 `/d/?sign=` 就是 401，只能靠 `fs/get` 兜底。
   * - 而 `fs/get` 对**同一个文件**返回的是**同一个**结果。如果这个结果本身
   *   取不到（`raw_url` 过期 / 指向服务端内网地址 / 新签名也没用），
   *   那么「重载」只不过是把同一串字节**再请求一遍**：
   *   状态 Error → Error，界面上就是「点了没反应」。
   * - **下拉刷新**不一样：它重新 `fs/list`，服务端给一批**全新的签名**，
   *   第 1 级直接成功。所以「下拉刷新能刷出来、重载刷不出来」——
   *   差别不在缓存，在**签名新不新**。
   *
   * 雪上加霜的一脚：调用方（Coil 的 Fetcher）手里攥着的是**列目录那一刻**的
   * 旧签名，它在第 1 级里优先级最高 —— `fs/get` 换来的新签名被
   * [rememberSign] 写进了 [signCache]，却永远排不上号，旧签名每一次都满血复活。
   *
   * 解药两半，缺一不可：
   * - **[failedSigns]**：失败过的签名记下来，下一次**跳过它**，别再复活；
   * - 第 2 级把 `raw_url` 和 `/d/?sign=<新签名>` **都试一遍**。
   *
   * 两条都上了之后，链条是自愈的：失败 → 跳过旧签名 → `fs/get` 换新签名 →
   * 成功 → [rememberSign] 作废证伪记录 → 之后又回到零额外请求的快路。
   *
   * ⚠️ 刻意**不**在成功路径上先调 `fs/get`：列目录已经把签名带回来了，
   *    取流就该是最后一跳；`fs/get` 只在失败时补。
   */
  override suspend fun getFileStream(path: String, sign: String?): Result<InputStream> =
    withContext(Dispatchers.IO) {
      try {
        val key = keyOf(path)

        // ① /d/ 直链（带上已知签名）。⚠️ 已证伪的签名必须跳过，见 KDoc。
        val failed = failedSigns[key]
        val known = listOfNotNull(
          sign?.takeIf { it.isNotBlank() },
          signCache[key],
        ).distinct().filter { it != failed }

        for (s in known) {
          val attempt = runCatching { openStream(directUrl(path, s)) }
          if (attempt.isSuccess) {
            failedSigns.remove(key)
            return@withContext Result.success(attempt.getOrThrow())
          }
          Log.w(TAG, "/d/ 直链取流失败: $path | ${attempt.exceptionOrNull()?.message}")
        }
        // 把失败的那个签名记下来。少了这一行，下一次（用户点「重载」）
        // 又会优先用它，而 fs/get 给的是同一个结果 ⇒ 重试永远撞同一堵墙。
        if (known.isNotEmpty()) {
          // 容量保护：这张表只按 path 记一条，超限直接清空（和 signCache 同口径）
          if (failedSigns.size > SIGN_CACHE_CAP) failedSigns.clear()
          failedSigns[key] = known.last()
        }

        // ② 兜底：fs/get —— 走 AList 的 API 现换一个签名 / raw_url。
        //    用户说「我是 alist 协议的，刷新应该要走 alist 的 api」，就是这一跳。
        Log.w(TAG, "改用 fs/get 换签名重试: $path")
        val meta = getFileMeta(path)
        val freshSign = meta.sign.takeIf { it.isNotBlank() }
        val urls = buildList {
          // raw_url：部分驱动直出对象存储直链，绕过 AList 更快
          if (!meta.isDir) meta.rawUrl.takeIf { it.isNotBlank() }?.let { add(it) }
          // ⚠️ 但 raw_url 也会过期 / 指错（典型是服务端自己的内网地址）——
          //    新签名这条必须也试。以前漏了它，raw_url 一坏整条链就断在这里，
          //    而且重载也救不回来（fs/get 每次返回同一个坏 raw_url）。
          add(directUrl(path, freshSign))
        }.distinct()

        var lastError: Throwable? = null
        for (url in urls) {
          val attempt = runCatching { openStream(url) }
          if (attempt.isSuccess) {
            failedSigns.remove(key)
            return@withContext Result.success(attempt.getOrThrow())
          }
          lastError = attempt.exceptionOrNull()
          Log.w(TAG, "fs/get 给出的地址取流失败: $url | ${lastError?.message}")
        }
        throw lastError ?: IOException("fs/get 没给出可用地址: $path")
      } catch (e: Exception) {
        Log.w(TAG, "取流失败: $path", e)
        Result.failure(e)
      }
    }

  /**
   * GET 一个取流地址，返回字节流。
   *
   * 两道防线，缺一不可：
   * 1. **非 2xx 一律抛**（[execute] 兜住）—— AList 缺签名时就是 401；
   * 2. **`text/html` 也抛** —— AList 的静态兜底会把没匹配上的路径渲染成前端页面
   *    （HTTP 200 + `index.html`）。放它过去 Coil 只会在解码阶段失败，症状是
   *    「图片全挂、却一条错都没有」，比直接失败难查得多。
   *
   * ⚠️ 成功路径**不关**流：它交给调用方（Coil 的 okio 会传递关闭）。
   */
  private fun openStream(url: String): InputStream {
    val response =
      execute(
        Request.Builder()
          .url(url)
          .get()
          // 下载口不读这个头（见类 KDoc），带上纯属无害 + 兼容非标准部署
          .apply { authHeader()?.let { header("Authorization", it) } }
          .build(),
      )
    val contentType = response.header("Content-Type").orEmpty()
    if (contentType.contains("text/html", ignoreCase = true)) {
      response.close()
      throw IOException("AList 返回的是网页而不是文件（下载路径没生效）: $url")
    }
    return response.body?.byteStream()
      ?: run {
        response.close()
        throw IOException("AList 返回空响应体: $url")
      }
  }

  /**
   * 给播放器的直链。
   *
   * 优先用 `fs/get` 的 `raw_url`（部分驱动直出对象存储直链，绕过 AList 更快），
   * 没有就用带 `sign` 的 `/d/` 链接 —— 两者都**不需要鉴权头**，播放器可以直连，
   * 不用起本地代理。
   */
  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      runCatching {
        val meta = runCatching { getFileMeta(path) }.getOrNull()
        val rawUrl = meta?.rawUrl?.takeIf { it.isNotBlank() }
        val url = rawUrl ?: directUrl(path, meta?.sign?.takeIf { it.isNotBlank() })
        Uri.parse(url)
      }
    }

  /**
   * 文件详情（[getFileUri] 与将来的直链下载都要用）。
   *
   * [NetworkFile.sign] 就出自这里与 `fs/list`：签名有时效，取一次用一次，别长期缓存。
   */
  suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      runCatching { getFileMeta(path).size }
    }

  // ── 写操作（AList 官方 API） ──
  //
  // 契约实证自 `D:\project\AlistClientN` 的请求实体：
  //   `lib/entity/file_remove_req.dart`  → {dir, names[]}
  //   `lib/entity/file_rename_req.dart`  → {path, name}
  //   `lib/entity/mkdir_req.dart`        → {path}
  // SMB / FTP / WebDAV 三个客户端都没实现写操作，所以这些只挂在 AlistClient 上，
  // 由 [NetworkClient.supportsWrite] 向 UI 声明「我支持」。

  /**
   * `POST /api/fs/remove`：删除。
   *
   * ⚠️ **不可逆**（无回收站），调用方必须先在 UI 上二次确认。
   *
   * @param dir 父目录（相对 base path，如 `/电影`）
   * @param names 要删的**文件名**列表（不是完整路径，也不是目录）
   */
  suspend fun remove(dir: String, names: List<String>): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        require(names.isNotEmpty()) { "没有要删除的文件" }
        withAuthRetry {
          postJsonChecked(
            endpoint = "fs/remove",
            body = buildJsonObject {
              put("dir", fsPath(dir))
              putJsonArray("names") { names.forEach { add(it) } }
            },
          )
        }
        Unit
      }
    }

  /** `POST /api/fs/rename`：重命名。`path` 是完整路径，`name` 是**新文件名** */
  suspend fun rename(path: String, newName: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "名称不能为空" }
        require(trimmed.none { it == '/' }) { "名称不能包含 /" }
        withAuthRetry {
          postJsonChecked(
            endpoint = "fs/rename",
            body = buildJsonObject {
              put("path", fsPath(path))
              put("name", trimmed)
            },
          )
        }
        Unit
      }
    }

  /**
   * `POST /api/fs/mkdir`：新建文件夹。
   *
   * @param path 待建目录的**完整路径**（如 `/电影/新文件夹`）
   */
  suspend fun mkdir(path: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        withAuthRetry {
          postJsonChecked(
            endpoint = "fs/mkdir",
            body = buildJsonObject { put("path", fsPath(path)) },
          )
        }
        Unit
      }
    }

  // ── 接口调用 ──

  /**
   * `POST /api/auth/login`。
   *
   * ⚠️ 这个请求**不能**带 `Authorization`（此时还没有 token，带了也是过期的脏值）。
   */
  private fun login(): String {
    val raw = postJson(
      endpoint = "auth/login",
      body = buildJsonObject {
        put("username", connection.username)
        put("password", connection.password)
        put("otp_code", "")
      },
      withAuth = false,
    )
    val parsed = json.decodeFromString<AlistResp<AlistLoginData>>(raw)
    if (parsed.code != CODE_OK) {
      throw AlistApiException(parsed.code, "AList 登录失败：${parsed.message}")
    }
    val newToken = parsed.data?.token.orEmpty()
    if (newToken.isBlank()) throw IOException("AList 登录成功但没返回 token")
    token = newToken
    return newToken
  }

  /** `POST /api/fs/get` */
  private fun getFileMeta(path: String): AlistFsGet =
    withAuthRetry {
      val raw = postJson(
        endpoint = "fs/get",
        body = buildJsonObject {
          put("path", fsPath(path))
          put("password", "")
        },
        withAuth = true,
      )
      val parsed = json.decodeFromString<AlistResp<AlistFsGet>>(raw)
      if (parsed.code != CODE_OK) throw AlistApiException(parsed.code, parsed.message)
      val data = parsed.data ?: throw IOException("AList fs/get 没返回 data: $path")
      // 新签名覆盖旧值（签名有时效，越新越好）
      rememberSign(path, data.sign)
      data
    }

  /** `POST /api/fs/list` —— 自动翻页，把这个目录**全部**条目拉回来 */
  private fun listInternal(path: String): List<NetworkFile> =
    withAuthRetry {
      // ⚠️ 为什么要翻页（踩过的坑，别再改回 `per_page = 0` 单次请求）：
      //    AList 后端对 per_page 有归一化 —— `per_page <= 0` 会被替换成**默认 200**，
      //    `per_page > 500` 会被**截断成 500**（见官方 API 文档 `fs/list` 的说明）。
      //    也就是说 `per_page: 0` 从来不是「一次拿全」，而是「只拿前 200 条」；
      //    大目录（番剧一季、照片文件夹）在界面上就只显示一部分，而且**没有任何提示**。
      //    正确做法是每页 500（服务端上限）+ 按 `total` / `has_more` 翻到收齐。
      val merged = LinkedHashMap<String, NetworkFile>()
      val parent = path.trim('/')
      var page = 1
      while (true) {
        val chunk = listPage(path, page)
        var added = 0
        chunk.items.forEach { item ->
          val name = item.name
          if (name.isBlank()) return@forEach
          val file =
            NetworkFile(
              name = name,
              path = if (parent.isEmpty()) name else "$parent/$name",
              size = item.size,
              isDirectory = item.isDir,
              lastModified = parseModified(item.modified),
              mimeType = if (item.isDir) null else guessNetworkMimeType(name),
              sign = item.sign.takeIf { it.isNotBlank() },
            )
          if (merged.put(name, file) == null) added++
          // 顺手把签名记进缓存：照片取流就是最后一跳，不必再为它发一次 fs/get
          rememberSign(file.path, file.sign)
        }
        // 停下来的四种情形：本页空 / 服务端忽略了 page 返回同一批 / 已经收齐 / 到达保护上限
        if (chunk.items.isEmpty() || added == 0) break
        if (!chunk.hasMore) break
        if (page >= MAX_LIST_PAGES) {
          Log.w(TAG, "目录条目超过 ${MAX_LIST_PAGES * ALIST_PAGE_SIZE} 条，只取了前 $page 页：$path")
          break
        }
        page++
      }
      merged.values.toList()
    }

  /** 拉取 `fs/list` 的**某一页**，并顺带判断「还有没有下一页」 */
  private fun listPage(path: String, page: Int): AlistPage =
    postJson(
      endpoint = "fs/list",
      // page / per_page 是**数字**、refresh 是**布尔** —— 写成字符串服务端会拒绝
      // （见 [postJson] 的说明）
      body = buildJsonObject {
        put("path", fsPath(path))
        put("password", "")
        put("page", page)
        put("per_page", ALIST_PAGE_SIZE)
        put("refresh", false)
      },
      withAuth = true,
    ).let { raw ->
      val parsed = json.decodeFromString<AlistResp<AlistFsListData>>(raw)
      if (parsed.code != CODE_OK) throw AlistApiException(parsed.code, parsed.message)
      val data = parsed.data
      val items = data?.content ?: emptyList()
      // 「还有下一页吗」的判据按可靠性排序，三个来源都要兜住：
      //  1. `has_more` —— v3.58.0 起才有，最准；
      //  2. `filtered_total` / `total` —— 老版本也有，但 `total` 在部分驱动上可能不准；
      //  3. 都没给（或恒为 0）时退回「满页即可能还有」——最坏情况只是多请求一次空页。
      val filteredTotal = data?.filteredTotal ?: 0
      val total = data?.total ?: 0
      val reportedTotal = if (filteredTotal > 0) filteredTotal else total
      val effectivePerPage = (data?.perPage ?: 0).takeIf { it > 0 } ?: ALIST_PAGE_SIZE
      val hasMoreField = data?.hasMore
      val hasMore =
        when {
          items.isEmpty() -> false
          hasMoreField != null -> hasMoreField
          reportedTotal > 0 -> page * effectivePerPage < reportedTotal
          else -> items.size >= effectivePerPage
        }
      AlistPage(items = items, hasMore = hasMore)
    }

  // ── HTTP 原语 ──

  /**
   * 执行请求；失败路径**一定**关掉响应体。
   *
   * ⚠️ 这条是「连接耗尽」的根因防线：响应体不关，底层连接就一直挂在连接池里
   *    不释放，几十个之后新请求全部拿不到连接。
   */
  private fun execute(request: Request): Response {
    val response = http().newCall(request).execute()
    if (!response.isSuccessful) {
      val code = response.code
      response.close()
      throw AlistApiException(code, "AList HTTP $code")
    }
    return response
  }

  /**
   * POST 一个 JSON body。
   *
   * ⚠️ **body 必须是 [JsonObject]，不能用 `Map<String, String>`**：
   * AList 的 Go 后端对字段类型是严的 —— `page` / `per_page` 要 uint64、
   * `refresh` 要 bool。用 Map<String, String> 会把它们全序列化成字符串，
   * 服务端直接报
   * `handles.ListReq. PageReq: readUint64: unexpected character`。
   * （真机踩过：错误日志里那句 `{"path":"/","password":"","page":"1","per_page":"0",...}`
   *   就是这么来的。）
   */
  private fun postJson(
    endpoint: String,
    body: JsonObject,
    withAuth: Boolean,
  ): String {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = body.toString().toRequestBody(mediaType)
    val request = Request.Builder()
      .url(api(endpoint))
      .post(requestBody)
      .apply { if (withAuth) authHeader()?.let { header("Authorization", it) } }
      .build()
    execute(request).use { response ->
      return response.body?.string() ?: throw IOException("AList 返回空响应体: $endpoint")
    }
  }

  /**
   * POST 并**校验业务码**。
   *
   * 列目录那几个是自己解析 `data` 的；写操作（remove / rename / mkdir）不需要
   * 返回内容，只要知道成没成 —— 统一走这里，免得某个写操作漏判 `code`
   * （HTTP 200 但 `code != 200` 是 AList 的常见失败形态，比如权限不足）。
   */
  private fun postJsonChecked(endpoint: String, body: JsonObject) {
    val raw = postJson(endpoint, body, withAuth = true)
    val parsed = json.decodeFromString<AlistSimpleResp>(raw)
    if (parsed.code != CODE_OK) {
      throw AlistApiException(
        parsed.code,
        parsed.message.ifBlank { "AList $endpoint 失败（code=${parsed.code}）" },
      )
    }
  }

  /** AList 的鉴权头是**裸 token**，不加 `Bearer`（参考项目 `intercept.dart` 实证） */
  private fun authHeader(): String? = token?.takeIf { it.isNotBlank() }

  /**
   * 401 = token 过期（AList 默认 48 小时）：重新登录一次再重试。
   * 只重试一次，避免服务端一直拒绝时陷入死循环。
   */
  private inline fun <T> withAuthRetry(block: () -> T): T =
    try {
      block()
    } catch (e: AlistApiException) {
      if (e.code != 401 || token == null) throw e
      Log.w(TAG, "token 失效，重新登录后重试一次")
      token = null
      if (connection.username.isNotBlank() || connection.password.isNotBlank()) {
        runCatching { login() }
      }
      block()
    }

  /**
   * AList 的 modified 是 RFC3339（可能不带时区），解不出来就当 0 ——
   * 最坏结果只是列表里这一行不显示时间，不值得为它抛异常。
   */
  private fun parseModified(raw: String): Long {
    if (raw.isBlank()) return 0L
    runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    // 没有时区后缀的按 UTC 处理（LocalDateTime 要先补时区才能转时间戳）
    runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }
      .getOrNull()?.let { return it }
    return 0L
  }
}

/** 服务端返回了非 200 的 `code`，或 HTTP 状态码不正常 */
private class AlistApiException(val code: Int, message: String) : IOException(message)

@Serializable
private data class AlistResp<T>(
  val code: Int = -1,
  val message: String = "",
  val data: T? = null,
)

@Serializable
private data class AlistLoginData(val token: String = "")

/** `fs/list` 的条目。`related` 可能是对象也可能是 null —— 用不到，靠 ignoreUnknownKeys 丢掉 */
@Serializable
private data class AlistFsItem(
  val name: String = "",
  val size: Long = 0,
  @SerialName("is_dir") val isDir: Boolean = false,
  val modified: String = "",
  val sign: String = "",
  val thumb: String = "",
  val type: Int = 0,
)

@Serializable
private data class AlistFsListData(
  val content: List<AlistFsItem>? = null,
  val total: Int = 0,
  /** v3.58.0+：**过滤后**（当前用户实际可见）的总数，比 [total] 更贴近真实条数 */
  @SerialName("filtered_total") val filteredTotal: Int = 0,
  /** v3.58.0+：服务端归一化之后的当前页码 / 每页条数 */
  val page: Int = 0,
  @SerialName("per_page") val perPage: Int = 0,
  /**
   * v3.58.0+：是否还有下一页。
   *
   * ⚠️ 必须可空：老版本根本没有这个字段，解析成 `null` 才能和「明确 false」
   * 区分开 —— 否则老服务端会被当成「没有下一页」，翻页在第一页就停住。
   */
  @SerialName("has_more") val hasMore: Boolean? = null,
  @SerialName("pages_total") val pagesTotal: Int = 0,
  val readme: String = "",
  val write: Boolean = false,
  val provider: String = "",
)

/** `fs/list` 的一页：条目 + 「还有下一页吗」（见 [AlistClient.listPage] 的判据） */
private data class AlistPage(
  val items: List<AlistFsItem>,
  val hasMore: Boolean,
)

/**
 * 只关心 `code` / `message` 的响应 —— 写操作（remove / rename / mkdir）用，
 * 见 [AlistClient.postJsonChecked]。
 */
@Serializable
private data class AlistSimpleResp(
  val code: Int = -1,
  val message: String = "",
)

/** `fs/get` 的 data。比 [AlistFsItem] 多了 `raw_url`（播放直链用的就是它） */
@Serializable
private data class AlistFsGet(
  val name: String = "",
  val size: Long = 0,
  @SerialName("is_dir") val isDir: Boolean = false,
  val modified: String = "",
  val sign: String = "",
  val thumb: String = "",
  val type: Int = 0,
  @SerialName("raw_url") val rawUrl: String = "",
)
