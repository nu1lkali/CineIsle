package app.marlboroadvance.mpvex.domain.iso

import kotlinx.coroutines.runBlocking

/**
 * 远程 ISO 播放的**编排层**：把「探测直链 → 解析 ISO → 注册本地代理」串成一步。
 *
 * ## 为什么要单独一层
 *
 * 上游（`EmbyViewModel`）只关心「这个 item 该用哪个 URL 播」，
 * 底层（[RemoteIsoParser] / [HttpRangeReader] / [IsoRangeProxy]）各自只管一段。
 * 中间这段编排有三条不能弄错的规矩，集中写在这里，别散到调用点去：
 *
 * 1. **探测与下载必须用同一个 UA**。CDN 签名把 UA 算进去时，
 *    探测用 A 套、下载用 B 套 ⇒ 403，而现象是「解析成功、播放必失败」。
 * 2. **探测失败必须回退，不能拦路**。远程 ISO 是增强功能，
 *    不是前置条件：解析不出来就返回 null，让调用方走原来的普通播放，
 *    用户至少还能看到条目、看到详情页。
 * 3. **探测分两步，别合并**。先用 [RemoteIsoParser.looksLikeIso] 只读 2KB 的 PVD
 *    判断是不是 ISO，不合格立刻返回 —— 别对每个普通视频都发一串探测请求。
 *
 * ## 路由清理不需要这里操心
 *
 * 注册出去的路由由 [IsoRangeProxy] 按「多久没人用」（30 分钟）自动回收。
 * 调用点**不要**自己管释放：播放器退出路径太多（正常返回 / 切内核 / 连播下一集 /
 * 划掉任务），在每个 `onDestroy` 挂钩子必漏一处。
 *
 * ## 用法
 *
 * 只有一条触发路径：**正常 Emby 条目**。
 *
 * ```kotlin
 * // 正常条目：先普通解析直链，探测不成就照常用直链
 * val direct = resolveDirectUrl(server, itemId)
 * val iso = direct?.let { RemoteIsoPlayback.prepareFromDirectUrl(it) { resolveDirectUrl(server, itemId) } }
 * launchSingle(server, item, resumeSeconds, overrideUrl = iso?.playbackUrl ?: direct)
 * ```
 *
 * ⚠️ **不要**给「库里指向远程 ISO 的 strm」单独开一条路。
 * 那类条目 Id 是 URL 本身、没有有效 ItemId，是 Emby 把文件内容当条目名扫出来的产物
 * （名字会长成 `http:` 这样），与远程 ISO 播放是**两件不相干的事**。
 * 详见 `EmbyLibraryScreen.isGhostItem` 的 KDoc。
 */
// internal 而不是 public：对外暴露的类型（[Prepared]）里有 internal 的
  // RemoteIsoParser 类型，写成 public 会被 Kotlin 拒（public 不得暴露 internal 类型）。
internal object RemoteIsoPlayback {

  /**
   * 一次探测的结果。
   *
   * @param playbackUrl 交给播放器的地址 —— 是本地代理地址，**不要**再对它做 302 解析。
   * @param kind 识别出的镜像类型，仅用于日志与后续可能的扩展（菜单/章节）。
   * @param track 主影片在 ISO 内的区间，排查问题时用得上。
   */
  data class Prepared(
    val playbackUrl: String,
    val kind: RemoteIsoParser.IsoKind,
    val track: RemoteIsoParser.MainTrack,
    val directUrl: String,
  )

  /**
   * 已经拿到直链时的入口：探测是不是 ISO，是就起本地代理。
   *
   * 只服务**正常 Emby 条目**：它们有有效 ItemId，所以直链过期时能通过
   * [onUrlExpired] 重新 302 一次，一部两小时的电影中途链接过期也不断。
   *
   * 全程阻塞在调用方的 IO 线程上（调用方包 `withContext(Dispatchers.IO)`），
   * **不抛异常**，任何一步失败都返回 null 让调用方回退普通播放。
   *
   * @param onUrlExpired 直链过期时重新解析。**suspend**，因为要走网络；
   *   [IsoRangeProxy] 那边会用 `runBlocking` 包起来调用（它跑在 NanoHTTPD 的
   *   IO 线程上，本来就在等 CDN 回数据，阻塞这一次不改变线程性质）。
   */
  fun prepareFromDirectUrl(
    directUrl: String,
    onUrlExpired: (suspend () -> String?)? = null,
  ): Prepared? {
    // 只认 http(s)。file:// 之类交给本地 ISO 那套（PlayerUtils.isoPlayableUri）。
    if (!directUrl.startsWith("http://") && !directUrl.startsWith("https://")) return null

    val reader = HttpRangeReader(urlProvider = { directUrl }, userAgent = IsoRangeProxy.STREAM_UA)

    // 先花 2KB 验一下是不是 ISO（只读 PVD），不合格就别拉目录树了。
    if (!RemoteIsoParser.looksLikeIso(reader)) return null

    val track =
      runCatching { RemoteIsoParser.parseMainTrack(reader) }.getOrNull()
        ?: return null

    // 解析出来了才注册代理 —— 注册了就意味着有一条常驻 route，
    // 探测失败的条目不该留下垃圾。
    val renew = onUrlExpired
    val playbackUrl =
      IsoRangeProxy.getInstance().register(
        // 正片可能跨多个片段（蓝光分段是常态），所以传映射而不是单段 offset/length。
        map = SegmentMap(track.segments),
        directUrl = directUrl,
        mimeType = track.mimeType,
        // renew 是 suspend，代理那边同步回调 ⇒ 这里 runBlocking 包一层。
        onExhausted = { renew?.let { runBlocking { it() } } },
      )

    return Prepared(playbackUrl, track.kind, track, directUrl)
  }
}