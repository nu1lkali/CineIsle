package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import java.security.MessageDigest

/** 应用锁的解锁方式。 */
object AppLockMethod {
  /** 4~6 位数字，键盘输入。 */
  const val PIN = "pin"

  /** 3×3 点阵滑动连线（至少 4 点）。 */
  const val PATTERN = "pattern"
}

/**
 * 应用锁（儿童锁）偏好。
 *
 * ## 为什么凭证不明文存
 *
 * SharedPreferences 是应用私有的 plain 文件，root 过的设备一读就着。
 * 存 SHA-256（带固定盐）至少让「捡到手机顺手读文件」这条路走不通 ——
 * 对 4~6 位 PIN / 9 点图案来说这不是密码学意义上的安全（可穷举），但足以挡住
 * 「翻文件」这种最常见的泄露路径。真要防穷举得上 KeyStore，那是系统级
 * 应用锁（不是本项目该做的）。
 *
 * ## 生命周期
 *
 * 「开锁」与「设凭证」是两个动作：开开关只进**设置凭证**流程
 * （先选方式：数字 PIN 或九宫格图案），设完才真正 [enabled]。
 * 关开关要先验证当前凭证（防止儿童自己进设置里关掉）。哈希会留着 ——
 * 再开时不用重新设。
 *
 * ## 兼容
 *
 * 老用户只设过 PIN ⇒ [method] 默认 [AppLockMethod.PIN]，行为完全不变。
 */
class AppLockPreferences(preferenceStore: PreferenceStore) {

  /** 总开关。默认关 —— 这是个会改变每次启动流程的功能，不能替用户默认打开。 */
  val enabled = preferenceStore.getBoolean("app_lock_enabled", false)

  /** 当前解锁方式：[AppLockMethod.PIN] / [AppLockMethod.PATTERN]。 */
  val method = preferenceStore.getString("app_lock_method", AppLockMethod.PIN)

  /** PIN 的 SHA-256 哈希（带盐）；空串 = 还没设置过。 */
  val pinHash = preferenceStore.getString("app_lock_pin_hash", "")

  /** 图案（点序）的 SHA-256 哈希（带盐）；空串 = 还没设置过。 */
  val patternHash = preferenceStore.getString("app_lock_pattern_hash", "")

  /**
   * 凭证正确后是否**自动解锁**（PIN 免按确定键；图案本来就是画完即验）。
   *
   * 默认关 —— 这是改变解锁手感的行为，由用户自己开（红线：习惯入侵型功能一律可选）。
   */
  val autoUnlock = preferenceStore.getBoolean("app_lock_auto_unlock", false)

  /** 设置（或重设）数字 PIN，并把解锁方式切成 PIN。 */
  fun setPin(pin: String) {
    pinHash.set(hashSecret(pin))
    method.set(AppLockMethod.PIN)
  }

  /** 设置（或重设）九宫格图案，并把解锁方式切成图案。 */
  fun setPattern(pattern: List<Int>) {
    patternHash.set(hashSecret(serializePattern(pattern)))
    method.set(AppLockMethod.PATTERN)
  }

  /** 校验输入的 PIN。没设置过（哈希为空）时一律返回 false。 */
  fun verifyPin(pin: String): Boolean {
    val stored = pinHash.get()
    return stored.isNotEmpty() && stored == hashSecret(pin)
  }

  /** 校验图案。没设置过（哈希为空）时一律返回 false。 */
  fun verifyPattern(pattern: List<Int>): Boolean {
    val stored = patternHash.get()
    return stored.isNotEmpty() && stored == hashSecret(serializePattern(pattern))
  }

  /**
   * 当前方式是否已配置好凭证。
   *
   * 门禁用它决定「该不该锁」：开了开关但凭证为空（理论上进不去，防御性判断）
   * 时不能锁 —— 否则用户被永久关在门外。
   */
  fun hasCredential(): Boolean = when (method.get()) {
    AppLockMethod.PATTERN -> patternHash.get().isNotEmpty()
    else -> pinHash.get().isNotEmpty()
  }

  /** 关锁（保留哈希，再开时不用重新设凭证）。 */
  fun disable() {
    enabled.set(false)
  }

  companion object {
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 6

    /** 图案至少要连这么多点（与 Android 系统图案一致）。 */
    const val MIN_PATTERN_DOTS = 4

    /** 九宫格的边长（3 ⇒ 3×3 = 9 个点）。 */
    const val PATTERN_GRID = 3

    /** 连续输错这么多次就进冷却，防穷举。 */
    const val MAX_ATTEMPTS = 5

    /** 冷却时长。 */
    const val COOLDOWN_MS = 30_000L

    /**
     * 离开应用超过这个时长，回来就要重新解锁。
     *
     * 5 秒是给「页内跳播放器 / 系统权限弹窗」这类**自己造成的短暂退后台**
     * 留的余量（那些场景回来不该重新解锁）；真正「离开应用」都远不止 5 秒。
     */
    const val RELOCK_GRACE_MS = 5_000L

    private const val SALT = "cineisle-applock-v1"

    /** PIN → 哈希。纯函数，脱离 Android 也能单测。 */
    fun hashPin(pin: String): String = hashSecret(pin)

    /** 图案点序 → 字符串（如 `[0,4,8]` → `"0-4-8"`）。纯函数。 */
    fun serializePattern(pattern: List<Int>): String = pattern.joinToString("-")

    /**
     * 图案是否合法：点数达标、下标在 0..8、**不重复**。
     *
     * 不重复是「图案」的定义之一 —— 同一个点连两次不是有意义的图案，
     * 且会与 `[0,4,0]`、`[0,4]` 这类串产生歧义。
     */
    fun isValidPattern(pattern: List<Int>): Boolean =
      pattern.size >= MIN_PATTERN_DOTS &&
        pattern.all { it in 0 until PATTERN_GRID * PATTERN_GRID } &&
        pattern.toSet().size == pattern.size

    /** 任意凭证 → SHA-256（带盐）。纯函数，脱离 Android 也能单测。 */
    internal fun hashSecret(secret: String): String {
      val digest = MessageDigest.getInstance("SHA-256")
        .digest((SALT + secret).toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }
  }
}
