package app.marlboroadvance.mpvex.domain.emby

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 一台服务器的**一个**入口地址。
 *
 * 为什么单独抽出来：同一台 Emby 可能有两个入口（家里内网 `http://192.168.1.10:8096`、
 * 外面走 frp 的 `https://emby.example.com:443`）。注意 **scheme / 端口 / 主机三者是绑定的**
 * —— 内网通常是 http 明文 + 高位端口，公网是 https + 443。只换 host 不换 scheme
 * 会得到一个连不上的地址（`http://域名:443` 之类），所以打包成一个值对象。
 */
data class EmbyEndpoint(
  val host: String,
  val port: Int,
  val useHttps: Boolean,
) {
  val baseUrl: String
    get() = "${if (useHttps) "https" else "http"}://$host:$port"

  /** 同一台机器的判定：只比 host，端口/scheme 差异不算换机器 */
  fun sameHostAs(other: EmbyEndpoint): Boolean = host.equals(other.host, ignoreCase = true)
}

/** 服务器自身配置里的那个入口（Room 里的 host/port/useHttps） */
fun EmbyServer.primaryEndpoint(): EmbyEndpoint = EmbyEndpoint(host, port, useHttps)

/**
 * 第二地址的落盘。
 *
 * **刻意不放进 Room**：`emby_servers` 加列要走迁移，而这个库的迁移链末端挂了
 * `fallbackToDestructiveMigration(true)` —— 万一迁移写错就是**整库重建**，
 * 服务器配置、播放列表、播放进度全丢。为一个可选字段冒这个风险不值得。
 * 口径与 [EmbyFailoverController] 的 `emby_failover` 一致：独立偏好、零迁移。
 *
 * 存法：`alt_<serverId>` → `host|port|useHttps(0/1)`。服务器删除时要调 [forget]。
 */
class EmbyEndpointStore(context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * 可观察的副本。
   *
   * ⚠️ 别让 UI 直接调 [altFor]：那是普通函数读快照，写进去不触发重组
   * （v146 的「设完备用还显示未设置」就是这么来的）。UI 一律 collect 这个 flow。
   */
  private val _alts = MutableStateFlow(readAll())
  val alts: StateFlow<Map<Long, EmbyEndpoint>> = _alts.asStateFlow()

  fun altFor(serverId: Long): EmbyEndpoint? = _alts.value[serverId]

  fun setAlt(serverId: Long, endpoint: EmbyEndpoint?) {
    val editor = prefs.edit()
    if (endpoint == null || endpoint.host.isBlank()) {
      editor.remove(key(serverId))
    } else {
      editor.putString(
        key(serverId),
        "${endpoint.host}|${endpoint.port}|${if (endpoint.useHttps) 1 else 0}",
      )
    }
    editor.apply()
    _alts.value = readAll()
  }

  /** 服务器被删除时清掉它的第二地址 */
  fun forget(serverId: Long) {
    prefs.edit().remove(key(serverId)).apply()
    _alts.value = readAll()
  }

  private fun readAll(): Map<Long, EmbyEndpoint> =
    buildMap {
      prefs.all.forEach { (k, v) ->
        if (!k.startsWith(PREFIX) || v !is String) return@forEach
        val id = k.removePrefix(PREFIX).toLongOrNull() ?: return@forEach
        parse(v)?.let { put(id, it) }
      }
    }

  private fun parse(raw: String): EmbyEndpoint? {
    val parts = raw.split('|')
    if (parts.size != 3) return null
    val host = parts[0].trim()
    val port = parts[1].toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return EmbyEndpoint(host = host, port = port, useHttps = parts[2] == "1")
  }

  private fun key(serverId: Long) = "$PREFIX$serverId"

  companion object {
    private const val PREFS_NAME = "emby_endpoints"
    private const val PREFIX = "alt_"
  }
}

