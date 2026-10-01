package app.marlboroadvance.mpvex.ui.player.engine

import android.graphics.Bitmap
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import app.marlboroadvance.mpvex.ui.player.TrackNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放内核门面（facade）。
 *
 * 全项目对播放内核的调用统一走这里，不再直接碰 [MPVLib]。
 *
 * 历史上这里还挂过一个 GSYVideoPlayer 分支（mpv 的 option/property 三元组被逐条
 * "翻译"成 GSY 的调用），那套翻译层是闪退 / 黑屏 / 拉满音量的根源，已整体删除。
 * 现在门面只转发 mpv，预留的 `kind` 字段保持原样，避免动到界面上几百处调用点。
 */
object PlayerLib {
  /** 当前内核。整个进程内保持一致。 */
  @Volatile
  var kind: EngineKind = EngineKind.MPV

  private val isMpv: Boolean get() = kind == EngineKind.MPV

  // ─────────────── 属性流（Compose 侧 collectAsState 用） ───────────────

  class Props<T>(
    private val provider: (String) -> StateFlow<T?>,
  ) {
    operator fun get(key: String): StateFlow<T?> = provider(key)
  }

  private fun <T> emptyStateFlow(): MutableStateFlow<T?> = MutableStateFlow(null)

  val propInt =
    Props<Int?> { key ->
      if (isMpv) MPVLib.propInt[key] else emptyStateFlow()
    }

  val propFloat =
    Props<Float?> { key ->
      if (isMpv) MPVLib.propFloat[key] else emptyStateFlow()
    }

  val propDouble =
    Props<Double?> { key ->
      if (isMpv) MPVLib.propDouble[key] else emptyStateFlow()
    }

  val propBoolean =
    Props<Boolean?> { key ->
      if (isMpv) MPVLib.propBoolean[key] else emptyStateFlow()
    }

  val propString =
    Props<String?> { key ->
      if (isMpv) MPVLib.propString[key] else emptyStateFlow()
    }

  val propNode =
    Props<MPVNode?> { key ->
      if (isMpv) MPVLib.propNode[key] else emptyStateFlow()
    }

  /** 轨道列表。mpv 侧由 track-list 推导；非 mpv 内核为空。 */
  val gsyTrackNodes = MutableStateFlow<List<TrackNode>>(emptyList())

  // ─────────────── 属性读 ───────────────

  fun getPropertyInt(key: String): Int? = if (isMpv) MPVLib.getPropertyInt(key) else null

  fun getPropertyFloat(key: String): Float? = if (isMpv) MPVLib.getPropertyFloat(key) else null

  fun getPropertyBoolean(key: String): Boolean? = if (isMpv) MPVLib.getPropertyBoolean(key) else null

  fun getPropertyDouble(key: String): Double? = if (isMpv) MPVLib.getPropertyDouble(key) else null

  fun getPropertyString(key: String): String? = if (isMpv) MPVLib.getPropertyString(key) else null

  /** 读 node 型属性（如 demuxer-cache-state）；非 mpv 内核恒为 null */
  fun getPropertyNode(key: String): MPVNode? = if (isMpv) MPVLib.getPropertyNode(key) else null

  // ─────────────── 属性写 ───────────────

  fun setPropertyInt(
    key: String,
    value: Int,
  ) {
    if (isMpv) MPVLib.setPropertyInt(key, value)
  }

  fun setPropertyFloat(
    key: String,
    value: Float,
  ) {
    if (isMpv) MPVLib.setPropertyFloat(key, value)
  }

  fun setPropertyBoolean(
    key: String,
    value: Boolean,
  ) {
    if (isMpv) MPVLib.setPropertyBoolean(key, value)
  }

  fun setPropertyDouble(
    key: String,
    value: Double,
  ) {
    if (isMpv) MPVLib.setPropertyDouble(key, value)
  }

  fun setPropertyString(
    key: String,
    value: String,
  ) {
    if (isMpv) MPVLib.setPropertyString(key, value)
  }

  /** mpv 专属的启动选项（sub-font / hwdec / glsl-shaders ...） */
  fun setOptionString(
    key: String,
    value: String,
  ) {
    if (isMpv) MPVLib.setOptionString(key, value)
  }

  // ─────────────── 命令 ───────────────

  fun command(vararg args: String) {
    if (isMpv) MPVLib.command(*args)
  }

  // ─────────────── 生命周期 / 事件 ───────────────

  /** AAR 的 MpvFormat 是 object + Int 常量（非枚举），format 直接传 Int */
  fun observeProperty(
    name: String,
    format: Int,
  ) {
    if (isMpv) MPVLib.observeProperty(name, format)
  }

  fun addObserver(observer: MPVLib.EventObserver) {
    if (isMpv) MPVLib.addObserver(observer)
  }

  fun removeObserver(observer: MPVLib.EventObserver) {
    if (isMpv) MPVLib.removeObserver(observer)
  }

  /** 释放内核 */
  fun destroy() {
    if (isMpv) MPVLib.destroy()
  }

  /** 截图 */
  fun grabThumbnail(size: Int): Bitmap? = if (isMpv) MPVLib.grabThumbnail(size) else null
}
