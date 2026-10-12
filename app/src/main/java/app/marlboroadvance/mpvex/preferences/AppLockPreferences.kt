package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import java.security.MessageDigest

/**
 * 应用锁（儿童锁）偏好。
 *
 * ## 为什么 PIN 不明文存
 *
 * SharedPreferences 是应用私有的 plain 文件，root 过的设备一读就着。
 * 存 SHA-256（带固定盐）至少让「捡到手机顺手读文件」这条路走不通 ——
 * 对 4~6 位 PIN 来说这不是密码学意义上的安全（可穷举），但足以挡住
 * 「翻文件」这种最常见的泄露路径。真要防穷举得上 KeyStore，那是系统级
 * 应用锁（不是本项目该做的）。
 *
 * ## 生命周期
 *
 * 「开锁」与「设 PIN」是两个动作：开开关只进**设置 PIN** 流程，
 * 输完两遍一致的 PIN 才真正 [enabled]。关开关要先验证当前 PIN
 * （防止儿童自己进设置里关掉）。哈希会留着 —— 再开时不用重新设。
 */
class AppLockPreferences(preferenceStore: PreferenceStore) {

  /** 总开关。默认关 —— 这是个会改变每次启动流程的功能，不能替用户默认打开。 */
  val enabled = preferenceStore.getBoolean("app_lock_enabled", false)

  /** PIN 的 SHA-256 哈希（带盐）；空串 = 还没设置过。 */
  val pinHash = preferenceStore.getString("app_lock_pin_hash", "")

  /** 设置（或重设）PIN。 */
  fun setPin(pin: String) {
    pinHash.set(hashPin(pin))
  }

  /** 校验输入的 PIN。没设置过（哈希为空）时一律返回 false。 */
  fun verifyPin(pin: String): Boolean {
    val stored = pinHash.get()
    return stored.isNotEmpty() && stored == hashPin(pin)
  }

  /** 关锁（保留哈希，再开时不用重新设 PIN）。 */
  fun disable() {
    enabled.set(false)
  }

  companion object {
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 6

    /** 连续输错这么多次就进冷却，防穷举。 */
    const val MAX_ATTEMPTS = 5

    /** 冷却时长。 */
    const val COOLDOWN_MS = 30_000L

    /**
     * 离开应用超过这个时长，回来就要重新输 PIN。
     *
     * 5 秒是给「页内跳播放器 / 系统权限弹窗」这类**自己造成的短暂退后台**
     * 留的余量（那些场景回来不该重新解锁）；真正「离开应用」都远不止 5 秒。
     */
    const val RELOCK_GRACE_MS = 5_000L

    private const val SALT = "cineisle-applock-v1"

    /** PIN → 哈希。纯函数，脱离 Android 也能单测。 */
    fun hashPin(pin: String): String {
      val digest = MessageDigest.getInstance("SHA-256")
        .digest((SALT + pin).toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }
  }
}
