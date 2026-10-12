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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.lifecycle.ProcessLifecycleOwner
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AppLockMethod
import app.marlboroadvance.mpvex.preferences.AppLockPreferences
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * 应用锁门禁（清单 b7）。
 *
 * 用法：包住 MainActivity 的整个内容。锁着时 [content] **整块不组合** ——
 * 背后的 Navigator 一帧都不布局，解锁后才开始首帧，没有「背后先跑一遍」的浪费。
 *
 * 解锁方式由偏好决定：[AppLockMethod.PIN] 用数字键盘，[AppLockMethod.PATTERN]
 * 用九宫格图案 —— 具体输入控件在 [AppLockUnlockScreen] 里分叉。
 *
 * ## 什么时候锁
 *
 *  · 冷启动：开锁的用户每次进程启动都锁（初始状态直接按偏好算）；
 *  · 真正离开应用再回来：离开超过 [AppLockPreferences.RELOCK_GRACE_MS]（5 秒）才重新锁。
 *
 * ## 为什么盯的是**进程**生命周期，不是当前 Activity 的
 *
 * 播放页（mpv / GSY / 视界流）是本 App 的**另一个 Activity**（`singleTask`，
 * 独立任务栈）。从 MainActivity 打开它时，MainActivity 会走 `ON_STOP` ——
 * 如果盯 Activity 的 `ON_STOP / ON_START`，「跳播放页」和「退到桌面」在事件层面
 * 一模一样，只能靠离开时长区分，于是「看完视频回来」必然被重新锁上（太激进）。
 *
 * [ProcessLifecycleOwner] 的 `ON_STOP` 只在**本 App 所有 Activity 都不在前台**
 * 时才派发（且自带 700ms 去抖），于是：跳自己家的播放页 ⇒ 进程始终在前台 ⇒
 * 不触发重锁；回桌面 / 切别的 App ⇒ 正常触发。
 *
 * ## 边界（有意为之）
 *
 * 只守 MainActivity。播放页本身不在门禁里 —— 儿童能碰到播放页的前提是已经过了这道门。
 * 真要连播放页一起锁，得给两个 PlayerActivity 各接一份，那是另一个量级的活。
 *
 * ⚠️ 系统文件选择器（SAF）跑在**别的进程**里：从 MainActivity 拉起它同样算「离开应用」，
 * 选完回来（超过 5 秒）仍会重锁 —— 这个口子没堵，需要的话得在发起处临时挂一个豁免标记。
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
  val prefs = koinInject<AppLockPreferences>()
  // 初始状态按偏好算：开了锁 + 当前方式已有凭证 ⇒ 进程一启动就是锁着的
  var locked by remember {
    mutableStateOf(prefs.enabled.get() && prefs.hasCredential())
  }
  // 什么时候退的后台（0 = 本进程还没退过后台）；只在进程级 ON_STOP 里写
  var backgroundedAt by remember { mutableLongStateOf(0L) }

  val processLifecycle = ProcessLifecycleOwner.get().lifecycle
  DisposableEffect(processLifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
        Lifecycle.Event.ON_START -> {
          val awayMs = System.currentTimeMillis() - backgroundedAt
          val shouldLock = prefs.enabled.get() && prefs.hasCredential()
          // backgroundedAt > 0 才说明本进程真的退过后台 —— 进程启动时那次 ON_START 不算
          if (shouldLock && backgroundedAt > 0L && awayMs > AppLockPreferences.RELOCK_GRACE_MS) {
            locked = true
          }
        }
        else -> Unit
      }
    }
    processLifecycle.addObserver(observer)
    onDispose { processLifecycle.removeObserver(observer) }
  }

  if (locked) {
    AppLockUnlockScreen(
      method = prefs.method.get(),
      autoUnlock = prefs.autoUnlock.get(),
      onVerifyPin = prefs::verifyPin,
      onVerifyPattern = prefs::verifyPattern,
      onUnlocked = { locked = false },
    )
  } else {
    content()
  }
}

