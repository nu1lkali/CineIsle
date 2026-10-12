package app.marlboroadvance.mpvex.ui.lock

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppLockPreferences
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * 应用锁门禁（清单 b7）。
 *
 * 用法：包住 MainActivity 的整个内容。锁着时 [content] **整块不组合** ——
 * 背后的 Navigator 一帧都不布局，解锁后才开始首帧，没有「背后先跑一遍」的浪费。
 *
 * ## 什么时候锁
 *
 *  · 冷启动：开锁的用户每次进程启动都锁（初始状态直接按偏好算）；
 *  · 退后台再回来：离开超过 [AppLockPreferences.RELOCK_GRACE_MS]（5 秒）才重新锁。
 *    5 秒是给「页内跳播放器 / 系统权限弹窗」这类**自己造成的短暂退后台**留的余量
 *    （那些场景回来不该重新解锁）；真正离开应用（回桌面、切别的 App）都远不止 5 秒。
 *
 * ## 边界（有意为之）
 *
 * 只守 MainActivity。播放页（mpv / GSY）不在门禁里 —— 从通知 / 小窗回来不该被 PIN
 * 拦下，且儿童能碰到播放页的前提是已经过了这道门。真要连播放页一起锁，
 * 得给两个 PlayerActivity 各接一份，那是另一个量级的活。
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
  val prefs = koinInject<AppLockPreferences>()
  // 初始状态按偏好算：开了锁 + 设过 PIN ⇒ 进程一启动就是锁着的
  var locked by remember {
    mutableStateOf(prefs.enabled.get() && prefs.pinHash.get().isNotEmpty())
  }
  var backgroundedAt by remember { mutableLongStateOf(0L) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
        Lifecycle.Event.ON_START -> {
          val awayMs = System.currentTimeMillis() - backgroundedAt
          val shouldLock = prefs.enabled.get() && prefs.pinHash.get().isNotEmpty()
          if (shouldLock && awayMs > AppLockPreferences.RELOCK_GRACE_MS) {
            locked = true
          }
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  if (locked) {
    AppLockUnlockScreen(
      onVerify = prefs::verifyPin,
      onUnlocked = { locked = false },
    )
  } else {
    content()
  }
}

/**
 * 全屏解锁页。
 *
 * 连续输错 [AppLockPreferences.MAX_ATTEMPTS] 次进 30 秒冷却（带倒计时）——
 * PIN 只有 4~6 位，不设防就是穷举题。计数器只在内存里，进程重启即忘
 * （重启本身也是一次「重新锁上」）。
 */
@Composable
private fun AppLockUnlockScreen(
  onVerify: (String) -> Boolean,
  onUnlocked: () -> Unit,
) {
  var pin by remember { mutableStateOf("") }
  var wrong by remember { mutableStateOf(false) }
  var attempts by remember { mutableIntStateOf(0) }
  var cooldownEndAt by remember { mutableLongStateOf(0L) }
  var cooldownRemain by remember { mutableIntStateOf(0) }

  // 冷却倒计时：到点自动清零尝试次数
  LaunchedEffect(cooldownEndAt) {
    while (cooldownEndAt > 0L) {
      val remainMs = cooldownEndAt - System.currentTimeMillis()
      if (remainMs <= 0L) {
        cooldownEndAt = 0L
        cooldownRemain = 0
        attempts = 0
        break
      }
      cooldownRemain = ((remainMs + 999L) / 1000L).toInt()
      delay(200)
    }
  }

  // ⚠️ 本地函数读的是当前值（by remember 每次读取都走快照状态），
  // 但不能对它们做 smart cast（红线 16）—— 先快照进局部 val 再判断。
  fun submit() {
    val entered = pin
    val cooling = cooldownEndAt > System.currentTimeMillis()
    if (entered.isEmpty() || cooling) return
    if (onVerify(entered)) {
      onUnlocked()
    } else {
      attempts += 1
      wrong = true
      pin = ""
      if (attempts >= AppLockPreferences.MAX_ATTEMPTS) {
        cooldownEndAt = System.currentTimeMillis() + AppLockPreferences.COOLDOWN_MS
        wrong = false
      }
    }
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(horizontal = 32.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(24.dp))

      AndroidView(
        modifier = Modifier.size(64.dp),
        factory = { ctx ->
          ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) }
        },
      )

      Spacer(Modifier.height(12.dp))

      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )

      Spacer(Modifier.height(28.dp))

      Text(
        text = stringResource(R.string.app_lock_unlock_title),
        style = MaterialTheme.typography.titleMedium,
      )

      Spacer(Modifier.height(20.dp))

      PinDots(length = pin.length)

      Spacer(Modifier.height(12.dp))

      val message: String = when {
        cooldownRemain > 0 -> stringResource(R.string.app_lock_cooldown, cooldownRemain)
        wrong -> stringResource(R.string.app_lock_wrong_pin)
        else -> ""
      }
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
      )

      Spacer(Modifier.height(16.dp))

      PinPad(
        enabled = cooldownRemain == 0,
        onDigit = { digit ->
          wrong = false
          if (pin.length < AppLockPreferences.MAX_PIN_LENGTH) pin += digit
        },
        onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
        onSubmit = { submit() },
      )
    }
  }
}

