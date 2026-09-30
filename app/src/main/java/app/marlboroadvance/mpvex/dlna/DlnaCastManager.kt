package app.marlboroadvance.mpvex.dlna

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.internal.UPnPException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/**
 * 对 UPnPCast 的薄封装：把它的协程 / 对象式 API 收敛成 Compose 友好的 [StateFlow]，
 * 并补齐本地文件投屏（file:// 直传路径；content:// 先解析真实路径，不行再拷到缓存）。
 *
 * 状态机：
 *   Idle → Discovering → Devices → (选设备) Connecting → Casting
 *                                ↘ (空/失败) Error
 * 关闭面板时：若正在 Casting 则保留（电视继续播），否则回到 Idle。
 *
 * 底层 UPnPCast 源码内置在 com.yinnho.upnpcast 包（v1.3.0），
 * 已修掉 1.1.2 的「越搜越少 / 端口占用 / host:-1 / MIME」等问题。
 */
class DlnaCastManager(private val context: Application) {

  /** 打开投屏面板前由调用方写入：当前想投屏的媒体。 */
  var pendingPayload: CastPayload? = null

  private val _devices = MutableStateFlow<List<DLNACast.Device>>(emptyList())
  val devices: StateFlow<List<DLNACast.Device>> = _devices.asStateFlow()

  sealed interface UiState {
    data object Idle : UiState
    data object Discovering : UiState
    data class Devices(val devices: List<DLNACast.Device>) : UiState
    data class Connecting(val device: DLNACast.Device) : UiState
    data class Casting(
      val device: DLNACast.Device,
      val playbackState: DLNACast.PlaybackState,
      val positionMs: Long,
      val durationMs: Long,
      val volume: Int,
    ) : UiState
    data class Error(val message: String) : UiState
  }

  private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
  val uiState: StateFlow<UiState> = _uiState.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var pollJob: Job? = null
  private var discoverJob: Job? = null

  init {
    // 初始化 UPnPCast 引擎（安全调用：失败也不影响主进程）。
    // 新版本 init() 会先 cleanup()，重复调用是安全的。
    runCatching { DLNACast.init(context) }
  }