/**
 * 全屏解锁页（PIN / 图案共用外壳）。
 *
 * 连续输错 [AppLockPreferences.MAX_ATTEMPTS] 次进 30 秒冷却（带倒计时）——
 * PIN 只有 4~6 位、图案只有 9 个点，不设防就是穷举题。计数器只在内存里，
 * 进程重启即忘（重启本身也是一次「重新锁上」）。
 *
 * [autoUnlock] 为真时：PIN 每输一位就试一次，**输对即解锁**（隐藏确定键）；
 * 图案本来就是画完即验，所以这个开关只影响 PIN 的手感。
 */
@Composable
private fun AppLockUnlockScreen(
  method: String,
  autoUnlock: Boolean,
  onVerifyPin: (String) -> Boolean,
  onVerifyPattern: (List<Int>) -> Boolean,
  onUnlocked: () -> Unit,
) {
  val isPattern = method == AppLockMethod.PATTERN

  var pin by remember { mutableStateOf("") }
  var wrong by remember { mutableStateOf(false) }
  var hint by remember { mutableStateOf<String?>(null) }
  var attempts by remember { mutableIntStateOf(0) }
  var cooldownEndAt by remember { mutableLongStateOf(0L) }
  var cooldownRemain by remember { mutableIntStateOf(0) }

  // 弹窗/分支回调里不能用 stringResource（非 Composable 上下文）—— 先取好文案
  val cooldownText = stringResource(R.string.app_lock_cooldown, cooldownRemain)
  val wrongPinText = stringResource(R.string.app_lock_wrong_pin)
  val wrongPatternText = stringResource(R.string.app_lock_wrong_pattern)
  val tooShortText = stringResource(R.string.app_lock_pattern_too_short)
  val autoHintText = stringResource(R.string.app_lock_auto_unlock_hint)

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

  // 图案错误红闪：~0.7s 后自动复位（复位瞬间控件会清掉红图）
  LaunchedEffect(wrong) {
    if (wrong && isPattern) {
      delay(700)
      wrong = false
    }
  }

  fun fail() {
    attempts += 1
    wrong = true
    pin = ""
    if (attempts >= AppLockPreferences.MAX_ATTEMPTS) {
      cooldownEndAt = System.currentTimeMillis() + AppLockPreferences.COOLDOWN_MS
      wrong = false
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
        text = stringResource(
          if (isPattern) R.string.app_lock_pattern_unlock_title else R.string.app_lock_unlock_title,
        ),
        style = MaterialTheme.typography.titleMedium,
      )

      Spacer(Modifier.height(20.dp))

      if (isPattern) {
        PatternLockView(
          modifier = Modifier.fillMaxWidth(0.72f),
          enabled = cooldownRemain == 0,
          error = wrong,
          onPattern = { pattern ->
            if (cooldownRemain == 0) {
              when {
                // 太短要**明说**，且不算一次失败尝试（还没构成一个图案）
                pattern.size < AppLockPreferences.MIN_PATTERN_DOTS -> {
                  hint = tooShortText
                  wrong = false
                }
                onVerifyPattern(pattern) -> onUnlocked()
                else -> {
                  hint = null
                  fail()
                }
              }
            }
          },
        )
      } else {
        PinDots(length = pin.length)
      }

      Spacer(Modifier.height(12.dp))

      val hintText = hint
      val message: String = when {
        cooldownRemain > 0 -> cooldownText
        wrong -> if (isPattern) wrongPatternText else wrongPinText
        hintText != null -> hintText
        autoUnlock && !isPattern -> autoHintText
        else -> ""
      }
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (cooldownRemain > 0 || wrong || hintText != null) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
      )

      Spacer(Modifier.height(16.dp))

      if (!isPattern) {
        PinPad(
          enabled = cooldownRemain == 0,
          showSubmit = !autoUnlock,
          onDigit = { digit ->
            wrong = false
            hint = null
            if (pin.length < AppLockPreferences.MAX_PIN_LENGTH) {
              val next = pin + digit
              pin = next
              if (autoUnlock) {
                when {
                  onVerifyPin(next) -> onUnlocked()
                  // 已到最长位数还没中 ⇒ 判定本次失败（给用户明确反馈，别干等着）
                  next.length >= AppLockPreferences.MAX_PIN_LENGTH -> fail()
                }
              }
            }
          },
          onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
          onSubmit = {
            val entered = pin
            if (entered.isNotEmpty() && cooldownRemain == 0) {
              if (onVerifyPin(entered)) onUnlocked() else fail()
            }
          },
        )
      }
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
 * [showSubmit] 为假时收起「确定」键（自动解锁模式下每输一位就自动验，
 * 那个键没有存在意义；留着反而让人以为必须点它）—— 位置留一个等大占位，
 * 保持 0 与删除键的位置不变（键盘不长脚）。
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
  showSubmit: Boolean = true,
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
      if (showSubmit) {
        PinKey(enabled = enabled, onClick = onSubmit) {
          Icon(
            Icons.Outlined.Check,
            contentDescription = stringResource(R.string.generic_confirm),
            modifier = Modifier.size(26.dp),
          )
        }
      } else {
        Spacer(Modifier.size(80.dp))
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
 * 应用锁「已开启」时的管理入口：改凭证 / 开关自动解锁 / 关锁。
 *
 * 这个弹窗本身不执行任何破坏性动作 —— 关锁后面还跟着一道验证弹窗
 * （防止儿童自己进设置关掉），改凭证则是「先验证旧的」流程。
 * 它只负责把用户领到对应流程，外加一个可以就地切的自动解锁开关。
 */
@Composable
fun AppLockManageDialog(
  method: String,
  autoUnlock: Boolean,
  onToggleAutoUnlock: (Boolean) -> Unit,
  onDisable: () -> Unit,
  onChangeCredential: () -> Unit,
  onDismiss: () -> Unit,
) {
  val isPattern = method == AppLockMethod.PATTERN
  AlertDialog(
    onDismissRequest = onDismiss,
    // 允许点弹窗外部任意空白处关闭（dismiss = 放弃/取消，所有流程都能安全中断：
    // 关锁/改凭证后面还有一道验证，点空白只是退出，不会误执行任何动作）。
    // 只靠系统返回手势是最难发现的一种关法 —— 用户找不到出口就会以为卡死。
    properties = DialogProperties(dismissOnClickOutside = true),
    title = { Text(text = stringResource(R.string.app_lock_title)) },
    text = {
      Column {
        Text(
          text = stringResource(R.string.app_lock_manage_message),
          style = MaterialTheme.typography.bodyMedium,
          color = AlertDialogDefaults.textContentColor,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.app_lock_auto_unlock_title),
              style = MaterialTheme.typography.bodyLarge,
            )
            Text(
              text = stringResource(R.string.app_lock_auto_unlock_summary),
              style = MaterialTheme.typography.bodySmall,
              color = AlertDialogDefaults.textContentColor,
            )
          }
          Spacer(Modifier.width(12.dp))
          Switch(checked = autoUnlock, onCheckedChange = onToggleAutoUnlock)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onChangeCredential) {
        Text(
          text = stringResource(
            if (isPattern) R.string.app_lock_change_pattern else R.string.app_lock_change_pin,
          ),
        )
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
 * 解锁方式选择（应用锁关闭时点入口出现）：数字 PIN / 九宫格图案。
 *
 * 只在「还没开锁」时走这一层 —— 已开锁想换方式，走「关闭 → 重新开启」，
 * 少一条「切换后旧凭证怎么办」的模糊路径（安全相关的东西宁可多一步）。
 */
@Composable
fun UnlockMethodDialog(
  onPickPin: () -> Unit,
  onPickPattern: () -> Unit,
  onDismiss: () -> Unit,
) {
  BasicAlertDialog(
    onDismissRequest = onDismiss,
    // 允许点弹窗外部任意空白处关闭（dismiss = 放弃/取消，所有流程都能安全中断：
    // 关锁/改凭证后面还有一道验证，点空白只是退出，不会误执行任何动作）。
    // 只靠系统返回手势是最难发现的一种关法 —— 用户找不到出口就会以为卡死。
    properties = DialogProperties(dismissOnClickOutside = true),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = AlertDialogDefaults.containerColor,
      tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(
          text = stringResource(R.string.app_lock_method_title),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = AlertDialogDefaults.titleContentColor,
        )
        Spacer(Modifier.height(16.dp))
        MethodRow(
          icon = Icons.Outlined.Dialpad,
          title = stringResource(R.string.app_lock_method_pin),
          description = stringResource(R.string.app_lock_method_pin_desc),
          onClick = onPickPin,
        )
        Spacer(Modifier.height(8.dp))
        MethodRow(
          icon = Icons.Outlined.Grid3x3,
          title = stringResource(R.string.app_lock_method_pattern),
          description = stringResource(R.string.app_lock_method_pattern_desc),
          onClick = onPickPattern,
        )
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.generic_cancel))
          }
        }
      }
    }
  }
}