/**
 * 同一台服务器多个入口之间的**择优**。
 *
 * ## 它解决什么
 *
 * 一台 Emby 有两个入口：家里内网、外面 frp 公网。在家走内网（几毫秒、不过 frp），
 * 在外面走公网。用户进出家门时 App 不该需要手动改配置。
 *
 * ## 判据为什么是「并发探活竞速」而不是 WiFi 名字
 *
 * 读 SSID 需要 `ACCESS_FINE_LOCATION`（API 29+ 还要用户开着系统定位）或
 * `NEARBY_WIFI_DEVICES`（API 33+ 运行时授权）—— 一个播放器为了选地址去要定位权限，
 * 观感和商店审核都不划算；而且 Android 10 起 SSID 读取有速率限制，高频读会返回
 * `<unknown ssid>`，做成「每个请求前判断」必然坏掉。更要紧的是：**连着家里 WiFi
 * 不等于内网那台可达**（访客网络、VLAN 隔离、路由器改过网段都会让你判断错）。
 * 直接探一下「这个地址此刻通不通」是更硬的事实。
 *
 * ## 为什么请求路径上不探测
 *
 * 探测是 1~2 秒级的事，放在请求前会把每一次翻页都拖慢。所以：
 * 只在**网络变化**、**切换服务器**、**真实请求失败**这三个时机重估一次，结果缓存在
 * [active] 里，请求时同步取用（零开销）。
 *
 * ## 与主备接管（[EmbyFailoverController]）的分工
 *
 * 这里是**服务器内部**换入口，不是换服务器：A 的内网不通就换 A 的公网，
 * 只有**两个入口都不通**才说明 A 这台机器有问题，才轮到主备接管把用户挪到 B。
 * 顺序搞反会出现「家里内网抖一下就被甩到另一台服务器上」。
 */
