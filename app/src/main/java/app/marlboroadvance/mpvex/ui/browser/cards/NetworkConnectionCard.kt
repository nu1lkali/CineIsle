package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol

/**
 * 协议徽标代号。
 *
 * 用 2–3 个字母而不是图标：四个协议（SMB / FTP / WebDAV / AList）在 Material 图标库
 * 里没有一组语义准确的对应，硬凑图标反而要用户去猜；字母代号零歧义、跨语言一致，
 * 而且和「协议」这个词本身就长得一样。
 */
internal fun protocolBadge(protocol: NetworkProtocol): String =
  when (protocol) {
    NetworkProtocol.SMB -> "SMB"
    NetworkProtocol.FTP -> "FTP"
    NetworkProtocol.WEBDAV -> "DAV"
    NetworkProtocol.ALIST -> "AL"
  }

/**
 * 协议强调色。
 *
 * ⚠️ 只用主题色阶、**不硬编码颜色** —— 跟随深浅色与动态取色自动适配。
 * Material 3 只给了三个强调色档（primary / secondary / tertiary），四个协议
 * 必然两两共用一档（SMB 与 AList 共用 primary）；精确区分交给 [protocolBadge]
 * 的字母代号，颜色只负责「一眼看出不是同一类」。
 */
@Composable
internal fun protocolAccent(protocol: NetworkProtocol): Color =
  when (protocol) {
    NetworkProtocol.SMB -> MaterialTheme.colorScheme.primary
    NetworkProtocol.FTP -> MaterialTheme.colorScheme.secondary
    NetworkProtocol.WEBDAV -> MaterialTheme.colorScheme.tertiary
    NetworkProtocol.ALIST -> MaterialTheme.colorScheme.primary
  }

/**
 * 局域网 / 网络连接卡（v69 紧凑版）。
 *
 * ## 这一版为什么要重做
 *
 * 旧版是「全宽 + 4~5 段堆叠」（名称行、可选的 Path/User 行、错误行、自启勾选行、
 * 底部大按钮行），单卡实测接近 200dp 高。连接一多，一屏放不下两张，列表又长又花。
 *
 * ## 网络模块的设计语言（本文件与 `NetworkStreamingScreen` 共用）
 *
 * 1. **协议徽标** —— 左侧 40dp 圆角方块，按协议着色 + 字母代号，扫一眼就知道是 SMB 还是 AList；
 * 2. **单行两段文本** —— 上行名称、下行 `协议 • host:port`，一律 1 行省略，不再堆细节；
 * 3. **状态点 + 溢出菜单** —— 右侧一个 8dp 状态点（已连=主色 / 空闲=轮廓灰 /
 *    连接中=转圈 / 出错=错误色），所有次级操作收进 ⋮ 菜单，卡片上不放按钮；
 * 4. **点击卡片 = 主操作** —— 已连接→浏览，未连接→连接，连接中→无动作。
 *    这是卡片能压到 ~60dp 的关键：把唯一的高频操作变成「点整张卡」，
 *    而不是塞一个大按钮（大按钮是高度的主要来源）；
 * 5. **置顶态** —— 置顶卡换成 `secondaryContainer` 底色，并在状态点左侧显示图钉。
 *    置顶只改展示顺序（DAO 的 `ORDER BY pinned DESC, id ASC`），不改连接行为。
 *
 * 细节项（起始路径、账号、是否匿名）统一收进「编辑」表单 —— 它们不是列表里需要
 * 反复看的信息，之前放在卡片上纯属占地方。
 *
 * @param onTogglePin 点击「置顶 / 取消置顶」。只翻一个 DB 列，
 *   不重新加密密码、不断开活跃连接（见 `NetworkRepository.setPinned`）。
 */
