package app.marlboroadvance.mpvex.privacy

/**
 * 隐私授权的判定逻辑（纯函数，不碰 Android API）。
 *
 * ## 为什么判定要和存储分开
 *
 * 「要不要重新弹窗」这件事只有两个输入：本地记录、当前协议版本。
 * 把它写成纯函数之后，单测可以直接覆盖版本比较的边角（空串、少段、多段、
 * 用户手工改坏 prefs），不需要跑 Robolectric，也不会因为 SharedPreferences
 * 的实现细节变化而失效。
 *
 * ## 什么时候会要求重新确认
 *
 * 只要**任意一份**协议的当前版本高于用户上次同意的版本，就必须重新确认。
 * 反过来说：
 *
 * - App 版本升级（versionName / versionCode 变化）**不会**触发重新确认 ——
 *   协议版本和应用版本是两套独立的数字，混在一起会让每次发版都弹一次。
 * - 用户同意过 2.0 而当前是 1.5（回滚场景）**不**要求重新确认：
 *   他已经看过更新后的正文，没有理由再拦一次。
 *
 * ⚠️ 改协议正文时必须同时改 [PRIVACY_POLICY_VERSION] 或
 * [USER_AGREEMENT_VERSION]。静默改内容而不涨版本号，会让所有老用户
 * 停留在「已同意」状态，却从未看过新条款。
 */
object PrivacyGateManager {

  /** 当前《隐私政策》版本。正文变化时递增。 */
  const val PRIVACY_POLICY_VERSION = "1.0"

  /** 当前《用户服务协议》版本。正文变化时递增。 */
  const val USER_AGREEMENT_VERSION = "1.0"

  /**
   * 授权记录本身的格式版本。
   *
   * 与协议版本无关：将来如果记录里要加字段（例如「同意的设备数」），
   * 靠它判断该不该按新格式重写，而不是把老记录当成无效要求用户重签。
   */
  const val CONSENT_RECORD_VERSION = 1

  /**
   * 是否已获得当前版本协议的有效授权。
   *
   * @param record 本地读取到的授权记录
   */
  fun isGranted(record: ConsentRecord): Boolean =
    record.hasAgreed &&
      compareVersion(record.privacyPolicyVersion, PRIVACY_POLICY_VERSION) >= 0 &&
      compareVersion(record.userAgreementVersion, USER_AGREEMENT_VERSION) >= 0

  /**
   * 点分版本号比较。
   *
   * 逐段按整数比较，缺段按 0 补齐（`1.0` == `1.0.0`）。
   * 非数字段一律当 0 处理 —— prefs 可能被手工改坏，这时宁可判「旧版本需要重新确认」，
   * 也不能抛异常把启动流程炸掉。
   *
   * @return 负数表示 [left] 更旧，0 表示相同，正数表示 [left] 更新
   */
  fun compareVersion(left: String, right: String): Int {
    val a = splitVersion(left)
    val b = splitVersion(right)
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
      val x = a.getOrElse(i) { 0 }
      val y = b.getOrElse(i) { 0 }
      if (x != y) return x.compareTo(y)
    }
    return 0
  }

  private fun splitVersion(raw: String): List<Int> =
    raw.split('.')
      .map { it.trim().toIntOrNull() ?: 0 }
      .ifEmpty { listOf(0) }
}

/** 本机保存的授权记录快照。 */
data class ConsentRecord(
  val hasAgreed: Boolean,
  val privacyPolicyVersion: String,
  val userAgreementVersion: String,
  /** 用户点击「同意并继续」的时刻（epoch millis）；0 表示没有记录。 */
  val agreedAt: Long,
  val recordVersion: Int,
) {
  companion object {
    /** 从未同意过的初值。 */
    val EMPTY =
      ConsentRecord(
        hasAgreed = false,
        privacyPolicyVersion = "",
        userAgreementVersion = "",
        agreedAt = 0L,
        recordVersion = PrivacyGateManager.CONSENT_RECORD_VERSION,
      )
  }
}