class EmbyEndpointResolver(
  private val scope: CoroutineScope,
  private val store: EmbyEndpointStore,
) {

  /** serverId → 当前生效入口。没有条目 = 用服务器自身配置的那个 */
  private val _active = MutableStateFlow<Map<Long, EmbyEndpoint>>(emptyMap())
  val active: StateFlow<Map<Long, EmbyEndpoint>> = _active.asStateFlow()

  /** 切换到另一个入口时发一次 —— UI 据此重拉数据（两个入口的 URL 不同） */
  private val _switched = MutableSharedFlow<EmbyServer>(extraBufferCapacity = 4)
  val switched: SharedFlow<EmbyServer> = _switched.asSharedFlow()

  /** serverId → 最近一次入口切换的时间戳，用于「刚换完先别急着判死」 */
  private val lastSwitchAt = ConcurrentHashMap<Long, Long>()

  /** 当前这台此刻该用哪个入口（同步、零开销，可直接在拼 URL 时调用） */
  fun currentFor(server: EmbyServer): EmbyEndpoint =
    _active.value[server.id] ?: server.primaryEndpoint()

  fun hasAlternate(serverId: Long): Boolean = store.altFor(serverId) != null

  /** 刚换过入口的窗口内，一次请求失败不足以说明服务器挂了 */
  fun switchedRecently(serverId: Long): Boolean {
    val at = lastSwitchAt[serverId] ?: return false
    return System.currentTimeMillis() - at < SETTLE_MS
  }

  /**
   * 重估一台服务器的入口。
   *
   * @param failedHost 触发重估的那个失败请求的 host。给了就只处理「失败的正是在用入口」
   *   的情况 —— 否则一次无关的失败会把好好的内网换成公网。
   */
  suspend fun refresh(server: EmbyServer, failedHost: String? = null): Outcome {
    val alt = store.altFor(server.id) ?: return Outcome.NoAlternate
    val primary = server.primaryEndpoint()
    // 两个入口实际上一样（同 host 同端口同 scheme）时不必竞速
    if (alt.baseUrl == primary.baseUrl) return Outcome.NoAlternate

    val current = currentFor(server)
    if (failedHost != null && !current.host.equals(failedHost, ignoreCase = true)) {
      // 失败的地址不是我们在用的那个（多半是残留请求），不动配置
      return Outcome.UnchangedAlive
    }

    val winner = race(primary, alt, server.apiToken) ?: return Outcome.Dead
    val changed = winner.baseUrl != current.baseUrl
    if (changed) {
      _active.value = _active.value + (server.id to winner)
      lastSwitchAt[server.id] = System.currentTimeMillis()
      Log.i(TAG, "「${server.name}」入口切换到 ${winner.baseUrl}")
      _switched.tryEmit(server)
    }
    return if (changed) Outcome.Switched else Outcome.UnchangedAlive
  }

  /** 网络变化时把所有配了双入口的服务器都重估一遍 */
  suspend fun refreshAll(servers: List<EmbyServer>) {
    servers.forEach { server ->
      if (store.altFor(server.id) != null) runCatching { refresh(server) }
    }
  }

  /** 服务器被删除 / 第二地址被清空时，丢掉缓存的生效入口 */
  fun forget(serverId: Long) {
    _active.value = _active.value - serverId
    lastSwitchAt.remove(serverId)
  }

  /**
   * 两个入口并发探活，**谁先探通就用谁**。
   *
   * 为什么不「主地址优先」：用户的习惯可能是主配公网、备配内网，也可能是反过来；
   * 按配置顺序选会在家里走公网（明明内网几十毫秒）。按「先通」选，在家自然走内网、
   * 在外面内网必然超时、公网胜出 —— 不用用户操心谁当主。
   */
  private suspend fun race(
    a: EmbyEndpoint,
    b: EmbyEndpoint,
    token: String,
  ): EmbyEndpoint? = coroutineScope {
    val results = Channel<EmbyEndpoint?>(Channel.UNLIMITED)
    val jobs =
      listOf(a, b).map { ep ->
        launch(Dispatchers.IO) {
          val ok = EmbyClient.probeAlive(ep, token)
          results.send(if (ok) ep else null)
        }
      }
    val winner =
      withTimeoutOrNull(RACE_TIMEOUT_MS) {
        // 收两条结果：第一条通了就用；第一条是「不通」就等第二条
        val first = results.receive()
        if (first != null) first else results.receive()
      }
    jobs.forEach { it.cancel() }
    winner
  }

  /** 一次重估的结论 */
  enum class Outcome {
    /** 没配第二地址 —— 调用方该按原来的方式处理失败（交给主备接管） */
    NoAlternate,

    /** 探通了，且还是原来那个入口（网络只是闪了一下） */
    UnchangedAlive,

    /** 换到另一个入口了，并且已经通知 UI */
    Switched,

    /** 两个入口都不通 —— 这才是「这台服务器有问题」 */
    Dead,
  }

  companion object {
    private const val TAG = "EmbyEndpoint"

    /** 竞速总预算：探测自身 2s 超时，留 0.5s 余量 */
    private const val RACE_TIMEOUT_MS = 2_500L

    /** 刚换过入口的 30 秒内不判死（新入口可能还在建连接） */
    private const val SETTLE_MS = 30_000L
  }
}

/**
 * 拼 URL 时的统一出口。
 *
 * [EmbyClient] 是个 object，拿不到依赖注入，所以这里用一个可替换的全局引用
 * （与 [EmbyHttp.installTokenSource] 同一套做法）。没装解析器时行为与改造前**完全一致**
 * —— 回落到服务器自身配置的地址，不会因为没配第二地址而改变任何东西。
 */
object EmbyEndpoints {

  @Volatile
  var resolver: EmbyEndpointResolver? = null

  /** 这台服务器此刻该用的 host 前缀（含 scheme 与端口） */
  fun hostUrl(server: EmbyServer): String = resolver?.currentFor(server)?.baseUrl ?: server.hostUrl
}
