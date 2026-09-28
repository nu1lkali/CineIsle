package app.marlboroadvance.mpvex.preferences

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R

/**
 * 进度条样式。
 *
 * 之前设置页直接显示 `style.name`（Standard / Wavy / Thick），中文界面会露出英文，
 * 因此改成携带字符串资源。
 */
enum class SeekbarStyle(
    @StringRes val titleRes: Int,
) {
    Standard(R.string.i18n_seekbar_standard),
    Wavy(R.string.i18n_seekbar_wavy),
    Thick(R.string.i18n_seekbar_thick),
}
