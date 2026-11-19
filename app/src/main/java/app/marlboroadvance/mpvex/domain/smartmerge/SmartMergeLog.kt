package app.marlboroadvance.mpvex.domain.smartmerge

import android.util.Log

/**
 * 智能合并链路的诊断日志；容错降级也必须留下可追踪的异常信息。
 *
 * 分三档而不是两档：合并是「扫描 → 匹配 → 快照 → 调接口 → 重查验证」的长链路，
 * 出问题时只有 error 是查不下去的 —— 得能看出**走到了哪一步**、当时拿到的数量对不对。
 * 统一 TAG 便于 `adb logcat -s SmartMergeDiag` 一把抓全。
 */
internal object SmartMergeLog {
  private const val TAG = "SmartMergeDiag"

  /** 正常流程的里程碑（扫描开始/完成、合并提交、验证结果）—— 不是异常，不要用 warn */
  fun info(message: String) {
    Log.i(TAG, message)
  }

  fun warn(message: String, throwable: Throwable? = null) {
    if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
  }

  fun error(message: String, throwable: Throwable? = null) {
    if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
  }
}
