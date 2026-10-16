package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyTicks

/**
 * 条目「已看进度 / 剩余时长」两个派生量的**唯一实现**。
 *
 * 原先 `EmbyHomeScreen` 与 `EmbyLibraryScreen` 各写了一份（`playbackProgress` /
 * `itemProgress`），历史页、收藏页也各有一份 —— 一处改了另一处不跟，就会出现
 * 「同一条片子在有的页面显示进度、有的页面不显示」。收口到这里，谁要谁调。
 *
 * 判据统一：`UserData.PlaybackPositionTicks`（服务端按**条目**存的续播位置）÷
 * `RunTimeTicks`；两者缺一或位置为 0（= 没开始看 / 已清进度）一律返回 null，
 * 卡片据此决定「不画进度条、不给剩余角标」。
 */

/** 已看进度 0f~1f；没有时长 / 没开始看时返回 null */
internal fun playbackProgress(item: EmbyItem): Float? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  return (position.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * 剩余时长文案：「剩余 12分30秒」。
 *
 * 只有「已开始播放且尚未看完」的条目才返回非空；
 * 超过 1 小时的用「剩余 X小时XX分」，避免数字太长撑破角标。
 */
internal fun remainingLabel(item: EmbyItem): String? {
  val total = item.RunTimeTicks ?: return null
  if (total <= 0) return null
  val position = item.UserData?.PlaybackPositionTicks ?: 0L
  if (position <= 0) return null
  val remain = EmbyTicks.ticksToSeconds(total - position)
  if (remain <= 0) return null
  return if (remain >= 3600) {
    "剩余 ${remain / 3600}小时${(remain % 3600) / 60}分"
  } else {
    "剩余 ${remain / 60}分${remain % 60}秒"
  }
}
