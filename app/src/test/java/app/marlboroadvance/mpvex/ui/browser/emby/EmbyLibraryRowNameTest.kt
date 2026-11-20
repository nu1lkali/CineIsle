package app.marlboroadvance.mpvex.ui.browser.emby

import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.LibraryNameIndex
import app.marlboroadvance.mpvex.domain.emby.pathLastSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录导航「库根那一行显示什么」的回归测试。
 *
 * 场景固定成用户报障的那一个：媒体库名叫「Movies_HD」，挂载目录叫 `/mnt/data/HD`，
 * 下拉里本该显示「首页 / Movies_HD / A / B」，实际显示「首页 / HD / A / B」。
 *
 * ⚠️ 这些用例故意不碰网络：`resolveLibraryRowNames` 是纯函数，索引由调用方喂进来。
 * 这样换服务端形态只需要改用例，不用连服务器。
 */
class EmbyLibraryRowNameTest {

  private fun level(
    id: String,
    name: String?,
    parentId: String? = null,
    path: String? = null,
    type: String = "Folder",
  ) = EmbyItem(Id = id, Name = name, ParentId = parentId, Path = path, Type = type)

  private val library = "lib-1"
  private val libraryName = "Movies_HD"

  @Test
  fun `库根记在 Id 上时按 rootId 取库名`() {
    val chain = listOf(
      level("t", "媒体文件夹", type = "CollectionFolder"),
      level(library, libraryName, parentId = "t", type = "Folder"),
      level("m", "HD", parentId = library, path = "/mnt/data/HD"),
    )

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex(byId = mapOf(library to libraryName)),
      rootId = library,
      rootTitle = libraryName,
      libraryName = libraryName,
    )

    // 有库层在链上 ⇒ 库名贴在库层，下一层的 HD 保持原样（否则上下两行同名）
    assertEquals(mapOf(library to libraryName), names)
    assertFalse(names.containsKey("m"))
  }

  @Test
  fun `库根只以磁盘目录形态出现时按父级 Id 认库`() {
    val chain = listOf(
      level("t", "媒体文件夹", type = "CollectionFolder"),
      level("m", "HD", parentId = library, path = "/mnt/data/HD"),
      level("a", "A", parentId = "m", path = "/mnt/data/HD/A"),
    )

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex(byId = mapOf(library to libraryName)),
      rootId = library,
      rootTitle = libraryName,
      libraryName = libraryName,
    )

    assertEquals(mapOf("m" to libraryName), names)
  }

  @Test
  fun `乱码挂载目录按 Path 末段认库`() {
    val garbled = "/mnt/ZXCV-77"
    val chain = listOf(
      level("t", "媒体文件夹", type = "CollectionFolder"),
      level("m", "ZXCV-77", parentId = "t", path = garbled),
    )

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex(bySegment = mapOf("zxcv-77" to libraryName)),
      rootId = library,
      rootTitle = null,
      libraryName = libraryName,
    )

    assertEquals(mapOf("m" to libraryName), names)
  }

  @Test
  fun `没有索引时退到库名分段只认最浅一行`() {
    val chain = listOf(
      level("t", "媒体文件夹", type = "CollectionFolder"),
      level("m", "HD", parentId = "t", path = "/mnt/data/HD"),
      level("a", "A", parentId = "m"),
    )

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex.EMPTY,
      rootId = library,
      rootTitle = null,
      libraryName = libraryName,
    )

    assertEquals(mapOf("m" to libraryName), names)
  }

  @Test
  fun `分段启发式不误伤深层同名子目录`() {
    val chain = listOf(
      level("t", "媒体文件夹", type = "CollectionFolder"),
      level("d", "data", parentId = "t", path = "/data"),
      level("m", "Movies", parentId = "d", path = "/data/Movies"),
    )

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex.EMPTY,
      rootId = library,
      rootTitle = null,
      libraryName = "Movies 4K",
    )

    // 第 1 行是 data（和库名没有交集）⇒ 一行都不改，绝不把 Movies 换成 Movies 4K
    assertTrue(names.isEmpty())
  }

  @Test
  fun `认不出库时什么都不改`() {
    val chain = listOf(
      level("t", "媒体文件夹", type = "CollectionFolder"),
      level("m", "HD", parentId = "t", path = "/mnt/data/HD"),
    )

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex.EMPTY,
      rootId = null,
      rootTitle = null,
      libraryName = null,
    )

    assertTrue(names.isEmpty())
  }

  @Test
  fun `最外层一行永远不参与命名`() {
    // 只有一层祖先（最外层就是唯一一行）：它显示「首页」，不该被贴库名
    val chain = listOf(level(library, libraryName, type = "CollectionFolder"))

    val names = resolveLibraryRowNames(
      chain = chain,
      index = LibraryNameIndex(byId = mapOf(library to libraryName)),
      rootId = library,
      rootTitle = libraryName,
      libraryName = libraryName,
    )

    assertTrue(names.isEmpty())
  }

  @Test
  fun `目录名本身正确时不被 preferredName 之外的来源改写`() {
    val item = level("m", "HD", path = "/mnt/data/HD")
    assertEquals("HD", levelDisplayName(item))
    assertEquals("Movies_HD", levelDisplayName(item, preferredName = libraryName))
  }

  @Test
  fun `路径末段兼容反斜杠与尾部分隔符`() {
    assertEquals("HD", pathLastSegment("/mnt/data/HD"))
    assertEquals("HD", pathLastSegment("/mnt/data/HD/"))
    assertEquals("HD", pathLastSegment("D:\\media\\HD\\"))
    assertEquals("HD", pathLastSegment("smb://nas/HD"))
    assertEquals(null, pathLastSegment("/"))
    assertEquals(null, pathLastSegment(null))
  }
}