// ────────────────────────────────────────────────────────────────────────
// 共用零件
// ────────────────────────────────────────────────────────────────────────

/** PIN 点位：固定画 6 个圈，输到几个填几个（PIN 允许 4~6 位）。 */
@Composable
internal fun PinDots(length: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    repeat(AppLockPreferences.MAX_PIN_LENGTH) { index ->
      val filled = index < length
      Box(
        modifier = Modifier
          .size(14.dp)
          .clip(CircleShape)
          .background(
            if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
          )
          .border(
            width = 1.dp,
            color = if (filled) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.outline
            },
            shape = CircleShape,
          ),
      )
    }
  }
}

/**
 * 数字键盘：1-9 + 底行（删除 / 0 / 确定）。
 *
 * 触摸区（80dp）比可见圆圈（68dp）大一圈 —— 纯观感不变、误触率降一截。
 */
@Composable
internal fun PinPad(
  enabled: Boolean,
  onDigit: (Char) -> Unit,
  onDelete: () -> Unit,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    listOf("123", "456", "789").forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        row.forEach { digit ->
          PinKey(enabled = enabled, onClick = { onDigit(digit) }) {
            Text(text = digit.toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium)
          }
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
      PinKey(enabled = enabled, onClick = onDelete) {
        Icon(
          Icons.AutoMirrored.Outlined.Backspace,
          contentDescription = stringResource(R.string.app_lock_key_delete),
          modifier = Modifier.size(26.dp),
        )
      }
      PinKey(enabled = enabled, onClick = { onDigit('0') }) {
        Text(text = "0", fontSize = 26.sp, fontWeight = FontWeight.Medium)
      }
      PinKey(enabled = enabled, onClick = onSubmit) {
        Icon(
          Icons.Outlined.Check,
          contentDescription = stringResource(R.string.generic_confirm),
          modifier = Modifier.size(26.dp),
        )
      }
    }
  }
}

/** 单个键盘键：外层 80dp 触摸区 + 内层 68dp 可见圆圈。 */
@Composable
private fun PinKey(
  enabled: Boolean,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(80.dp)
      .clip(CircleShape)
      .clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(color = MaterialTheme.colorScheme.primary),
      ) { onClick() },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(68.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

// ────────────────────────────────────────────────────────────────────────
// 设置里的弹窗
// ────────────────────────────────────────────────────────────────────────

/**
 * 应用锁「已开启」时的管理入口：改 PIN / 关锁。
 *
 * 这个弹窗本身不执行任何破坏性动作 —— 关锁后面还跟着一道
 * [PinVerifyDialog]（防止儿童自己进设置关掉），改 PIN 则是
 * [PinChangeDialog]（先验证旧的）。它只负责把用户领到对应流程。
 */
@Composable
fun AppLockManageDialog(
  onChangePin: () -> Unit,
  onDisable: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(dismissOnClickOutside = false),
    title = { Text(text = stringResource(R.string.app_lock_title)) },
    text = { Text(text = stringResource(R.string.app_lock_manage_message)) },
    confirmButton = {
      TextButton(onClick = onChangePin) {
        Text(text = stringResource(R.string.app_lock_change_pin))
      }
    },
    dismissButton = {
      TextButton(onClick = onDisable) {
        Text(text = stringResource(R.string.app_lock_disable))
      }
    },
  )
}