@Composable
fun NetworkConnectionCard(
  connection: NetworkConnection,
  onConnect: (NetworkConnection) -> Unit,
  onDisconnect: (NetworkConnection) -> Unit,
  onEdit: (NetworkConnection) -> Unit,
  onDelete: (NetworkConnection) -> Unit,
  onBrowse: (NetworkConnection) -> Unit,
  onAutoConnectChange: (NetworkConnection, Boolean) -> Unit,
  onTogglePin: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
  isConnected: Boolean = false,
  isConnecting: Boolean = false,
  error: String? = null,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val accent = protocolAccent(connection.protocol)

  Card(
    onClick = {
      when {
        isConnecting -> Unit
        isConnected -> onBrowse(connection)
        else -> onConnect(connection)
      }
    },
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (connection.pinned) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainer
      },
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // ① 协议徽标
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = protocolBadge(connection.protocol),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = accent,
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      // ② 名称 / 地址（可能再挂一行错误）
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = connection.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${connection.protocol.displayName} • ${connection.host}:${connection.port}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (error != null) {
          Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // ③ 置顶标记 + 状态点
      if (connection.pinned) {
        Icon(
          imageVector = Icons.Filled.PushPin,
          contentDescription = null,
          modifier = Modifier.size(13.dp),
          tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.width(6.dp))
      }
      ConnectionStatusDot(
        isConnected = isConnected,
        isConnecting = isConnecting,
        hasError = error != null,
      )

      // ④ 溢出菜单：所有次级操作
      Box {
        IconButton(
          onClick = { menuOpen = true },
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "更多操作",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        DropdownMenu(
          expanded = menuOpen,
          onDismissRequest = { menuOpen = false },
        ) {
          DropdownMenuItem(
            text = {
              Text(
                if (connection.pinned) {
                  stringResource(R.string.i18n_unpin_top)
                } else {
                  stringResource(R.string.i18n_pin_top)
                },
              )
            },
            leadingIcon = {
              Icon(Icons.Filled.PushPin, contentDescription = null)
            },
            onClick = {
              menuOpen = false
              onTogglePin(connection)
            },
          )

          if (isConnected) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.i18n_browse)) },
              leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
              onClick = {
                menuOpen = false
                onBrowse(connection)
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.i18n_disconnect)) },
              leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
              onClick = {
                menuOpen = false
                onDisconnect(connection)
              },
            )
          } else {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.i18n_connect)) },
              leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
              enabled = !isConnecting,
              onClick = {
                menuOpen = false
                onConnect(connection)
              },
            )
          }

          HorizontalDivider()

          DropdownMenuItem(
            text = { Text(stringResource(R.string.i18n_connect_on_launch)) },
            leadingIcon = {
              // 用「有没有勾」表达开关态 —— 菜单项不适合放 Checkbox，
              // 但留一个等宽占位（24dp）保证两种状态的文字左对齐
              if (connection.autoConnect) {
                Icon(Icons.Filled.Check, contentDescription = null)
              } else {
                Spacer(modifier = Modifier.size(24.dp))
              }
            },
            onClick = {
              menuOpen = false
              onAutoConnectChange(connection, !connection.autoConnect)
            },
          )

          DropdownMenuItem(
            text = { Text(stringResource(R.string.i18n_edit)) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = {
              menuOpen = false
              onEdit(connection)
            },
          )
          DropdownMenuItem(
            text = {
              Text(
                text = stringResource(R.string.i18n_delete),
                color = MaterialTheme.colorScheme.error,
              )
            },
            leadingIcon = {
              Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
              )
            },
            onClick = {
              menuOpen = false
              onDelete(connection)
            },
          )
        }
      }
    }
  }
}

/**
 * 连接状态点：一个 20dp 的定宽容器，内部按状态换内容 ——
 * 这样四种状态（空闲 / 连接中 / 已连接 / 出错）**占位完全一致**，
 * 状态切换时右侧的溢出菜单不会左右抖动。
 */
@Composable
private fun ConnectionStatusDot(
  isConnected: Boolean,
  isConnecting: Boolean,
  hasError: Boolean,
) {
  Box(
    modifier = Modifier.size(20.dp),
    contentAlignment = Alignment.Center,
  ) {
    when {
      isConnecting ->
        CircularProgressIndicator(
          modifier = Modifier.size(14.dp),
          strokeWidth = 2.dp,
        )

      hasError ->
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        )

      isConnected ->
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        )

      else ->
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
  }
}
