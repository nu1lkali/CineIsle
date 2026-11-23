package app.marlboroadvance.mpvex.ui.browser.emby.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.emby.subtitle.SubtitlePresentation
import app.marlboroadvance.mpvex.domain.subtitle.online.DownloadedSubtitle
import app.marlboroadvance.mpvex.ui.browser.emby.DetailSubtitlePhase
import app.marlboroadvance.mpvex.ui.browser.emby.DetailSubtitleUi

/**
 * 详情页的「字幕」卡片（折叠态）。
 *
 * ## 只做展示，不改任何状态
 *
 * 订阅 / 挂载字幕全部发生在播放页。这里放一个「更多信息」的入口是安全的：
 * 用户点开看一眼、关掉，不会影响之后播放时选哪条字幕。
 *
 * ## 三种形态共用同一个外框
 *
 * 加载中 / 无字幕 / 失败都在同一张卡片里换一段文字，**不做整块消失** ——
 * 卡片突然不见会让人以为是滚动错位；而高度小幅变化比空白更不刺眼。
 *
 * @param onOpenSheet 点「全部」或卡片主体（有明细时）
 * @param onRetry 失败态的重试
 * @param onSearchWebSubtitle 打开网络字幕搜索（迅雷 / SubtitleCat）。null 时该入口不渲染
 *   —— 非可播条目（季 / 合集）没有稳定的 ItemId 关联，留着只会下错地方。
 * @param localSubtitles 已经下载到本机、并关联到这条媒体的网络字幕（需求 6.5：
 *   「已关联 N 条字幕」要能一眼看出，不要每次重新搜索）
 */
@Composable
internal fun DetailSubtitleCard(
  ui: DetailSubtitleUi,
  onOpenSheet: () -> Unit,
  onRetry: () -> Unit,
  onSearchWebSubtitle: (() -> Unit)? = null,
  localSubtitles: List<DownloadedSubtitle> = emptyList(),
  modifier: Modifier = Modifier,
) {
  val (chips, hiddenChipCount) = remember(ui.subtitles) {
    SubtitlePresentation.languageChips(ui.subtitles)
  }
  val allUnavailable = remember(ui.subtitles) {
    SubtitlePresentation.allUnavailable(ui.subtitles)
  }
  val picked = ui.pick?.chosen
  val pickedTitle = picked?.let { SubtitlePresentation.titleOf(it) }
  // 「将自动使用」那一行先给**实际名称**：用户要确认的是「哪一条」，不是「什么语言」
  val pickedName = picked?.let { SubtitlePresentation.actualNameOf(it) }
  val pickedSummary = picked?.describe()

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(
      1.dp,
      MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    ),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp)
      // 有明细才可点：空态 / 失败态点开是空抽屉，等于骗人
      .clickable(enabled = ui.hasData, onClick = onOpenSheet),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Subtitles,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "字幕",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(6.dp))
        SubtitleCountBadge(ui = ui, localCount = localSubtitles.size)
        Spacer(modifier = Modifier.weight(1f))
        if (ui.hasData) {
          Text(
            text = "全部",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      when {
        ui.phase == DetailSubtitlePhase.IDLE || ui.phase == DetailSubtitlePhase.LOADING ->
          SubtitleLoadingBody()

        ui.phase == DetailSubtitlePhase.FAILED && !ui.hasData ->
          SubtitleFailedBody(error = ui.error, onRetry = onRetry)

        ui.subtitles.isEmpty() ->
          SubtitleEmptyBody(totalStreams = ui.totalStreams, localCount = localSubtitles.size)

        else -> SubtitleReadyBody(
          ui = ui,
          pickedTitle = pickedTitle,
          pickedName = pickedName,
          pickedSummary = pickedSummary,
          chips = chips,
          hiddenChipCount = hiddenChipCount,
          allUnavailable = allUnavailable,
        )
      }

      // ── 已下载的网络字幕（需求 6.5）──
      // 与上面那段「服务端已识别的轨」分开摆：两者不是同一种资源，
      // 混在一行里会让人以为服务器上多了几条字幕。这里只陈述本机事实。
      if (localSubtitles.isNotEmpty()) {
        val bySource =
          localSubtitles
            .groupingBy { it.source.label }
            .eachCount()
            .entries
            .joinToString(" · ") { "${it.key} ${it.value}" }
        Text(
          text = "本机已关联 ${localSubtitles.size} 条网络字幕 · $bySource",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 8.dp),
        )
        Text(
          text = "播放该媒体时自动挂载，可在字幕面板切换",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 1.dp),
        )
      }

      // ── 网络字幕入口（用户反馈 6）──
      // 原来只是标题行右侧一个 28dp 的「网络字幕」小链接：字小、位置偏，
      // 用户根本不知道那是个能下载字幕的按钮。改成整行按钮，动作一目了然。
      if (onSearchWebSubtitle != null) {
        Spacer(modifier = Modifier.height(10.dp))
        FilledTonalButton(
          onClick = onSearchWebSubtitle,
          modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        ) {
          Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "搜索网络字幕并下载",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
          )
        }
        Text(
          text = "从迅雷 / SubtitleCat 检索，下载到本机并关联该媒体",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

/**
 * 标题右侧的小徽标：条数 / 读取中 / 空 / 失败。
 *
 * 条数**分两段写**（`Emby 3 · 网络 2`），不合并成一个数字：服务端轨与本机下载的
 * 网络字幕是两种资源，合成「5 条」会让人以为库里凭空多了两条字幕。
 * 任一侧为 0 时省略该段，两侧都空才显示「0 条」。
 */
@Composable
private fun SubtitleCountBadge(
  ui: DetailSubtitleUi,
  localCount: Int = 0,
) {
  val (text, isAlert) = when {
    ui.phase == DetailSubtitlePhase.IDLE || ui.phase == DetailSubtitlePhase.LOADING ->
      "读取中" to false
    ui.phase == DetailSubtitlePhase.FAILED && !ui.hasData -> "获取失败" to true
    else -> {
      val parts = buildList {
        if (ui.subtitles.isNotEmpty()) add("Emby ${ui.subtitles.size}")
        if (localCount > 0) add("网络 $localCount")
      }
      (if (parts.isEmpty()) "0 条" else parts.joinToString(" · ")) to false
    }
  }
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = if (isAlert) {
      MaterialTheme.colorScheme.errorContainer
    } else {
      MaterialTheme.colorScheme.secondaryContainer
    },
    contentColor = if (isAlert) {
      MaterialTheme.colorScheme.onErrorContainer
    } else {
      MaterialTheme.colorScheme.onSecondaryContainer
    },
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      softWrap = false,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
    )
  }
}

