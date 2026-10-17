package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/**
 * 取一个网络路径的**显示名**（顶栏标题 / 照片查看器标题 / 收藏名共用这一份口径）。
 *
 * ⚠️⚠️ 这里有个极隐蔽的坑，v81 才修掉，**别再改回旧写法**：
 *
 * `substringAfterLast(delimiter, missingDelimiterValue = this)` 的第二个参数是
 * **「找不到分隔符时的返回值」**，Kotlin 的默认值是 `this`（整个字符串）。
 * 旧代码显式传了 `""`：
 * ```kotlin
 * // ❌ 错：单段路径整串里没有 '/' ⇒ 返回 missingDelimiterValue = ""
 * currentPath.trimEnd('/').substringAfterLast('/', "").ifBlank { connectionName }
 * ```
 * 于是**单段路径**（如 `volume1`）会拿到空串，被 `.ifBlank` 退化成连接名 ——
 * 真机表现正是用户报的：「进 ABC 里的 volume1 挂载路径，顶栏还显示 ABC；
 * 再从 volume1 进『存储』就正常了」。根目录反而是好的（`trim` 完本就是空串，
 * 本来就该退化）⇒ **这个 bug 只在第一层露出来**，极易漏。
 *
 * 正确写法：`trim('/')` 归一化首尾斜杠 + `substringAfterLast('/')` **不传第二参**
 * （用默认的 `this`），单段路径原样返回、多段取最后一段、空串留给调用方退化。
 */
fun networkPathDisplayName(path: String, fallback: String): String {
  val trimmed = path.trim('/')
  return trimmed.substringAfterLast('/').ifBlank { fallback }
}
