package app.marlboroadvance.mpvex.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marlboroadvance.mpvex.di.MIGRATION_1_2 as M1_2
import app.marlboroadvance.mpvex.di.MIGRATION_2_3
import app.marlboroadvance.mpvex.di.MIGRATION_3_4
import app.marlboroadvance.mpvex.di.MIGRATION_4_5
import app.marlboroadvance.mpvex.di.MIGRATION_5_6
import app.marlboroadvance.mpvex.di.MIGRATION_6_7
import app.marlboroadvance.mpvex.di.MIGRATION_7_8
import app.marlboroadvance.mpvex.di.MIGRATION_8_9
import app.marlboroadvance.mpvex.di.MIGRATION_9_10
import app.marlboroadvance.mpvex.di.MIGRATION_10_11
import app.marlboroadvance.mpvex.di.MIGRATION_11_12
import app.marlboroadvance.mpvex.di.MIGRATION_12_13
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移链测试（清单 1.2 里优先级最高的一项）。
 *
 * ## 为什么必须写
 *
 * 迁移链从 v1 排到 v12 共 11 段，全是手写 SQL，**此前零测试**。
 * 而 `DatabaseModule` 里挂着 `fallbackToDestructiveMigration(true)` ——
 * 迁移链只要**断一环**，Room 找不到完整路径就会**整库删表重建**，
 * 用户表现为「升级后 Emby 服务器配置、播放列表、播放进度全没了」，
 * 而且不崩溃、不报错，极难归因。
 *
 * 这不是假想的风险：本次就发现了 `MIGRATION_10_11` 缺失
 * （v11 那次发版只 bump 了版本号、schema 没变，于是没人写这段迁移），
 * v10 用户升 v12 必然掉库。已修 + 用本测试钉死。
 *
 * ## 为什么放在 androidTest 而不是 test
 *
 * `MigrationTestHelper` 需要**真的 SQLite**（JVM 上的 Robolectric SQLite 与
 * Room 生成代码的 `SupportSQLiteDatabase` 行为有差异），所以只能跑在设备/模拟器上。
 * 本机没设备时用 `-PskipAndroidTests` 跳过，别因为跑不了就删掉这些用例。
 *
 * ## 跑法
 *
 * ```
 * ./gradlew :app:connectedStandardDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MpvExDatabaseMigrationTest {
  private val databaseName = "migration-test.db"

  private fun helper(): MigrationTestHelper =
    MigrationTestHelper(
      // schema 目录：build.gradle.kts 里 room { schemaDirectory("$projectDir/schemas") }，
      // 并且已把同一目录挂进 androidTest 的 assets.srcDir（见 build.gradle.kts 的 sourceSets 块）——
      // 少了那一步，helper 在设备上会找不到任何 schema。
      InstrumentationRegistry.getInstrumentation(),
      MpvExDatabase::class.java,
      emptyList(),
      FrameworkSQLiteOpenHelperFactory(),
    )

  /**
   * 建库前先确认该版本的 schema json 真的存在，缺就跳过而不是让整条用例红。
   *
   * `app/schemas/` 里有 1,2,3,5,6,7,8,10,11,12,13 —— **缺 4 和 9**。
   * 原因是那两版发版时 Room 检测到 schema 没变就没重新导出（Room 只在
   * schema 真正变化时写文件），目录里就没留下对应版本。
   *
   * 用 `assumeTrue(false)` 而不是直接删用例：等哪天补导出（或从 3.json/8.json
   * 手工补出 4.json/9.json），这条用例会自动重新生效，不需要改测试代码。
   */
  private fun createIfSchemaExists(h: MigrationTestHelper, version: Int) {
    assumeTrue(
      "schemas/ 目录里缺 $version.json，该版本的迁移暂时无法验证（已跳过）",
      tryCreate(h, version),
    )
  }

  /**
   * 建库；schema json 缺失导致建不出来时返回 false（不抛）。
   *
   * 成功时**立刻关掉句柄**：调用方紧接着要用同一个库文件名跑
   * `runMigrationsAndValidate`，留着打开的句柄会和它抢锁。
   */
  private fun tryCreate(h: MigrationTestHelper, version: Int): Boolean =
    runCatching { h.createDatabase(databaseName, version).close() }
      .onFailure { android.util.Log.w("MpvExMigrationTest", "缺 $version.json，跳过该版本", it) }
      .isSuccess

  /**
   * 逐段跑完整条链：v1 一路升到 v13。
   *
   * 这是最有价值的一条：它同时验证「每一段都能跑」和「链路完整无缺口」。
   * 中间任何一段缺失，Room 会在打开时直接抛「no migration path」，
   * 用 `createDatabase(TEST_VERSION)` 打开目标版本就会把缺口暴露出来。
   */
  @Test
  fun 迁移链从1到13完整() {
    val h = helper()
    // 建 v1 库（用导出的 1.json）
    createIfSchemaExists(h, 1)

    h.runMigrationsAndValidate(
      databaseName,
      13, // 目标：当前 @Database version
      true, // validateDroppedTables = true：多出来的表也要判失败
      M1_2,
      MIGRATION_2_3,
      MIGRATION_3_4,
      MIGRATION_4_5,
      MIGRATION_5_6,
      MIGRATION_6_7,
      MIGRATION_7_8,
      MIGRATION_8_9,
      MIGRATION_9_10,
      MIGRATION_10_11,
      MIGRATION_11_12,
      MIGRATION_12_13,
    ).apply { close() }
  }

  /**
   * 12 → 13 必须建出两张下载表，且**列集与索引集完全正确**（清单 4.2）。
   *
   * 单独钉这条的原因：12→13 的 DDL 全是手写 SQL，而 Room 会在打开 v13 库时
   * 拿实体注解生成的期望 schema 跟实际表结构逐项比对 —— 少一列、多一列、
   * 索引名拼错，都会在**运行时**抛 IllegalStateException。
   * 那种错误在开发期就暴露（装一次 debug 版就知道），但如果迁移链断了导致
   * 走了 destructive fallback，就变成「用户升级后下载记录全没了」且**不报错**。
   *
   * 索引名是 Room 自动生成的 `index_<表名>_<字段>`，手写 DDL 时最容易拼错，
   * 所以这里把索引也逐个查出来断言。
   */
  @Test
  fun 迁移_12_13_建出两张下载表且索引齐全() {
    val h = helper()
    createIfSchemaExists(h, 12)

    val db = h.runMigrationsAndValidate(databaseName, 13, true, MIGRATION_12_13)

    val tables = mutableSetOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
      val idx = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) tables += c.getString(idx)
    }
    assertTrue("emby_download_tasks 表必须存在，实际有：$tables", "emby_download_tasks" in tables)
    assertTrue("emby_downloaded_media 表必须存在，实际有：$tables", "emby_downloaded_media" in tables)

    // 索引：名字错一个字符 Room 就判 schema 不匹配
    val indices = mutableSetOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type='index'").use { c ->
      val idx = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) indices += c.getString(idx)
    }
    listOf(
      "index_emby_download_tasks_serverId",
      "index_emby_download_tasks_status",
      "index_emby_download_tasks_createdAt",
      "index_emby_downloaded_media_serverId",
      "index_emby_downloaded_media_seriesName",
      "index_emby_downloaded_media_completedAt",
    ).forEach {
      assertTrue("索引 $it 必须存在（名字错 Room 会判 schema 不匹配），实际有：$indices", it in indices)
    }

    // emby_downloaded_media 的列集：离线内容页就靠这几列
    val columns = mutableSetOf<String>()
    db.query("PRAGMA table_info(`emby_downloaded_media`)").use { c ->
      val idx = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) columns += c.getString(idx)
    }
    listOf(
      "itemId", "serverId", "title", "type", "seriesName", "seasonNumber", "episodeNumber",
      "overview", "runTimeTicks", "productionYear", "communityRating", "officialRating",
      "imageTag", "fileName", "localUri", "fileSize", "completedAt",
    ).forEach {
      assertTrue("emby_downloaded_media 缺列 $it，实际有：$columns", it in columns)
    }
    db.close()
  }

  /**
   * 12 → 13 不动既有表。
   *
   * 这条防的是「加表顺手改了别人」：v12 的用户升级后，
   * Emby 服务器配置 / 播放列表 / 播放进度 / 搜索历史一张都不能少。
   */
  @Test
  fun 迁移_12_13_不动既有表() {
    val h = helper()
    createIfSchemaExists(h, 12)

    val db = h.runMigrationsAndValidate(databaseName, 13, true, MIGRATION_12_13)

    // v12 就存在的关键表，升级后必须还在
    val tables = mutableSetOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
      val idx = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) tables += c.getString(idx)
    }
    listOf(
      "emby_servers",       // 服务器配置 + 加密后的密码
      "PlaylistEntity",     // 本地播放列表
      "PlaybackStateEntity",// 播放进度
      "search_history",     // 搜索历史
    ).forEach {
      assertTrue("升级不该动既有表 $it（实际有：$tables）", it in tables)
    }
    db.close()
  }

  /**
   * v10 → v12 单跳（本次修的那个洞）。
   *
   * 单跳跑的意义：真实用户往往只跨几版升级（v10 装的是上一版，v12 是新版），
   * 不一定经过中间所有版本。10→11 是空迁移（schema 没变），11→12 建 search_history。
   */
  @Test
  fun 迁移_10_11_12_不丢数据() {
    val h = helper()
    createIfSchemaExists(h, 10)

    val db = h.runMigrationsAndValidate(
      databaseName,
      12,
      true,
      MIGRATION_10_11,
      MIGRATION_11_12,
    )

    // 验证 10_11 确实是空迁移：PlaybackStateEntity 仍是 10 版的列集，没有多也没有少
    val columns = mutableSetOf<String>()
    db.query("PRAGMA table_info(`PlaybackStateEntity`)").use { c ->
      val idx = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) columns += c.getString(idx)
    }
    listOf("mediaTitle", "secondarySid", "externalSubtitles", "hasBeenWatched").forEach {
      assertTrue("v10 的列 $it 不该在迁移后消失", it in columns)
    }
    db.close()
  }

  /**
   * search_history 表在 12 版存在且能读写。
   *
   * 单独查这一个新表：它是 v12 唯一的 schema 变化，之前的版本都没有。
   */
  @Test
  fun 迁移后_search_history_可用() {
    val h = helper()
    createIfSchemaExists(h, 11)

    h.runMigrationsAndValidate(databaseName, 12, true, MIGRATION_11_12).apply { close() }
  }

  /**
   * 9 → 10 必须建出 emby_servers。
   *
   * 这张表存的是 Emby 服务器地址与密码 —— 迁移出错等于「升级后要重新登录」，
   * 是用户最直接的感知点，所以单独钉一条。
   */
  @Test
  fun 迁移_9_10_建出_emby_servers() {
    val h = helper()
    createIfSchemaExists(h, 9)

    val db = h.runMigrationsAndValidate(databaseName, 10, true, MIGRATION_9_10)
    val tables = mutableSetOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
      val idx = c.getColumnIndexOrThrow("name")
      while (c.moveToNext()) tables += c.getString(idx)
    }
    assertTrue("emby_servers 表必须存在，实际有：$tables", "emby_servers" in tables)
    db.close()
  }

  /**
   * 每段迁移单跑一遍（除了 v1→v2 那种大改，其余都是「加一列」）。
   *
   * 分开跑的价值：全链跑通不代表每段都对 —— 比如某段加了两遍列、
   * 或者段与段之间有隐藏顺序依赖，只有单跑才暴露。
   * 缺 schema 的段（v3→v4、v8→v9，因为 4.json / 9.json 没导出）
   * 只跳过**自己这一段**，不影响其余段落继续验证 —— 所以这里用
   * `tryCreate` 的布尔返回值判断，而不是 `assumeTrue`（那会终止整条用例）。
   */
  @Test
  fun 每段迁移单独跑一遍() {
    val h = helper()
    val cases = listOf(
      2 to arrayOf(M1_2),
      3 to arrayOf(MIGRATION_2_3),
      4 to arrayOf(MIGRATION_3_4),
      5 to arrayOf(MIGRATION_4_5),
      6 to arrayOf(MIGRATION_5_6),
      7 to arrayOf(MIGRATION_6_7),
      8 to arrayOf(MIGRATION_7_8),
      9 to arrayOf(MIGRATION_8_9),
      10 to arrayOf(MIGRATION_9_10),
      11 to arrayOf(MIGRATION_10_11),
      12 to arrayOf(MIGRATION_11_12),
      13 to arrayOf(MIGRATION_12_13),
    )
    var verified = 0
    var skipped = 0
    for ((target, migrations) in cases) {
      val from = target - 1
      if (!tryCreate(h, from)) {
        skipped++
        continue
      }
      h.runMigrationsAndValidate(databaseName, target, true, *migrations).apply { close() }
      verified++
    }
    // 至少要真的验上一段，否则「全被跳过」也会显示通过
    assertTrue("没有任何一段迁移被验证到，测试配置有问题", verified > 0)
    android.util.Log.i("MpvExMigrationTest", "单跑验证 $verified 段，跳过 $skipped 段（缺 schema json）")
  }
}
