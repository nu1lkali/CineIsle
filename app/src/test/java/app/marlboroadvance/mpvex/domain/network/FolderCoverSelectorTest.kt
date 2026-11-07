package app.marlboroadvance.mpvex.domain.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹封面选择口径的测试。
 *
 * 这一组断言就是「卡片上那张图应该是哪一张」的**契约**：
 * 视频海报优先 → 回退图片 → 仅视频也走视频海报 → 都没有就留给上层穿透。
 * 每次改动 `pickFolderCover` 都先跑这里，别靠真机翻目录去验。
 */
class FolderCoverSelectorTest {
  private fun file(
    name: String,
    thumb: String? = null,
    dir: Boolean = false,
  ): NetworkFile =
    NetworkFile(
      name = name,
      path = "库/$name",
      size = 0,
      isDirectory = dir,
      thumb = thumb,
    )

  private val posterA = "https://alist.example.com/d/%E5%BA%93/a.mkv?sign=aaa"
  private val posterB = "https://alist.example.com/d/%E5%BA%93/b.mkv?sign=bbb"

  @Test
  fun `视频有海报时优先用视频海报，即使图片名更靠前`() {
    val pick =
      pickFolderCover(
        listOf(
          file("001-cover.jpg"),
          file("a.mkv", thumb = posterA),
          file("b.mkv", thumb = posterB),
        ),
      )

    assertEquals(FolderCoverKind.VIDEO_THUMB, pick?.kind)
    assertEquals(posterA, pick?.ref)
  }

  @Test
  fun `视频没有海报时回退到第一张图片`() {
    val pick =
      pickFolderCover(
        listOf(
          file("a.mkv"),
          file("cover.png"),
        ),
      )

    assertEquals(FolderCoverKind.IMAGE, pick?.kind)
    assertEquals("库/cover.png", pick?.ref)
  }

  @Test
  fun `仅含图片时取名称自然序第一张`() {
    val pick =
      pickFolderCover(
        listOf(
          file("img10.jpg"),
          file("img9.jpg"),
          file("img100.jpg"),
        ),
      )

    // 字典序会把 img10 判成最小 —— 这里必须是自然序（与服务端返回顺序无关）
    assertEquals(FolderCoverKind.IMAGE, pick?.kind)
    assertEquals("库/img9.jpg", pick?.ref)
  }

  @Test
  fun `仅含视频时同样拿视频海报做封面`() {
    val pick = pickFolderCover(listOf(file("e01.mkv", thumb = posterA)))

    assertEquals(FolderCoverKind.VIDEO_THUMB, pick?.kind)
    assertEquals(posterA, pick?.ref)
  }

  @Test
  fun `仅含视频但都没有海报时没有封面`() {
    // 不自行截帧（需求明确「封面全部由 AList 后端提供」）
    assertNull(pickFolderCover(listOf(file("e01.mkv"), file("e02.mp4"))))
  }

  @Test
  fun `只有子文件夹时本级没有封面`() {
    assertNull(pickFolderCover(listOf(file("第 1 季", dir = true), file("第 2 季", dir = true))))
  }

  @Test
  fun `空目录没有封面`() {
    assertNull(pickFolderCover(emptyList()))
  }

  @Test
  fun `子文件夹按自然序排队，文件不参与穿透`() {
    val children =
      folderCoverChildDirs(
        listOf(
          file("第 10 季", dir = true),
          file("第 2 季", dir = true),
          file("a.mkv"),
        ),
      )

    assertEquals(listOf("第 2 季", "第 10 季"), children.map { it.name })
  }

  @Test
  fun `封面引用的形态判据`() {
    assertTrue(isRemoteCoverRef(posterA))
    assertTrue(isRemoteCoverRef("HTTP://host/d/a.jpg"))
    assertTrue(!isRemoteCoverRef("库/img9.jpg"))
    assertTrue(!isRemoteCoverRef("/d/a.jpg"))
  }
}
