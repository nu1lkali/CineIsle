package app.marlboroadvance.mpvex.dlna

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
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
import java.io.File
import java.util.UUID

/**
 * 对 UPnPCast 的薄封装：把它的回调式 / 对象式 API 收敛成 Compose 友好的 [StateFlow]，
 * 并补齐本地文件投屏（file:// 直传路径；content:// 先解析真实路径，不行再拷到缓存）。
 *
 * 状态机：
 *   Idle → Discovering → Devices → (选设备) Connecting → Casting
 *                                ↘ (空/失败) Error
 * 关闭面板时：若正在 Casting 则保留（电视继续播），否则回到 Idle。
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

  init {
    // 初始化 UPnPCast 引擎（安全调用：失败也不影响主进程）
    runCatching { DLNACast.init(context) }
  }

  /** 开始 SSDP 设备发现。 */
  fun startDiscovery() {
    if (_uiState.value is UiState.Discovering) return
    _devices.value = emptyList()
    _uiState.value = UiState.Discovering
    scope.launch(Dispatchers.IO) {
      runCatching { DLNACast.search(timeout = 5000) }
        .onSuccess { list ->
          _devices.value = list
          _uiState.value =
            if (list.isEmpty()) {
              UiState.Error(
                "未发现可用的投屏设备。\n请确认：\n· 手机与投屏设备在同一 Wi-Fi\n· 设备的 DLNA / 投屏功能已开启",
              )
            } else {
              UiState.Devices(list)
            }
        }
        .onFailure { e ->
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
      runCatching { castPayload(device, payload) }
        .onSuccess {
          _uiState.value =
            UiState.Casting(
              device = device,
              playbackState = DLNACast.getState().playbackState,
              positionMs = 0,
              durationMs = 0,
              volume = -1,
            )
          startPolling()
        }
        .onFailure { e -> _uiState.value = UiState.Error(mapError(e)) }
    }
  }

  private suspend fun castPayload(device: DLNACast.Device, p: CastPayload) {
    when (p.uri.scheme) {
      "http", "https" -> {
        val ok = DLNACast.castToDevice(device, p.uri.toString(), p.title)
        if (!ok) throw UPnPException.DeviceError("设备拒绝了投屏请求")
      }
      else -> {
        val path =
          resolveLocalPath(p.uri)
            ?: throw UPnPException.FileError("无法读取本地文件，可能无法投屏该视频。")
        DLNACast.castLocalFile(path, device, p.title)
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

  private suspend fun refresh() {
    val cur = _uiState.value as? UiState.Casting ?: return
    val state = DLNACast.getState()
    val prog = runCatching { DLNACast.getProgress() }.getOrNull()
    _uiState.value = cur.copy(
      playbackState = state.playbackState,
      positionMs = prog?.first ?: cur.positionMs,
      durationMs = prog?.second ?: cur.durationMs,
      volume = state.volume,
    )
  }

  private fun mapError(e: Throwable): String =
    when (e) {
      is UPnPException.NetworkError -> "网络错误：无法连接投屏设备，请确认设备在线且在同一局域网。"
      is UPnPException.DeviceError -> "设备返回错误：${e.message}"
      is UPnPException.FileError -> "本地文件错误：${e.message}"
      is UPnPException.MediaError -> "媒体错误：${e.message}"
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
}
