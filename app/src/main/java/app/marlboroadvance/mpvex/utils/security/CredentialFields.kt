package app.marlboroadvance.mpvex.utils.security

import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import app.marlboroadvance.mpvex.domain.network.NetworkConnection

/**
 * 凭据字段的「内存明文 / 落盘密文」双向转换。
 *
 * 设计约定：**数据库里永远是密文，内存对象里永远是明文。**
 * 这样上层所有业务代码（鉴权、建连接、列目录、拼 URL）都不用关心加密这件事，
 * 只需要在 Repository 的读写边界上套一层转换。
 */

// ─── Emby ───

/** 读：落盘密文 → 明文。 */
fun EmbyServer.withDecryptedPassword(): EmbyServer =
  if (CryptoUtils.isEncrypted(password)) copy(password = CryptoUtils.decrypt(password)) else this

/** 写：明文 → 落盘密文（已是密文则原样返回，幂等）。 */
fun EmbyServer.withEncryptedPassword(): EmbyServer =
  if (password.isEmpty() || CryptoUtils.isEncrypted(password)) {
    this
  } else {
    copy(password = CryptoUtils.encrypt(password))
  }

// ─── 网络连接（SMB / FTP / WebDAV）───

/** 读：落盘密文 → 明文。 */
fun NetworkConnection.withDecryptedPassword(): NetworkConnection =
  if (CryptoUtils.isEncrypted(password)) copy(password = CryptoUtils.decrypt(password)) else this

/** 写：明文 → 落盘密文（已是密文则原样返回，幂等）。 */
fun NetworkConnection.withEncryptedPassword(): NetworkConnection =
  if (password.isEmpty() || CryptoUtils.isEncrypted(password)) {
    this
  } else {
    copy(password = CryptoUtils.encrypt(password))
  }
