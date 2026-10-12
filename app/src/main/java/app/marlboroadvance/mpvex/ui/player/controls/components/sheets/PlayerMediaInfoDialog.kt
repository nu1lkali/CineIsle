package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry
import app.marlboroadvance.mpvex.domain.emby.buildEmbyMediaInfoEntries
import app.marlboroadvance.mpvex.ui.theme.AppLoadingIndicator
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

/**
 * 「更多 → 媒体信息」弹窗：看**当前正在播的这个版本**的全部媒体信息。
 *
 * ## 为什么版本号不放在顶栏
 *
 * 早前把版本名拼在顶栏片名后面（「片名 · 4K HEVC」），但片名本身可能已经很长，
 * 版本名再拼一截会把整条标题撑到换行/截断 —— 用户反馈「两个加在标题栏更长了不好看」。
 * 所以顶栏只留片名，版本名连同容器 / 分辨率 / 码率 / 轨道等信息收进这个弹窗，
 * 一次能看全，也不用再为标题长度让步。
 *
 * ## 版式
 *
 * 沿用项目里自定义 Dialog 的一套（与休眠计时器 [TimePickerDialog] 同形）：
 * `Dialog(usePlatformDefaultWidth = false)` + `Surface(extraLarge / surfaceContainerHigh)`，
 * 而不是 `AlertDialog` —— 后者宽度、内边距、形状都被平台钉死，长表格里标签列
 * 会被压到换行。
 *
 * 视觉上分三层，颜色从重到轻：
 *  1. **头部**：圆形底图标 + 标题（surfaceContainerHigh 上的唯一强调色块）；
 *  2. **版本横幅**：`primaryContainer` 实色底 + 电影图标，把「现在播的是哪一版」
 *     提到最显眼的位置 —— 表格里那条「版本」行随之过滤掉，同一信息不出现两遍；
 *  3. **信息表**：不再套内层卡片（灰底卡片叠在灰底弹窗上只会发闷），
 *     改用「分区标题 + 细分隔线 + 两列对齐行」，长路径按需换行。
 *
 * ## 数据来源
 *
 * 与详情页那一块「媒体信息」**完全同一份**（[buildEmbyMediaInfoEntries]），
 * 不是另写一套 —— 否则同一个条目两个页面显示不一样，用户只会觉得其中一个在骗人。
 *
 * @param serverId 当前播放条目的 Emby 服务器 Id；null = 非 Emby 播放
 * @param itemId 当前播放条目的 ItemId；null = 非 Emby 播放
 * @param mediaSourceId 当前实际在播的版本；null = 单版本
 * @param versionName 版本显示名；拉取失败时至少还能看到它（头部横幅）
 */
@Composable
fun PlayerMediaInfoDialog(
  serverId: Long?,
  itemId: String?,
  mediaSourceId: String?,
  versionName: String?,
  onDismissRequest: () -> Unit,
) {
  val repository = koinInject<EmbyRepository>()

  var state by remember(serverId, itemId, mediaSourceId) {
    mutableStateOf<MediaInfoState>(MediaInfoState.Loading)
  }

  LaunchedEffect(serverId, itemId, mediaSourceId) {
    if (serverId == null || itemId.isNullOrBlank()) {
      state = MediaInfoState.Failed("当前视频不是 Emby 媒体库里的条目，取不到媒体信息")
      return@LaunchedEffect
    }
    val server = repository.getServer(serverId)
    if (server == null) {
      state = MediaInfoState.Failed("找不到对应的 Emby 服务器（可能已被移除）")
      return@LaunchedEffect
    }
    state =
      try {
        val item = repository.getItem(server, itemId)
        MediaInfoState.Ready(buildEmbyMediaInfoEntries(item, mediaSourceId))
      } catch (e: CancellationException) {
        // ⚠️ 取消必须原样抛出：吞掉它会把「弹窗被关掉」当成「读取失败」，
        // 而且破坏结构化并发（与项目里 runCatching 的那个坑同源）。
        throw e
      } catch (e: Exception) {
        MediaInfoState.Failed(e.message ?: "读取媒体信息失败")
      }
  }

  Dialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
      modifier =
        Modifier
          .width(360.dp)
          .padding(MaterialTheme.spacing.medium),
    ) {
      // ⚠️ 滚动挂在外层 Column（与 TimePickerDialog 同一套路）：弹窗整体不超屏。
      //    只让表格自己滚是不行的 —— 头部 + 横幅 + 操作区是固定的，
      //    高峰值加起来 ≈700dp，小屏/横屏会把底部的「关闭」挤出屏幕。
      Column(
        modifier =
          Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
      ) {
        // ── 头部：圆形底图标 + 标题 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier =
              Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Outlined.Info,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(20.dp),
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = "媒体信息",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        // 版本横幅：把「现在播的是哪一版」提到最显眼处。
        // ⚠️ 表格里那条「版本」行随后要过滤掉（见 MediaInfoTable 的 hideVersionRow）——
        //    同一个信息在头部和表里各出现一遍只会让人觉得是 bug。
        val showVersionBadge = !versionName.isNullOrBlank()
        if (showVersionBadge) {
          Spacer(modifier = Modifier.height(14.dp))
          VersionBanner(versionName)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 内容：读取中 / 失败 / 表格 ──
        when (val current = state) {
          MediaInfoState.Loading -> LoadingContent()

          is MediaInfoState.Failed -> FailedContent(current.message)

          is MediaInfoState.Ready ->
            if (current.entries.isEmpty()) {
              EmptyContent()
            } else {
              MediaInfoTable(entries = current.entries, hideVersionRow = showVersionBadge)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 底部操作 ──
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onDismissRequest) { Text("关闭") }
        }
      }
    }
  }
}