  /** 开始 SSDP 设备发现。 */
  fun startDiscovery() {
    if (_uiState.value is UiState.Discovering) return
    discoverJob?.cancel()
    _uiState.value = UiState.Discovering
    discoverJob = scope.launch(Dispatchers.IO) {
      runCatching { DLNACast.search(timeout = SEARCH_TIMEOUT_MS) }
        .onSuccess { list ->
          // 兜底：万一这一轮一个都没收到（Wi-Fi 抖动 / 设备休眠），
          // 保留上一轮已发现的设备，避免面板凭空变空。
          val merged = list.ifEmpty { _devices.value }
          _devices.value = merged
          _uiState.value =
            if (merged.isEmpty()) {
              UiState.Error(
                "未发现可用的投屏设备。\n请确认：\n· 手机与投屏设备在同一 Wi-Fi\n· 设备的 DLNA / 投屏功能已开启",
              )
            } else {
              UiState.Devices(merged)
            }
        }
        .onFailure { e ->
          Log.w(TAG, "DLNA search failed", e)
          _uiState.value = UiState.Error("设备搜索失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
  }

  /** 选定设备并投屏当前 [pendingPayload]。 */
  fun connectAndCast(device: DLNACast.Device) {
    val payload = pendingPayload
    if (payload == null) {
      _uiState.value = UiState.Error("没有可投屏的媒体，请先打开要投屏的视频。")
      return
    }
    _uiState.value = UiState.Connecting(device)
    scope.launch(Dispatchers.IO) {
      runCatching {
        withTimeout(CAST_TIMEOUT_MS) { castPayload(device, payload) }
      }.onSuccess {
        _uiState.value =
          UiState.Casting(
            device = device,
            playbackState = DLNACast.PlaybackState.PLAYING,
            positionMs = 0,
            durationMs = 0,
            volume = -1,
          )
        startPolling()
      }.onFailure { e ->
        Log.w(TAG, "DLNA cast failed", e)
        _uiState.value = UiState.Error(mapError(e))
      }
    }
  }

  private suspend fun castPayload(device: DLNACast.Device, p: CastPayload) {
    val options = CastOptions(mimeType = p.mimeType)
    when (p.uri.scheme) {
      "http", "https" -> {
        val url = p.uri.toString()
        Log.i(TAG, "cast url=$url to ${device.name} (${device.address})")
        val ok = DLNACast.castToDevice(device, url, p.title, options)
        if (!ok) throw UPnPException.DeviceError(describeRejection(url))
      }
      else -> {
        val path =
          resolveLocalPath(p.uri)
            ?: throw UPnPException.FileError("无法读取本地文件，可能无法投屏该视频。")
        Log.i(TAG, "cast local file=$path to ${device.name} (${device.address})")
        DLNACast.castLocalFile(path, device, p.title, options)
      }
    }
  }

  /**
   * castToDevice() 只回传 false，失败原因是个黑盒。这里把库记下的设备侧错误
   * （UPnP errorCode / errorDescription，或服务解析失败提示）拼进提示里，
   * 让用户/日志能看出到底是格式不支持、URL 不可达还是设备描述解析失败。
   */
  private fun describeRejection(url: String): String {
    val detail = DLNACast.getLastError()
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    return buildString {
      append("设备拒绝了投屏请求")
      if (!detail.isNullOrBlank()) append("：").append(detail)
      else append("（设备未返回具体错误码）")
      if (!host.isNullOrBlank()) {
        append("\n提示：设备需要能直接访问 ").append(host)
        append("，请确认两者在同一局域网且该地址未被防火墙拦截。")
      }
    }
  }

  private fun resolveLocalPath(uri: Uri): String? =
    when (uri.scheme) {
      "file" -> uri.path
      "content" -> resolveContentUriPath(uri) ?: copyContentToCache(uri)
      else -> null
    }

  /** content:// 优先取真实文件路径（MediaStore / DocumentsContract），拿不到再拷缓存。 */
  private fun resolveContentUriPath(uri: Uri): String? {
    runCatching {
      context.contentResolver
        .query(uri, arrayOf("_data"), null, null, null)
        ?.use { c ->
          if (c.moveToFirst()) {
            val idx = c.getColumnIndex("_data")
            if (idx >= 0) {
              c.getString(idx)?.takeIf { it.isNotBlank() && File(it).exists() }?.let { return it }
            }
          }
        }
    }
    if (DocumentsContract.isDocumentUri(context, uri)) {
      runCatching {
        val docId = DocumentsContract.getDocumentId(uri)
        if (docId.startsWith("primary:")) {
          val path = "${Environment.getExternalStorageDirectory()}/${docId.removePrefix("primary:")}"
          if (File(path).exists()) return path
        }
      }
    }
    return null
  }

  /** content:// 拿不到真实路径时的兜底：整文件拷到应用缓存再交给 UPnPCast 起本地服务。 */
  private fun copyContentToCache(uri: Uri): String? =
    runCatching {
      val cr = context.contentResolver
      val name = queryDisplayName(cr, uri) ?: "cast_${UUID.randomUUID()}"
      val ext = cr.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: ""
      val dir = File(context.cacheDir, "cineisle_cast")
      dir.mkdirs()
      val out = File(dir, "$name${if (ext.isNotEmpty()) ".$ext" else ""}")
      cr.openInputStream(uri)?.use { input -> input.copyTo(out.outputStream()) }
      out.absolutePath
    }.getOrNull()

  private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? =
    runCatching {
      cr.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()

  fun controlPlay() = scope.launch(Dispatchers.IO) { runCatching { DLNACast.play() }; refresh() }
  fun controlPause() = scope.launch(Dispatchers.IO) { runCatching { DLNACast.pause() }; refresh() }
  fun controlStop() = scope.launch(Dispatchers.IO) {
    runCatching { DLNACast.stop() }
    pollJob?.cancel(); pollJob = null
    _uiState.value = UiState.Devices(_devices.value)
  }
  fun controlSeek(ms: Long) = scope.launch(Dispatchers.IO) { runCatching { DLNACast.seek(ms) }; refresh() }
  fun controlVolume(v: Int) = scope.launch(Dispatchers.IO) { runCatching { DLNACast.setVolume(v) }; refresh() }

  /** 断开：停止设备播放并复位状态。 */
  fun disconnect() {
    pollJob?.cancel(); pollJob = null
    scope.launch(Dispatchers.IO) { runCatching { DLNACast.stop() } }
    _uiState.value = UiState.Idle
    _devices.value = emptyList()
  }

  private fun startPolling() {
    pollJob?.cancel()
    pollJob = scope.launch(Dispatchers.IO) {
      while (isActive) {
        delay(1500)
        if (_uiState.value !is UiState.Casting) break
        refresh()
      }
    }
  }

  /** 轮询设备状态。getPlaybackState() 走 GetTransportInfo，能反映电视端被暂停/停止。 */
  private suspend fun refresh() {
    val cur = _uiState.value as? UiState.Casting ?: return
    val state = runCatching { DLNACast.getPlaybackState() }
      .getOrDefault(DLNACast.getState().playbackState)
    val prog = runCatching { DLNACast.getProgress() }.getOrNull()
    val volume = runCatching { DLNACast.getState().volume }.getOrDefault(cur.volume)
    _uiState.value = cur.copy(
      playbackState = state,
      positionMs = prog?.first ?: cur.positionMs,
      durationMs = prog?.second ?: cur.durationMs,
      volume = volume,
    )
  }

  private fun mapError(e: Throwable): String =
    when (e) {
      is UPnPException.NetworkError -> "网络错误：无法连接投屏设备，请确认设备在线且在同一局域网。\n${e.message ?: ""}".trim()
      is UPnPException.DeviceError -> "设备返回错误：${e.message}"
      is UPnPException.FileError -> "本地文件错误：${e.message}"
      is UPnPException.MediaError -> "媒体错误：${e.message}"
      is kotlinx.coroutines.TimeoutCancellationException ->
        "投屏超时（${CAST_TIMEOUT_MS / 1000}s）：设备没有在预期时间内响应，请重试或换一个设备。"
      else -> "投屏失败：${e.message ?: e.javaClass.simpleName}"
    }

  /** 面板关闭时调用：保留投屏状态（电视继续播），否则复位。 */
  fun onDismissed() {
    pollJob?.cancel(); pollJob = null
    if (_uiState.value !is UiState.Casting) {
      _uiState.value = UiState.Idle
      _devices.value = emptyList()
    }
  }

  private companion object {
    const val TAG = "CineIsle-DLNA"
    const val SEARCH_TIMEOUT_MS = 4000L
    // SetAVTransportURI 最多会降级重试 4 次（见 DlnaMediaController.playMediaDirect），
    // 给足时间，避免第一次降级还没走完就被判超时。
    const val CAST_TIMEOUT_MS = 45_000L
  }
}
