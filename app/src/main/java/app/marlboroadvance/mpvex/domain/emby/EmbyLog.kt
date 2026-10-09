package app.marlboroadvance.mpvex.domain.emby

import android.util.Log

/**
 * Emby 层的诊断日志（对应清单 1.3「禁止静默吞异常」）。
 *
 * ## 为什么需要它
 *
 * 项目里有一批**故意容错**的辅助请求（筛选项 / 统计类）：失败时返回空列表而不是抛异常，
 * 免得「筛选面板少一组选项」把整个页面搞崩。这本身是对的。
 *
 * 但以前它们是纯 `runCatching { }.getOrDefault(emptyList())` —— **一个字都不打**。
 * 后果是这类失败在用户侧表现为「筛选器里没有类型」「角标数字不显示」，
 * 在开发侧也**完全查不到**（没有堆栈、没有请求路径），属于最难查的一类 bug。
 *
 * 现在约定：
 * - **故意容错的地方**：照旧返回默认值，但**必须**调 [warn] 记下是哪个端点、什么原因；
 * - **不该容错的地方**（用户主动操作的主链路）：让异常照常抛出，由 UI 给错误态 + 重试。
 *
 * 抓 logcat：`adb logcat -s EmbyDiag`
 */
internal object EmbyLog {
  private const val TAG = "EmbyDiag"

  /** 请求失败但调用方会兜底降级（筛选面板、角标统计等）。 */
  fun warn(msg: String, t: Throwable? = null) {
    if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
  }

  /** 排查用：记录一条关键路径的细节。 */
  fun info(msg: String) {
    Log.i(TAG, msg)
  }
}