/** 方式选择里的一行：图标 + 标题 + 说明，整行可点。 */
@Composable
private fun MethodRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  description: String,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
      Spacer(Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
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
    // 允许点弹窗外部任意空白处关闭（dismiss = 放弃/取消，所有流程都能安全中断：
    // 关锁/改凭证后面还有一道验证，点空白只是退出，不会误执行任何动作）。
    // 只靠系统返回手势是最难发现的一种关法 —— 用户找不到出口就会以为卡死。
    properties = DialogProperties(dismissOnClickOutside = true),
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

// ────────────────────────────────────────────────────────────────────────
// 九宫格图案的三个弹窗（设置 / 验证 / 修改）
// ────────────────────────────────────────────────────────────────────────

/**
 * 图案弹窗骨架：标题 + 说明 +（错误）+ 九宫格 + 取消。
 *
 * 图案没有「确定」键 —— 松手那一刻就是提交（[onPattern]）。
 * [resetKey] 变化时重建控件，把上一步画过的图案擦掉
 * （设置流程第一步画完进第二步，不该还留着第一步的线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternDialogScaffold(
  title: String,
  message: String,
  error: String?,
  flash: Boolean,
  resetKey: Any,
  onPattern: (List<Int>) -> Unit,
  onCancel: () -> Unit,
) {
  BasicAlertDialog(
    onDismissRequest = onCancel,
    // 允许点弹窗外部任意空白处关闭（dismiss = 放弃/取消，所有流程都能安全中断：
    // 关锁/改凭证后面还有一道验证，点空白只是退出，不会误执行任何动作）。
    // 只靠系统返回手势是最难发现的一种关法 —— 用户找不到出口就会以为卡死。
    properties = DialogProperties(dismissOnClickOutside = true),
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
        Spacer(Modifier.height(20.dp))
        key(resetKey) {
          PatternLockView(
            modifier = Modifier.fillMaxWidth(0.8f),
            error = flash,
            onPattern = onPattern,
          )
        }
        Spacer(Modifier.height(12.dp))
        Text(
          text = error ?: "",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onCancel) {
            Text(stringResource(R.string.generic_cancel))
          }
        }
      }
    }
  }
}

/**
 * 设置图案：画一遍 → 再画一遍确认，两遍一致才落盘。
 *
 * ⚠️ 与 PIN 一样，中途关掉视为放弃，不写「半个图案」。
 */
@Composable
fun PatternSetupDialog(
  onDismiss: () -> Unit,
  onSet: (pattern: List<Int>) -> Unit,
) {
  var step by remember { mutableStateOf(1) }
  var first by remember { mutableStateOf<List<Int>>(emptyList()) }
  var error by remember { mutableStateOf<String?>(null) }
  var flash by remember { mutableStateOf(false) }

  val tooShortText = stringResource(R.string.app_lock_pattern_too_short)
  val mismatchText = stringResource(R.string.app_lock_pattern_mismatch)

  // 红闪 ~0.7s 后自动复位（复位那刻控件会清掉红图）
  LaunchedEffect(flash) {
    if (flash) {
      delay(700)
      flash = false
    }
  }

  PatternDialogScaffold(
    title = stringResource(
      if (step == 1) R.string.app_lock_pattern_setup_title else R.string.app_lock_pattern_confirm_title,
    ),
    message = stringResource(
      if (step == 1) R.string.app_lock_pattern_setup_hint else R.string.app_lock_pattern_confirm_hint,
    ),
    error = error,
    flash = flash,
    resetKey = step,
    onPattern = { pattern ->
      when {
        pattern.size < AppLockPreferences.MIN_PATTERN_DOTS -> {
          error = tooShortText
          flash = true
        }
        step == 1 -> {
          first = pattern
          error = null
          step = 2
        }
        pattern == first -> {
          onSet(first)
          onDismiss()
        }
        else -> {
          error = mismatchText
          flash = true
          // 退回第一步重来，避免用户在第二步里干瞪眼
          first = emptyList()
          step = 1
        }
      }
    },
    onCancel = onDismiss,
  )
}

/**
 * 验证当前图案（关锁 / 改图案的前置步骤）。
 *
 * 与 [PinVerifyDialog] 一样：输错只在本弹窗里提示，不进全局冷却。
 */
@Composable
fun PatternVerifyDialog(
  title: String,
  message: String,
  onDismiss: () -> Unit,
  onVerify: (List<Int>) -> Boolean,
  onVerified: () -> Unit,
) {
  var error by remember { mutableStateOf<String?>(null) }
  var flash by remember { mutableStateOf(false) }

  val wrongText = stringResource(R.string.app_lock_wrong_pattern)
  val tooShortText = stringResource(R.string.app_lock_pattern_too_short)

  // 红闪 ~0.7s 后自动复位（复位那刻控件会清掉红图）—— 
  // ⚠️ 这里**不能**用变化的 resetKey 重建控件：那会在红闪刚开始时就把图擦掉，看不见错误。
  LaunchedEffect(flash) {
    if (flash) {
      delay(700)
      flash = false
    }
  }

  PatternDialogScaffold(
    title = title,
    message = message,
    error = error,
    flash = flash,
    resetKey = Unit,
    onPattern = { pattern ->
      when {
        pattern.size < AppLockPreferences.MIN_PATTERN_DOTS -> {
          error = tooShortText
          flash = true
        }
        onVerify(pattern) -> {
          onVerified()
          onDismiss()
        }
        else -> {
          error = wrongText
          flash = true
        }
      }
    },
    onCancel = onDismiss,
  )
}

/** 改图案：先验证旧的，通过后直接进入「设置新图案」两步流。 */
@Composable
fun PatternChangeDialog(
  onDismiss: () -> Unit,
  onVerify: (List<Int>) -> Boolean,
  onSet: (pattern: List<Int>) -> Unit,
) {
  var verified by remember { mutableStateOf(false) }
  if (!verified) {
    PatternVerifyDialog(
      title = stringResource(R.string.app_lock_verify_title),
      message = stringResource(R.string.app_lock_change_verify_hint),
      onDismiss = onDismiss,
      onVerify = onVerify,
      onVerified = { verified = true },
    )
  } else {
    PatternSetupDialog(onDismiss = onDismiss, onSet = onSet)
  }
}
