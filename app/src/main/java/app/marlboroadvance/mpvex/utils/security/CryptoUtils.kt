package app.marlboroadvance.mpvex.utils.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段（Emby / SMB / FTP / WebDAV 的密码）的落盘加密。
 *
 * 为什么自己写而不用 `EncryptedSharedPreferences`：
 *  1. 它只作用于 SharedPreferences，这里的数据在 Room 里，用不上；
 *  2. `androidx.security:security-crypto` 已进入维护冻结状态，官方不再推进；
 *  3. 走 AndroidKeyStore 的 AES/GCM 是系统自带能力，零第三方依赖。
 *
 * 密钥由 Android Keystore 生成并保管 —— **密钥本身不可导出**，不落盘、不进备份，
 * 即使有人拿到 `mpvex.db` 文件（root、adb backup、云备份）也解不开密文。
 * 代价是：卸载重装 / 清除应用数据后密钥一并消失，旧密文解不开（此时按「密码为空」处理，
 * 让用户重新输一次即可）。
 *
 * 存储格式：`enc:v1:<base64(iv)>:<base64(ciphertext)>`
 *  - 带前缀的才是密文；不带前缀的一律按**旧版明文**原样返回，实现无缝兼容与渐进升级。
 *  - GCM 的 IV 每次加密随机生成（12 字节），所以同一个明文每次密文都不同，无法比对。
 */
object CryptoUtils {
  private const val TAG = "CryptoUtils"
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS = "mpvex_secret_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val TAG_LENGTH_BITS = 128

  /** 密文前缀。刻意避开 base64 字母表，方便用 `:` 直接切分 IV 与密文。 */
  private const val PREFIX = "enc:v1:"

  @Volatile
  private var cachedKey: SecretKey? = null

  /**
   * 是否是本工具产出的密文。
   *
   * 只认前缀、不去尝试解密 —— 用来判断「还需要升级成密文吗」。
   */
  fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

  /**
   * 加密。[plain] 为空或已经是密文时原样返回（幂等，重复保存不会包两层）。
   *
   * 加密失败时返回空串而不是退回明文 —— 宁可让用户重输一次密码，
   * 也不能因为「加密出了点问题」就把密码以明文写进数据库。
   */
  fun encrypt(plain: String?): String {
    val value = plain.orEmpty()
    if (value.isEmpty() || isEncrypted(value)) return value

    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, secretKey())
      val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
      PREFIX +
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
        ":" +
        Base64.encodeToString(payload, Base64.NO_WRAP)
    } catch (e: Throwable) {
      Log.e(TAG, "encrypt failed; refusing to store plaintext", e)
      ""
    }
  }

  /**
   * 解密。
   *
   * - 空串 / null → 空串
   * - 不带前缀 → 原样返回（旧版明文，向后兼容）
   * - 带前缀但解不开（密钥丢失、数据损坏）→ 返回空串并记日志，
   *   调用方按「没存密码」处理，而不是抛异常把界面炸掉。
   */
  fun decrypt(stored: String?): String {
    val value = stored.orEmpty()
    if (value.isEmpty()) return value
    if (!isEncrypted(value)) return value

    return try {
      val body = value.removePrefix(PREFIX)
      val separator = body.indexOf(':')
      if (separator <= 0) return ""

      val iv = Base64.decode(body.substring(0, separator), Base64.NO_WRAP)
      val payload = Base64.decode(body.substring(separator + 1), Base64.NO_WRAP)

      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
      String(cipher.doFinal(payload), Charsets.UTF_8)
    } catch (e: Throwable) {
      Log.e(TAG, "decrypt failed (keystore key rotated or lost?) -> treating as empty", e)
      ""
    }
  }

  /**
   * 取出（必要时创建）Keystore 里的 AES 密钥。
   *
   * `AndroidKeyStore` 里的密钥生成一次就长期驻留，读一次缓存起来即可 ——
   * 每次 `KeyStore.load` 都要过一次 keystore 进程，热路径上不该反复走。
   */
  private fun secretKey(): SecretKey {
    cachedKey?.let { return it }

    return synchronized(this) {
      cachedKey?.let { return it }

      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
      (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
        cachedKey = it
        return it
      }

      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
      generator.init(
        KeyGenParameterSpec
          .Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          // 随机 IV：同一个密码每次密文都不同，避免「密文相同 → 反推密码相同」
          .setRandomizedEncryptionRequired(true)
          // 不要求锁屏验证：本应用可能在无锁屏的设备/电视盒子上跑，
          // 绑了用户认证会出现「解不开密码导致连不上服务器」这种莫名其妙的故障。
          .setUserAuthenticationRequired(false)
          .build(),
      )

      generator.generateKey().also { cachedKey = it }
    }
  }
}
