package app.marlboroadvance.mpvex.domain.danmaku

import android.util.Base64
import java.security.MessageDigest

/**
 * dandanplay 开放弹幕网络的请求签名 —— 纯逻辑，无 Android 依赖（除 Base64），可 JVM 单测。
 *
 * ## 算法（官方 `doc.dandanplay.com/open`）
 *
 * ```
 * X-Signature = base64( sha256( AppId + Timestamp + Path + AppSecret ) )
 * ```
 *
 * 三个坑，按重要性排：
 *
 * 1. **Path 不含 query 也不含域名**。`https://api.dandanplay.net/api/v2/comment/123?withRelated=true`
 *    的签名 Path 是 `/api/v2/comment/123`。把 `?withRelated=true` 算进去 ⇒ 签名不匹配。
 * 2. **Base64 编码的是原始字节，不是十六进制串**。
 *    `Base64(sha256(...))` ≠ `Base64(hex(sha256(...)))`。官方六种语言的示例里
 *    Java 用 `digest()`、PHP 用 `hash(..., true)`、Go 用 `hash[:]` —— 都是原始字节。
 * 3. **时间戳单位是秒**，且必须是 **UTC** 的 Unix 时间戳。
 *    用 `System.currentTimeMillis() / 1000`（本机时区无关，恰好就是 Unix 秒）可以，
 *    但绝不能传毫秒 —— 服务端回 `Invalid Timestamp`。
 *
 * ## 为什么签名模式而不是凭证模式
 *
 * 官方提供两种：`X-AppSecret` 明文放头里（凭证模式），或算签名（签名模式）。
 * 我们用签名模式 —— 密钥不随每个请求明文发出，客户端被逆向了也拿不到长期凭据。
 */
object DanmakuSignature {

  /**
   * 算 `X-Signature`。
   *
   * @param appId 应用 ID
   * @param appSecret 应用密钥（生产用密钥 1；密钥 2 是给审核/测试用的）
   * @param path **只含路径**，不带域名、不带 query（如 `/api/v2/comment/123`）
   * @param timestampSec Unix 时间戳（**秒**）
   */
  fun sign(appId: String, appSecret: String, path: String, timestampSec: Long): String {
    val data = appId + timestampSec + path + appSecret
    val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
    // ⚠️ NO_WRAP：Base64 默认会每 76 字符插一个换行，而这是 HTTP 头，插换行会让服务端解析失败。
    return Base64.encodeToString(digest, Base64.NO_WRAP)
  }

  /**
   * 把 [pathWithQuery] 截成签名用的 [path]。
   *
   * 单独一个函数是因为这是最容易写错的一步，且写错了只会得到一个
   * 空 body 的 403 —— 没有堆栈、没有提示，排查起来极其痛苦。
   *
   * ⚠️ 只截 query，**不做 URL 解码也不改大小写**：官方要求「建议小写」而非「必须小写」，
   * 而我们自己的路径本来就是小写构造的，解码反而可能引入不一致。
   */
  fun pathOf(pathWithQuery: String): String = pathWithQuery.substringBefore('?')
}