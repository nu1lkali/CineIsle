package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
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
 * ## 数据来源
 *
 * 与详情页那一块「媒体信息」**完全同一份**（`buildEmbyMediaInfoEntries`），
 * 不是另写一套 —— 否则同一个条目两个页面显示不一样，用户只会觉得其中一个在骗人。
 *
 * @param serverId 当前播放条目的 Emby 服务器 Id；null = 非 Emby 播放
 * @param itemId 当前播放条目的 ItemId；null = 非 Emby 播放
 * @param mediaSourceId 当前实际在播的版本；null = 单版本
 * @param versionName 版本显示名（多版本条目才有）；拉取失败时至少还能看到它
 */
@Composable
fun PlayerMediaInfoDialog(
  serverId: Long?,
  itemId: String?,
  mediaSourceId: String?,
  versionName: String?,
  onDismissRequest: () -> Unit,
) {
  val repository = koinInject<app.marlboroadvance.mpvex.domain.emby.EmbyRepository>()

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
        MediaInfoState.Ready(
          app.marlboroadvance.mpvex.domain.emby.buildEmbyMediaInfoEntries(item, mediaSourceId),
        )
      } catch (e: CancellationException) {
        // ⚠️ 取消必须原样抛出：吞掉它会把「弹窗被关掉」当成「读取失败」，
        // 而且破坏结构化并发（与项目里 runCatching 的那个坑同源）。
        throw e
      } catch (e: Exception) {
        MediaInfoState.Failed(e.message ?: "读取媒体信息失败")
      }
  }

  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text("媒体信息") },
    text = {
      when (val current = state) {
        MediaInfoState.Loading -> {
          Text(
            text = "正在读取媒体信息…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        is MediaInfoState.Failed -> {
          Column {
            // 拉取失败也要把「在播哪个版本」给出来 —— 这是这个弹窗最主要的用途
            if (!versionName.isNullOrBlank()) {
              VersionOnlyRow(versionName)
              Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
              text = current.message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        is MediaInfoState.Ready -> {
          if (current.entries.isEmpty()) {
            Text(
              text = "服务器没有返回可用的媒体信息",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            MediaInfoTable(current.entries)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismissRequest) { Text("关闭") }
    },
  )
}

private sealed interface MediaInfoState {
  data object Loading : MediaInfoState
  data class Ready(val entries: List<app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry>) : MediaInfoState
  data class Failed(val message: String) : MediaInfoState
}

/** 拉取失败时的兜底：至少显示「现在播的是哪个版本」 */
@Composable
private fun VersionOnlyRow(versionName: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = "版本",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(76.dp),
    )
    Text(
      text = versionName,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
  }
}

/** 与详情页「媒体信息」分区同一套排版：左边固定宽度的字段名，右边值 */
@Composable
private fun MediaInfoTable(
  entries: List<app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry>,
) {
  Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(vertical = 6.dp)) {
        entries.forEach { entry ->
          when (entry) {
            is app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry.Section -> {
              Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
              )
            }

            is app.marlboroadvance.mpvex.domain.emby.MediaInfoEntry.Row -> {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Text(
                  text = entry.label,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.width(76.dp),
                )
                Text(
                  text = entry.value,
                  style = MaterialTheme.typography.bodySmall,
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
