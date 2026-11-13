package app.marlboroadvance.mpvex.ui.player.subtitle

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.ConfidenceLevel
import app.marlboroadvance.mpvex.ui.player.subtitle.smart.MatchReason

/**
 * 匹配依据 → 文案资源。集中一处映射。
 *
 * 播放器的字幕面板与「文件选择器」的推荐区用的是同一套判据，
 * 两处各写一个 `when` 迟早会漂（一处加了新枚举、另一处 falling 到默认分支，
 * 于是同一份结果在两个界面显示成两句话）。
 *
 * @return 0 表示这条依据不单独成句（例如 [MatchReason.STEM_EXACT] 与
 *   [MatchReason.CODE_EXACT] 同时成立时，只显示更硬的那条）
 */
@StringRes
fun MatchReason.labelRes(): Int =
  when (this) {
    MatchReason.STEM_EXACT -> R.string.player_sheets_local_subs_reason_stem
    MatchReason.STEM_PREFIX -> R.string.player_sheets_local_subs_reason_stem_prefix
    MatchReason.CODE_EXACT -> R.string.player_sheets_local_subs_reason_code
    MatchReason.CODE_FORMAT_VARIANT -> R.string.player_sheets_local_subs_reason_format
    MatchReason.CODE_ALIAS -> R.string.player_sheets_local_subs_reason_alias
    MatchReason.CODE_ZERO_VARIANT -> R.string.player_sheets_local_subs_reason_zero
    MatchReason.CODE_SUFFIX_VARIANT -> R.string.player_sheets_local_subs_reason_suffix
    MatchReason.CODE_PARTIAL -> R.string.player_sheets_local_subs_reason_partial
    MatchReason.CRC_SAME -> R.string.player_sheets_local_subs_reason_crc
    MatchReason.TITLE_TOKENS -> R.string.player_sheets_local_subs_reason_title
    MatchReason.VERSION_FLAGS -> R.string.player_sheets_local_subs_reason_version
    MatchReason.TIMING_VERIFIED -> R.string.player_sheets_local_subs_verified
    MatchReason.TEXT_ONLY -> R.string.player_sheets_local_subs_reason_text
  }

/**
 * 置信度角标文案。LOW 不给角标 —— 那一条只是「碰巧像」，加个徽章反而像推荐。
 */
@StringRes
fun ConfidenceLevel.badgeRes(): Int? =
  when (this) {
    ConfidenceLevel.HIGH -> R.string.player_sheets_local_subs_badge_auto
    ConfidenceLevel.MEDIUM -> R.string.player_sheets_local_subs_badge_review
    ConfidenceLevel.LOW -> null
  }
