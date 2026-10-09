package app.marlboroadvance.mpvex.domain.emby

import app.marlboroadvance.mpvex.database.entities.DownloadedMediaEntity
import app.marlboroadvance.mpvex.database.entities.EmbyDownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下载任务 / 已下载内容的映射测试（清单 4.2）。
 *
 * 映射层是最容易出**静默**错误的地方：少映射一个字段不会崩、不会红，
 * 只会在用户那儿表现为「下载记录丢了进度」「离线列表里剧名不见了」。
 * 所以逐个字段断言往返（round-trip）相等。
 */
class EmbyDownloadMapperTest {

  private fun task(
    itemId: String = "item1",
    serverId: Long = 7L,
    title: String = "测试片",
    fileName: String = "测试片.mkv",
    url: String = "http://h/Items/item1/Download?api_key=k",
    totalBytes: Long = 1024L,
    downloadedBytes: Long = 512L,
    status: EmbyDownloadStatus = EmbyDownloadStatus.RUNNING,
    message: String? = "连接已断开，可继续下载",
    targetUri: String? = "content://media/1",
    locationLabel: String? = "Download/CineIsle/测试片.mkv",
    createdAt: Long = 1_700_000_000_000L,
    speedBps: Long = 999_999L,
  ) = EmbyDownloadTask(
    itemId = itemId, serverId = serverId, title = title, fileName = fileName, url = url,
    totalBytes = totalBytes, downloadedBytes = downloadedBytes, status = status, message = message,
    targetUri = targetUri, locationLabel = locationLabel, createdAt = createdAt,
    speedBps = speedBps,
  )

  // ── 任务对象 → Room 行 ──

  @Test
  fun `任务转实体逐字段对应`() {
    val t = task()
    val e = t.toEntity()
    assertEquals(t.itemId, e.itemId)
    assertEquals(t.serverId, e.serverId)
    assertEquals(t.title, e.title)
    assertEquals(t.fileName, e.fileName)
    assertEquals(t.url, e.url)
    assertEquals(t.totalBytes, e.totalBytes)
    assertEquals(t.downloadedBytes, e.downloadedBytes)
    assertEquals(t.message, e.message)
    assertEquals(t.targetUri, e.targetUri)
    assertEquals(t.locationLabel, e.locationLabel)
    assertEquals(t.createdAt, e.createdAt)
  }

  @Test
  fun `状态存的是枚举名而不是 ordinal`() {
    // 存 ordinal 的老坑：往枚举中间插一个值，库里所有老记录的语义全错且不报错。
    assertEquals("RUNNING", task(status = EmbyDownloadStatus.RUNNING).toEntity().status)
    assertEquals("PAUSED", task(status = EmbyDownloadStatus.PAUSED).toEntity().status)
    assertEquals("COMPLETED", task(status = EmbyDownloadStatus.COMPLETED).toEntity().status)
  }

  @Test
  fun `速度不进 Room`() {
    // 存进去只会在重启后显示成一个早就失效的旧数字，比显示 0 更误导
    val t = task(speedBps = 123_456L)
    // 实体的字段里根本没有 speedBps —— 用「往返后速度归零」间接断言
    assertEquals(0L, t.toEntity().toTask().speedBps)
  }

  // ── Room 行 → 任务对象 ──

  @Test
  fun `实体转任务逐字段对应`() {
    val e = EmbyDownloadEntity(
      itemId = "x", serverId = 3L, title = "剧", fileName = "剧.mp4", url = "u",
      totalBytes = 100L, downloadedBytes = 50L, status = "PAUSED",
      message = "m", targetUri = "file:///a.mp4", locationLabel = "l", createdAt = 42L,
    )
    val t = e.toTask()
    assertEquals("x", t.itemId)
    assertEquals(3L, t.serverId)
    assertEquals("剧", t.title)
    assertEquals("剧.mp4", t.fileName)
    assertEquals(100L, t.totalBytes)
    assertEquals(50L, t.downloadedBytes)
    assertEquals(EmbyDownloadStatus.PAUSED, t.status)
    assertEquals("m", t.message)
    assertEquals(42L, t.createdAt)
  }

  @Test
  fun `未知状态名兜底成已暂停而不是崩`() {
    // 枚举将来被删/改名时，库里存的老数据不该让整个 App 冷启动就崩。
    // 兜成 PAUSED 的语义是「有这条记录但现在没在跑」，用户点继续即可。
    val e = EmbyDownloadEntity(
      itemId = "x", serverId = 1L, title = "t", fileName = "f", url = "u",
      status = "SOMETHING_REMOVED_IN_FUTURE_VERSION",
    )
    assertEquals(EmbyDownloadStatus.PAUSED, e.toTask().status)
  }

  @Test
  fun `状态名大小写不符也兜底而不是崩`() {
    val e = EmbyDownloadEntity(
      itemId = "x", serverId = 1L, title = "t", fileName = "f", url = "u", status = "running",
    )
    assertEquals(EmbyDownloadStatus.PAUSED, e.toTask().status)
  }

