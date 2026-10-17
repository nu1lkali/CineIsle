package app.marlboroadvance.mpvex.ui.browser.cards

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.ui.utils.pressDownScale

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
  // 「完整错误」弹窗开关：卡上的错误块最多两行，点它看全文（见 [ConnectionErrorBlock]）
  var errorDetailOpen by remember { mutableStateOf(false) }
  val accent = protocolAccent(connection.protocol)

  Card(
    onClick = {
      when {
        isConnecting -> Unit
        isConnected -> onBrowse(connection)
        else -> onConnect(connection)
      }
    },
    modifier = modifier.fillMaxWidth().pressDownScale(),
    colors = CardDefaults.cardColors(
      containerColor = if (connection.pinned) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainer
      },
    ),
  ) {
    // 主行 + 可选的「错误块」纵向排布。
    // ⚠️ 错误**不能**再挤在主行里当第三行文字：主行是「1 行省略」的紧凑版式，
    //    而连接失败的原文动辄几十上百字符，塞进去等于什么都没说（用户看不出
    //    「是密码错、超时、还是端口不通」）。所以让它独占一块、并给出看全文的入口。
    Column(modifier = Modifier.fillMaxWidth()) {
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

        // ② 名称 / 地址（一律 1 行；错误另起一块，见下）
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

      // ⑤ 连接失败详情：只在出错时出现，正常卡片完全不受影响（仍然是 ~60dp 紧凑行）
      val errorText = error
      if (errorText != null) {
        ConnectionErrorBlock(
          error = errorText,
          onOpenDetail = { errorDetailOpen = true },
        )
      }
    }
  }

  val errorText = error
  if (errorDetailOpen && errorText != null) {
    ConnectionErrorDialog(
      message = errorText,
      onDismiss = { errorDetailOpen = false },
    )
  }
}

/**
 * 连接失败详情块：整块铺满卡片宽、`errorContainer` 底色、最多两行、右侧一个箭头。
 *
 * 为什么值得单独做一块（而不是把错误挤在主行里省略）：
 * 连接失败的原文是**排错唯一线索**（认证失败 / 超时 / 端口不通 / 路径不存在 全都
 * 靠它区分），一行省略等于把线索丢掉。这里给两行 + 点开看全文，
 * 即「默认不打扰、要看能给全」。
 *
 * ⚠️ 右侧必须留箭头这种「可展开」的视觉提示：整块可点但没有提示，
 * 就成了「点了也不知道会发生什么」的灰区（项目铁律：别造死项 / 别做无提示的可点区域）。
 */
@Composable
private fun ConnectionErrorBlock(
  error: String,
  onOpenDetail: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.errorContainer)
      .clickable(onClick = onOpenDetail)
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.ErrorOutline,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = error,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onErrorContainer,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(4.dp))
    Icon(
      imageVector = Icons.Filled.KeyboardArrowRight,
      contentDescription = "查看完整错误",
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

/**
 * 完整错误原文弹窗。
 *
 * 原文可能很长（异常串），所以正文区可滚动；底部一个「复制」——用户在论坛 /
 * 群里贴错误时能一次拿全，不用手抄。
 */
@Composable
private fun ConnectionErrorDialog(
  message: String,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("连接失败详情") },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val clipboard = context.getSystemService(ClipboardManager::class.java)
          clipboard?.setPrimaryClip(ClipData.newPlainText("连接错误", message))
          Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        },
      ) {
        Text("复制")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("关闭")
      }
    },
  )
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