/**
 * 设置 PIN：第一步输新 PIN（≥4 位），第二步再输一遍，两遍一致才落盘。
 *
 * ⚠️ 两步都在同一个弹窗里完成，中途关掉一律视为放弃 —— 不写「半个 PIN」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupDialog(
  onDismiss: () -> Unit,
  onSet: (pin: String) -> Unit,
) {
  var step by remember { mutableStateOf(1) }
  var first by remember { mutableStateOf("") }
  var pin by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  // 弹窗回调里不能用 stringResource（非 Composable 上下文）—— 先取好文案
  val mismatchText = stringResource(R.string.app_lock_pin_mismatch)
  val tooShortText = stringResource(R.string.app_lock_pin_too_short)

  PinDialogScaffold(
    title = stringResource(
      if (step == 1) R.string.app_lock_pin_setup_title else R.string.app_lock_pin_confirm_title,
    ),
    message = stringResource(
      if (step == 1) R.string.app_lock_pin_setup_hint else R.string.app_lock_pin_confirm_hint,
    ),
    error = error,
    pinLength = pin.length,
    onDigit = { digit ->
      error = null
      if (pin.length < AppLockPreferences.MAX_PIN_LENGTH) pin += digit
    },
    onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
    onSubmit = {
      val entered = pin
      when {
        // 太短要**明说**：静默不反应是产品 bug（按钮在、点了没效果）
        step == 1 && entered.length < AppLockPreferences.MIN_PIN_LENGTH -> {
          error = tooShortText
        }
        step == 1 -> {
          first = entered
          pin = ""
          error = null
          step = 2
        }
        entered == first -> {
          onSet(first)
          onDismiss()
        }
        else -> {
          // 不一致：退回第一步重来，避免用户在第二步里干瞪眼
          error = mismatchText
          first = ""
          pin = ""
          step = 1
        }
      }
    },
    submitEnabled = pin.isNotEmpty(),
    onCancel = onDismiss,
  )
}

/**
 * 验证当前 PIN（关锁 / 改 PIN 的前置步骤）。
 *
 * 验证通过后回调 [onVerified]；输错只在本弹窗里提示，不进全局冷却
 * （设置页是「已经过了门」的场景，没必要按全局那个力度防）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinVerifyDialog(
  title: String,
  message: String,
  onDismiss: () -> Unit,
  onVerify: (String) -> Boolean,
  onVerified: () -> Unit,
) {
  var pin by remember { mutableStateOf("") }
  var wrong by remember { mutableStateOf(false) }
  val wrongText = stringResource(R.string.app_lock_wrong_pin)

  PinDialogScaffold(
    title = title,
    message = message,
    error = if (wrong) wrongText else null,
    pinLength = pin.length,
    onDigit = { digit ->
      wrong = false
      if (pin.length < AppLockPreferences.MAX_PIN_LENGTH) pin += digit
    },
    onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
    onSubmit = {
      val entered = pin
      if (entered.isNotEmpty() && onVerify(entered)) {
        onVerified()
        onDismiss()
      } else {
        wrong = true
        pin = ""
      }
    },
    submitEnabled = pin.isNotEmpty(),
    onCancel = onDismiss,
  )
}

/** 改 PIN：先验证旧的，通过后直接进入「设置新 PIN」两步流。 */
@Composable
fun PinChangeDialog(
  onDismiss: () -> Unit,
  onVerify: (String) -> Boolean,
  onSet: (pin: String) -> Unit,
) {
  var verified by remember { mutableStateOf(false) }
  if (!verified) {
    PinVerifyDialog(
      title = stringResource(R.string.app_lock_verify_title),
      message = stringResource(R.string.app_lock_change_verify_hint),
      onDismiss = onDismiss,
      onVerify = onVerify,
      onVerified = { verified = true },
    )
  } else {
    PinSetupDialog(onDismiss = onDismiss, onSet = onSet)
  }
}

/**
 * 弹窗骨架：标题 + 说明 +（错误）+ PIN 点位 + 键盘 + 取消/确定。
 *
 * 确定键的语义由调用方在 [onSubmit] 里定义（下一步 / 完成 / 验证）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinDialogScaffold(
  title: String,
  message: String,
  error: String?,
  pinLength: Int,
  onDigit: (Char) -> Unit,
  onDelete: () -> Unit,
  onSubmit: () -> Unit,
  submitEnabled: Boolean,
  onCancel: () -> Unit,
) {
  BasicAlertDialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(dismissOnClickOutside = false),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = AlertDialogDefaults.containerColor,
      tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = AlertDialogDefaults.titleContentColor,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = AlertDialogDefaults.textContentColor,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PinDots(length = pinLength)
        Spacer(Modifier.height(10.dp))
        Text(
          text = error ?: "",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        PinPad(
          enabled = true,
          onDigit = onDigit,
          onDelete = onDelete,
          onSubmit = onSubmit,
        )
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = onCancel) {
            Text(stringResource(R.string.generic_cancel))
          }
          Spacer(Modifier.width(8.dp))
          TextButton(onClick = onSubmit, enabled = submitEnabled) {
            Text(stringResource(R.string.generic_confirm), fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