@Composable
private fun SubtitleLoadingBody() {
  Column {
    SkeletonBar(widthFraction = 0.58f)
    Spacer(modifier = Modifier.height(6.dp))
    SkeletonBar(widthFraction = 0.84f)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "正在读取该版本的字幕信息…",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SkeletonBar(widthFraction: Float) {
  Box(
    modifier = Modifier
      .fillMaxWidth(widthFraction)
      .height(9.dp)
      .clip(RoundedCornerShape(5.dp))
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
  )
}

/**
 * 空态分两支：
 * · [totalStreams] > 0 ⇒ 服务端回了轨道，但里面没有字幕轨，是真的「没附带」；
 * · == 0 ⇒ 连轨道都没拿到（远程 strm / 直链常见），不能断言「没有字幕」。
 */
@Composable
private fun SubtitleEmptyBody(
  totalStreams: Int,
  localCount: Int = 0,
) {
  Column {
    Text(
      text = when {
        localCount > 0 -> "服务端未附带字幕，本机已下载 $localCount 条网络字幕"
        totalStreams > 0 -> "该版本未附带字幕"
        else -> "服务端未返回轨道信息"
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = when {
        localCount > 0 -> "播放该媒体时自动挂载，可在字幕面板切换"
        totalStreams > 0 -> "可在播放页「字幕 → 搜索字幕」在线获取"
        else -> "播放时可在播放页里查看实际可用字幕"
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SubtitleFailedBody(error: String?, onRetry: () -> Unit) {
  Column {
    Text(
      text = "字幕信息获取失败",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = error?.takeIf { it.isNotBlank() } ?: "网络请求失败，暂无法确认字幕",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 2.dp),
    )
    TextButton(
      onClick = onRetry,
      modifier = Modifier.height(32.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(
        horizontal = 8.dp,
        vertical = 0.dp,
      ),
    ) {
      Text(text = "重试", style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
private fun SubtitleReadyBody(
  ui: DetailSubtitleUi,
  pickedTitle: String?,
  /** 要加载的那一条的实际名称（外挂文件名 / 服务端 DisplayTitle）；拿不到为 null */
  pickedName: String?,
  /** 技术口径一句话：`简体中文 · ASS · 外挂 · 默认` */
  pickedSummary: String?,
  chips: List<String>,
  hiddenChipCount: Int,
  allUnavailable: Boolean,
) {
  Column {
    if (pickedSummary != null) {
      Row {
        Text(
          text = "将自动使用",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
          // 有实际名称就显示名称：同一语言下可能有好几条，只有名字能区分是哪一条
          text = pickedName ?: pickedSummary,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }
      if (pickedName != null) {
        // 名称已经说了「是哪一条」，第二行退成技术口径，不抢主次
        Text(
          text = pickedSummary,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    } else {
      Text(
        text = "未匹配到语言偏好，播放时需手动选一条",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (allUnavailable) {
      Text(
        text = "${ui.subtitles.size} 条字幕均无法独立提取",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
    }

    if (chips.isNotEmpty()) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { label ->
          LanguageChip(label = label, highlighted = label == pickedTitle)
        }
        if (hiddenChipCount > 0) {
          LanguageChip(label = "+$hiddenChipCount", highlighted = false)
        }
      }
    }

    if (ui.stale || (ui.error != null && ui.hasData)) {
      Text(
        text = "更新失败，显示的是上次读取结果",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

@Composable
private fun LanguageChip(label: String, highlighted: Boolean) {
  Surface(
    shape = RoundedCornerShape(7.dp),
    color = if (highlighted) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surface
    },
    contentColor = if (highlighted) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    },
    border = BorderStroke(
      1.dp,
      if (highlighted) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
      } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
      },
    ),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
    )
  }
}
