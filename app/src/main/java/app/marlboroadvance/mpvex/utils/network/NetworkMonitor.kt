package app.marlboroadvance.mpvex.utils.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 网络连通性监听（清单 4.2）。
 *
 * ## 之前为什么没有这个
 * 项目里其实**申请了** `ACCESS_NETWORK_STATE` 权限（`AndroidManifest.xml`）却一次都没调用过 ——
 * 断网时的表现是各个 API 调用抛异常 → 被 `runCatching` 吞掉 → 页面显示空列表。
 * 用户看到的是「我的媒体库空了」，而不是「你断网了」。
 *
 * ## 为什么用 callbackFlow 而不是轮询
 * `registerDefaultNetworkCallback` 是系统主动推事件，不需要我们定时问。
 * `callbackFlow` 的 `awaitClose` 保证**取消收集时自动注销回调** ——
 * 这是这个 API 最容易漏的地方：忘了注销，回调对象会一直持有 Flow 的收集者，
 * 而系统侧那个 callback 是进程级的，泄漏链条能一直活到进程结束。
 *
 * ## 判断「在线」的标准
 * 只看 [NetworkCapabilities.NET_CAPABILITY_INTERNET] 与
 * [NET_CAPABILITY_VALIDATED]。
 * ⚠️ **必须有 VALIDATED**：只判 INTERNET 会把「连上了但鉴权失败的 Wi-Fi」
 * （机场、酒店的门户 Wi-Fi 就是这样）算成在线，然后用户看到一堆加载失败的超时。
 * VALIDATED 表示系统真的通过这个网络访问过互联网探测地址，结果才作数。
 */
class NetworkMonitor(context: Context) {

  private val appContext = context.applicationContext
  private val connectivityManager =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  /**
   * 当前是否在线。
   *
   * 初始值用 [queryCurrent] 同步取一次，而不是硬编码 `true`：
   * 冷启动时若已经断网，首页应该立刻知道，而不是等第一次网络变更回调
   * （断网期间不一定再触发回调，可能等到用户手动开网才有）。
   */
  private val _isOnline = MutableStateFlow(queryCurrent())
  val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  /**
   * 带回调的在线状态流，供 Composable 直接 collect。
   *
   * 首个值就是当前状态（[queryCurrent] 的结果），所以 `collectAsState` 不会闪一下。
   */
  fun observe(): Flow<Boolean> = callbackFlow {
    val manager = connectivityManager
    if (manager == null) {
      // 取不到 ConnectivityManager（几乎不可能，但设备 ROM 定制会出这种事）：
      // 保守报「在线」—— 让上层按老路走网络请求，失败了自己会提示，
      // 总比把用户锁死在离线模式、连本地内容都看不到要好。
      trySend(true)
      awaitClose { }
      return@callbackFlow
    }

    trySend(queryCurrent())

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        // ⚠️ 不能在这里直接报「在线」：onAvailable 只说明网络**建起来了**，
        // 还没验证通过。 captive portal（需要网页认证的 Wi-Fi）也会走 onAvailable。
        // 真正的判据是 onCapabilitiesChanged 里的 VALIDATED。
        _isOnline.value = queryCurrent()
        trySend(_isOnline.value)
      }

      override fun onLost(network: Network) {
        _isOnline.value = queryCurrent()
        trySend(_isOnline.value)
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        // distinctUntilChanged 之外仍要自己判一次：连上第二个网络时
        // 回调会带着「其实还是不通」的能力集再来一次，不判就会闪一下离线态。
        if (online != _isOnline.value) {
          _isOnline.value = online
          trySend(online)
        }
      }
    }

    // ⚠️ 这里**必须**用单参重载 `registerDefaultNetworkCallback(callback)`。
    // 容易踩的坑：本 SDK 的双参重载是 `registerDefaultNetworkCallback(request, handler)`
    // —— 第二参是 **Handler**，不是 NetworkCallback。传 callback 过去报的错是
    // 「actual type is '<anonymous>', but 'Handler' was expected」，很直白但很容易
    // 误以为是 callback 匿名类写错了。
    // 语义上单参版才是我们要的：「只监听系统当前选中的那条默认网络」，
    // 正好对应 [queryCurrent] 问的那个问题（我正在用的这条通不通）。
    runCatching { manager.registerDefaultNetworkCallback(callback) }
      .onFailure { trySend(true) }

    awaitClose {
      // 必须在 awaitClose 里注销：callbackFlow 的收集被取消时执行这一段，
      // 这是 callbackFlow 存在的意义 —— 让「注册/注销」成对，不可能只注册不注销。
      runCatching { manager.unregisterNetworkCallback(callback) }
    }
  }.distinctUntilChanged()

  /**
   * 同步查一次当前网络状态。
   *
   * 用 [getNetworkCapabilities]（拿当前**默认**网络）而不是 `getAllNetworks()`：
   * 手机上同时有 Wi-Fi + 蜂窝是常态，「有没有一个能上网的网络」不是用户关心的问题，
   * 「我正在用的这条通不通」才是。
   */
  private fun queryCurrent(): Boolean {
    val manager = connectivityManager ?: return true
    return runCatching {
      val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
  }
}
