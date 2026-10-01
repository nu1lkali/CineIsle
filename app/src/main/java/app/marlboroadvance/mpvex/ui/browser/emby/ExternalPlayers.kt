package app.marlboroadvance.mpvex.ui.browser.emby

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 「用外部播放器打开」的一个候选。
 *
 * **一个应用只会出现一次** —— 同一个包里有多个 Activity 命中视频类型时（MX Player、
 * KMPlayer 都有这种注册方式）合并成一条，否则列表里会出现三行一模一样的「MX Player」。
 */
data class ExternalPlayerOption(
  val packageName: String,
  val activityName: String,
  val label: String,
  /** 已知播放器的推荐位次，越小越靠前；没收录的应用统一排到已知播放器之后 */
  val rank: Int,
) {
  /** 持久化用的稳定键（记住上次选了谁） */
  val key: String get() = "$packageName/$activityName"
}

/** 常见播放器的展示名与推荐位次 */
private class KnownPlayer(val label: String, val rank: Int)

/**
 * 常见播放器（按包名收录）。
 *
 * 两个作用：
 *  1. **排序**：MX / VLC 这类真正拿来看片的排最前，排在「文件管理」「微信」这类
 *     顺带注册了视频类型的应用前面；
 *  2. **兜底显示名**：个别播放器 `loadLabel` 拿不到名字时用这里的中文/通用名顶上。
 *
 * 包名要和 [AndroidManifest] 里 `<queries>` 的 `<package>` 声明一一对应 ——
 * 那边少一条，这边对应的播放器在 Android 11+ 上就可能探测不到。
 */
private val KNOWN_EXTERNAL_PLAYERS: Map<String, KnownPlayer> =
  linkedMapOf(
    "com.mxtech.videoplayer.pro" to KnownPlayer("MX Player Pro", 10),
    "com.mxtech.videoplayer.ad" to KnownPlayer("MX Player", 11),
    "org.videolan.vlc" to KnownPlayer("VLC", 20),
    "com.newin.nplayer.pro" to KnownPlayer("nPlayer", 30),
    "com.brouken.player" to KnownPlayer("Just Player", 40),
    "is.xyz.mpv" to KnownPlayer("mpv-android", 50),
    "org.xbmc.kodi" to KnownPlayer("Kodi", 60),
    "com.kmplayer" to KnownPlayer("KMPlayer", 70),
    "com.plexapp.android" to KnownPlayer("Plex", 80),
    "com.emby.mobile" to KnownPlayer("Emby", 81),
    "org.jellyfin.mobile" to KnownPlayer("Jellyfin", 82),
  )

const val EXTERNAL_PLAYER_VIDEO_MIME = "video/*"

/**
 * 枚举本机能打开这个流地址的播放器。
 *
 * **为什么不能只查一次带 URI 的 intent**：Emby 的流地址是 `https://…`，而
 * `Intent.ACTION_VIEW` 的匹配要求 **scheme 也要对得上** —— 只声明了 `file` / `content`
 * 的 Activity 匹配不上 `https`，于是列表里只剩「视频」这种声明得最宽的系统播放器。
 * 这里改成两路探测取并集：
 *
 *  1. 带真实 URI 的一路：**优先**，因为它匹配上的 Activity 才是真能吃 http(s) 流的；
 *     同一个包以此路的 Activity 为准（排第一的那个最可能是播放入口）；
 *  2. 只有 MIME 没有 URI 的一路：scheme 通配，把剩下那些只按类型注册的补进来。
 *
 * 自家两个播放页（mpv / GSY）会被剔除 —— 否则「用外部播放器打开」里第一项就是影屿自己，
 * 点了等于原地打转。
 */
fun queryExternalPlayers(
  context: Context,
  uri: Uri,
  selfPackageName: String,
): List<ExternalPlayerOption> {
  val pm = context.packageManager

  val probes =
    listOf(
      Intent(Intent.ACTION_VIEW).setDataAndType(uri, EXTERNAL_PLAYER_VIDEO_MIME),
      Intent(Intent.ACTION_VIEW).setType(EXTERNAL_PLAYER_VIDEO_MIME),
    )

  // LinkedHashMap 保序：先探测到的排在前面；key 用包名做去重维度
  val found = LinkedHashMap<String, ExternalPlayerOption>()

  for (probe in probes) {
    val resolved =
      runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
    for (info in resolved) {
      val pkg = info.activityInfo.packageName
      if (pkg == selfPackageName) continue
      if (found.containsKey(pkg)) continue

      val known = KNOWN_EXTERNAL_PLAYERS[pkg]
      val label =
        known?.label
          ?: runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
          ?: pkg

      found[pkg] =
        ExternalPlayerOption(
          packageName = pkg,
          activityName = info.activityInfo.name,
          label = label,
          rank = known?.rank ?: Int.MAX_VALUE,
        )
    }
  }

  return found.values.sortedWith(compareBy({ it.rank }, { it.label.lowercase() }))
}

/** 构造「用指定播放器打开」的显式 intent */
fun externalPlayerLaunchIntent(
  uri: Uri,
  option: ExternalPlayerOption,
  title: String,
): Intent =
  Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, EXTERNAL_PLAYER_VIDEO_MIME)
    setClassName(option.packageName, option.activityName)
    // 一部分播放器（MX / VLC）会把它显示在标题栏
    putExtra(Intent.EXTRA_TITLE, title)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

/**
 * 兜底：交给系统自己的选择器。
 *
 * 候选同样是剔除自家之后的那批，用 `EXTRA_INITIAL_INTENTS` 喂进去，
 * 所以系统选择器里也不会出现影屿自己。
 */
fun externalPlayerChooserIntent(
  context: Context,
  uri: Uri,
  selfPackageName: String,
  title: String,
): Intent? {
  val all = queryExternalPlayers(context, uri, selfPackageName)
  if (all.isEmpty()) return null

  val intents = all.map { externalPlayerLaunchIntent(uri, it, title) }
  return Intent(Intent.ACTION_CHOOSER).apply {
    putExtra(Intent.EXTRA_INTENT, intents.first())
    putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
}
