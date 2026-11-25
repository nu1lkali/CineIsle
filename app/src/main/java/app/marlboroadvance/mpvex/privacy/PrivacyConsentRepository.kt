package app.marlboroadvance.mpvex.privacy

import android.content.Context
import app.marlboroadvance.mpvex.preferences.preference.AndroidPreferenceStore

/**
 * 隐私授权记录的持久化。
 *
 * ## 为什么直接用 SharedPreferences 而不是 DataStore
 *
 * 项目全部偏好都走 [AndroidPreferenceStore]（底层就是默认的 SharedPreferences）。
 * 授权状态是**启动路径上的同步读**：Application.onCreate 里就要拿到结果决定
 * 初始化哪一段，DataStore 的 Flow 语义在这里反而要多绕一层 `runBlocking`。
 * 用同一套存储还有一个实际好处：系统「清除数据」会一次性把它们全清掉，
 * 不会出现「设置清了、授权还在」的半截状态。
 *
 * ## 记录里存什么
 *
 * - [ConsentRecord.hasAgreed]：是否点过「同意并继续」。**只有用户主动点击才写入**，
 *   打开协议正文不算同意（正文页不碰这里）。
 * - [ConsentRecord.privacyPolicyVersion] / [ConsentRecord.userAgreementVersion]：
 *   同意时各自的协议版本，用于判断是否过期。
 * - [ConsentRecord.agreedAt]：同意时刻，设置页展示。
 * - [ConsentRecord.recordVersion]：记录格式版本。
 */
class PrivacyConsentRepository(context: Context) {

  private val store = AndroidPreferenceStore(context.applicationContext)

  private val hasAgreedPref = store.getBoolean(KEY_HAS_AGREED, false)
  private val privacyVersionPref = store.getString(KEY_PRIVACY_VERSION, "")
  private val agreementVersionPref = store.getString(KEY_AGREEMENT_VERSION, "")
  private val agreedAtPref = store.getLong(KEY_AGREED_AT, 0L)
  private val recordVersionPref = store.getInt(KEY_RECORD_VERSION, PrivacyGateManager.CONSENT_RECORD_VERSION)

  /** 读取当前记录。任何一项缺失都按「未同意」解释，不做补偿推断。 */
  fun read(): ConsentRecord =
    ConsentRecord(
      hasAgreed = hasAgreedPref.get(),
      privacyPolicyVersion = privacyVersionPref.get(),
      userAgreementVersion = agreementVersionPref.get(),
      agreedAt = agreedAtPref.get(),
      recordVersion = recordVersionPref.get(),
    )

  /**
   * 写入一次有效授权。
   *
   * ⚠️ 只应由「同意并继续」按钮的点击回调调用 —— 这是本类唯一的写入口。
   */
  fun save(
    privacyPolicyVersion: String,
    userAgreementVersion: String,
    agreedAtMillis: Long = System.currentTimeMillis(),
  ) {
    // 先写各版本号、最后写 hasAgreed：中途被杀时最坏结果只是「版本齐了但没同意」，
    // 下次照常弹窗。反过来先写 hasAgreed 就可能出现「已同意但版本为空」，
    // 那种记录会被 isGranted 判为过期，行为上是对的，但排查时容易误读。
    privacyVersionPref.set(privacyPolicyVersion)
    agreementVersionPref.set(userAgreementVersion)
    agreedAtPref.set(agreedAtMillis)
    recordVersionPref.set(PrivacyGateManager.CONSENT_RECORD_VERSION)
    hasAgreedPref.set(true)
  }

  /**
   * 撤回授权。
   *
   * 只清授权相关键，**不动**服务器配置、播放记录等业务数据 ——
   * 撤回的是「同意」，不是「删除数据」。用户要删数据走「清除应用数据」或卸载。
   */
  fun revoke() {
    hasAgreedPref.delete()
    privacyVersionPref.delete()
    agreementVersionPref.delete()
    agreedAtPref.delete()
    recordVersionPref.delete()
  }

  private companion object {
    const val KEY_HAS_AGREED = "privacy_consent_agreed"
    const val KEY_PRIVACY_VERSION = "privacy_consent_privacy_version"
    const val KEY_AGREEMENT_VERSION = "privacy_consent_agreement_version"
    const val KEY_AGREED_AT = "privacy_consent_agreed_at"
    const val KEY_RECORD_VERSION = "privacy_consent_record_version"
  }
}