private sealed interface MediaInfoState {
  data object Loading : MediaInfoState
  data class Ready(val entries: List<MediaInfoEntry>) : MediaInfoState
  data class Failed(val message: String) : MediaInfoState
}

/**
 * 版本横幅：`primaryContainer` 实色底 + 电影图标。
 *
 * 为什么用实色而不是灰底 chip：这是整个弹窗的**主信息**（用户点开多半就是
 * 为了确认「我现在播的是哪一版」），灰底在一篇灰的弹窗里根本不显眼；
 * primaryContainer 是头部图标同色系，视觉上自成一组。
 */
@Composable
private fun VersionBanner(versionName: String) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Outlined.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = versionName,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 2,
      )
    }
  }
}

/** 读取中：与全局统一的加载动画（用户可在设置里换 Lottie），不只是干等一句话 */
@Composable
private fun LoadingContent() {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 180.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    AppLoadingIndicator(size = 48.dp)
    Spacer(modifier = Modifier.height(14.dp))
    Text(
      text = "正在读取媒体信息…",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 读取失败：错误图标 + 原因（版本信息在头部横幅，这里不重复） */
@Composable
private fun FailedContent(message: String) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 120.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Outlined.Error,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.error,
      modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

/** 服务器没返回任何字段时的占位 */
@Composable
private fun EmptyContent() {
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 120.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "服务器没有返回可用的媒体信息",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * 与详情页「媒体信息」分区同一套排版：左边固定宽度的字段名，右边值。
 *
 * @param hideVersionRow true = 头部横幅已经在放版本名，表里那条「版本」行过滤掉，
 *   同一信息不出现两遍（buildEmbyMediaInfoEntries 只在多版本时才产出该行）。
 */
@Composable
private fun MediaInfoTable(
  entries: List<MediaInfoEntry>,
  hideVersionRow: Boolean,
) {
  val visibleEntries =
    remember(entries, hideVersionRow) {
      if (hideVersionRow) {
        entries.filterNot { it is MediaInfoEntry.Row && it.label == "版本" }
      } else {
        entries
      }
    }

  Column(modifier = Modifier.fillMaxWidth()) {
    // 文件路径 / 版本名这类值长按可复制 —— 报故障、对版本时用得上的小口子
    SelectionContainer {
      Column {
        visibleEntries.forEachIndexed { index, entry ->
          when (entry) {
            is MediaInfoEntry.Section -> {
              // 分区之间一条细分隔线：轨道一多，没有线就是一片糊。
              // 首个小节（通常是「文件」）前面不画 —— 弹窗顶部已经有横幅隔开了。
              if (index > 0) {
                HorizontalDivider(
                  modifier = Modifier.padding(vertical = 6.dp),
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
              }
              Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
              )
            }

            is MediaInfoEntry.Row -> {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Text(
                  text = entry.label,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.width(80.dp),
                )
                Text(
                  text = entry.value,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}