  @Test
  fun `任务往返一次所有持久化字段不变`() {
    val original = task()
    val round = original.toEntity().toTask()
    // 逐字段比，而不是 assertEquals(original, round)：
    // speedBps 是**刻意**不落盘的（它是实时速度，冷启动后没有意义，
    // 存进去只会让老记录带着一个过期的数字误导 UI），所以 data class 的
    // 整体 equals 在这里本来就不该成立。少一个字段不写进测试，将来
    // 谁再漏映射一个字段就没人拦了。
    assertEquals(original.itemId, round.itemId)
    assertEquals(original.serverId, round.serverId)
    assertEquals(original.title, round.title)
    assertEquals(original.fileName, round.fileName)
    assertEquals(original.url, round.url)
    assertEquals(original.totalBytes, round.totalBytes)
    assertEquals(original.downloadedBytes, round.downloadedBytes)
    assertEquals(original.status, round.status)
    assertEquals(original.message, round.message)
    assertEquals(original.targetUri, round.targetUri)
    assertEquals(original.locationLabel, round.locationLabel)
    assertEquals(original.createdAt, round.createdAt)
  }

  @Test
  fun `速度不落盘 冷启动后归零`() {
    // 钉住上面那条设计：speedBps 只活在内存里。
    // 如果哪天有人「顺手」把它加进 Entity，下载管理页会显示一个
    // 上次会话遗留的僵尸速度，而且不会有任何报错提示。
    val round = task(speedBps = 999_999L).toEntity().toTask()
    assertEquals(0L, round.speedBps)
  }

  // ── EmbyItem → 已下载内容行 ──

  @Test
  fun `没有 ItemId 时不生成记录`() {
    // 没有 Id 就没法跟任务表对应，也没法重连服务器，直接放弃比生成一条孤儿记录好
    assertNull(EmbyItem(Id = null, Name = "无 id").toDownloadedMediaEntity(serverId = 1L))
  }

  @Test
  fun `剧集条目带上季集号与剧名`() {
    val item = EmbyItem(
      Id = "ep1", Name = "第一集", Type = "Episode", SeriesName = "测试剧",
      ParentIndexNumber = 2, IndexNumber = 5, RunTimeTicks = 1_400_000_000L,
      ProductionYear = 2021, Overview = "简介", CommunityRating = 8.5,
      OfficialRating = "TV-14", ImageTags = mapOf("Primary" to "tag123", "Backdrop" to "bd"),
    )
    val e = item.toDownloadedMediaEntity(serverId = 5L)!!
    assertEquals("ep1", e.itemId)
    assertEquals(5L, e.serverId)
    assertEquals("第一集", e.title)
    assertEquals("Episode", e.type)
    assertEquals("测试剧", e.seriesName)
    assertEquals(2, e.seasonNumber)
    assertEquals(5, e.episodeNumber)
    assertEquals("简介", e.overview)
    assertEquals(2021, e.productionYear)
    // ⚠️ communityRating 在 EmbyItem 里是 Double?（Emby 可能不返回评分），
    // 断言时要用 assertEquals(Double?, Double?, delta) 这个三参重载 ——
    // 直接写 assertEquals(8.5, e.communityRating, 0.0001) 会因为
    // 「Double? 不能传给 Double 形参」编译失败。
    assertEquals(8.5, e.communityRating!!, 0.0001)
    assertEquals("TV-14", e.officialRating)
  }

  @Test
  fun `只取 Primary 海报 tag 不取 Backdrop`() {
    // 离线页卡片用竖版海报；Backdrop 是横图，取了也用不上，只是白占一列
    val item = EmbyItem(
      Id = "m1", Type = "Movie",
      ImageTags = mapOf("Primary" to "p", "Backdrop" to "b", "Logo" to "l"),
    )
    assertEquals("p", item.toDownloadedMediaEntity(1L)!!.imageTag)
  }

  @Test
  fun `没有海报时 tag 为 null 而不是空串`() {
    // 空串会让 `ImageTags["Primary"]` 式的判空失效，图片 URL 拼出 "xxx" 这种脏值
    val e = EmbyItem(Id = "m1", Type = "Movie", ImageTags = emptyMap())
      .toDownloadedMediaEntity(1L)!!
    assertNull(e.imageTag)
  }

  @Test
  fun `片名为空时退回剧名再退回未命名`() {
    val withSeries = EmbyItem(Id = "e1", Name = null, SeriesName = "剧名")
    assertEquals("剧名", withSeries.toDownloadedMediaEntity(1L)!!.title)
    val neither = EmbyItem(Id = "e2", Name = null, SeriesName = null)
    assertEquals("未命名", neither.toDownloadedMediaEntity(1L)!!.title)
  }

  @Test
  fun `Emby 没给评分时保持 null 而不是塞 0`() {
    // 塞 0 的话离线列表会给没评分的片子显示「0.0 分」，比不显示更糟
    val e = EmbyItem(Id = "m1", Type = "Movie", CommunityRating = null)
      .toDownloadedMediaEntity(1L)!!
    assertNull(e.communityRating)
  }

  @Test
  fun `入队时 localUri 为空且文件大小未知`() {
    // 判据是 localUri IS NOT NULL —— 入队行不该出现在离线内容页里
    val e = EmbyItem(Id = "m1", Type = "Movie").toDownloadedMediaEntity(1L)!!
    assertNull(e.localUri)
    assertEquals(-1L, e.fileSize)
    assertEquals(0L, e.completedAt)
  }

  @Test
  fun `下载完成时传入的落盘信息会写进同一行`() {
    val e = EmbyItem(Id = "m1", Type = "Movie")
      .toDownloadedMediaEntity(
        serverId = 1L,
        localUri = "content://media/external/downloads/1",
        fileSize = 2048L,
        completedAt = 1_700_000_000_000L,
      )!!
    assertEquals("content://media/external/downloads/1", e.localUri)
    assertEquals(2048L, e.fileSize)
    assertEquals(1_700_000_000_000L, e.completedAt)
  }
}
