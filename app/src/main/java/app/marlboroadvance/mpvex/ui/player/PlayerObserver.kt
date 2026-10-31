package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import android.util.Log

/**
 * mpv 事件/属性回调 → `PlayerActivity` 的分发层。
 *
 * ## ⚠️ 为什么每一次分发都必须吞掉异常（v190）
 *
 * 这些回调由 **libmpv 的 native 线程经 JNI 调到 Java**。这里（以及被
 * `runOnUiThread` 投递到主线程的那个 lambda）抛出的任何异常都会变成
 * **未捕获异常 ⇒ 进程直接死**。用户看到的就是「打开一个损坏的视频，App 闪退」。
 *
 * 而 mpv 对损坏文件的属性值本来就不保证合法：节点缺字段、空字符串、NaN、
 * 时长/轨道数为 0 都出现过。下游解析偶发抛错是必然的，问题只在于
 * **一条属性没生效**，绝不该带走整个 App。
 *
 * 所以每个回调体统一进 [guard]：只记日志，让上层的最后一次判定（错误卡片 /
 * 时长看守）去处理「这个文件到底能不能放」。
 */
class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver {
  override fun eventProperty(property: String) {
    if (activity.player.isExiting) return
    // ⚠️ guard 必须在 runOnUiThread **里面**：投递出去的 lambda 才是在主线程上跑，
    // 在外面包一层只能兜住「投递」本身，兜不住真正的处理逻辑。
    activity.runOnUiThread { guard("props:$property") { activity.onObserverEvent(property) } }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { guard("props:$property") { activity.onObserverEvent(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { guard("props:$property") { activity.onObserverEvent(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { guard("props:$property") { activity.onObserverEvent(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { guard("props:$property") { activity.onObserverEvent(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { guard("props:$property") { activity.onObserverEvent(property, value) } }
  }

  override fun event(eventId: Int, data: MPVNode) {
    if (activity.player.isExiting) return
    // `data` 一路带下去：`MPV_EVENT_END_FILE` 的失败原因就在里面（损坏文件靠它判死）。
    activity.runOnUiThread { guard("event:$eventId") { activity.event(eventId, data) } }
  }

  private inline fun guard(
    what: String,
    block: () -> Unit,
  ) {
    runCatching(block).onFailure { Log.w(TAG, "mpv 回调异常（已忽略）: $what", it) }
  }

  private companion object {
    const val TAG = "PlayerObserver"
  }
}
