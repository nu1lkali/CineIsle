package app.marlboroadvance.mpvex.ui.browser.networkstreaming.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.ui.browser.emby.components.rememberShimmer
import app.marlboroadvance.mpvex.ui.browser.emby.components.shimmer
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络照片缩略图（网格卡用）。
 *
 * 与 Emby 照片卡的 [app.marlboroadvance.mpvex.ui.browser.emby.photo.EmbyPhotoThumbnail]
 * 同一套视觉语言（骨架屏 / 失败占位 + 重试 / Crop 填满），差异只在数据源：
 * 字节从 SMB/WebDAV/FTP 会话里拉，没有服务端缩放与转码兜底
 * （见 [NetworkPhotoImageLoader] 的 KDoc）。
 *
 * @param size 请求的解码尺寸；null = 按组件约束采样（更省内存）。
 * @param sign AList 下载签名（[NetworkFile.sign]）；其他协议 / 文件夹封面传 null。
 *   文件夹封面在缓存里只存了 path（拿不到签名），由 AlistClient 的 `fs/get` 兜底。
 */
@Composable
fun NetworkPhotoThumbnail(
  connectionId: Long,
  path: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
  sign: String? = null,
) {
  val context = LocalContext.current
  val imageLoader = remember(context) { NetworkPhotoImageLoader.get(context) }

  // ⚠️ 这里**不要**加「重载计数器 / nonce」之类的东西去换一个新 request。
  //    v79 试过（`remember(connectionId, path, sign, retryNonce)`），是**无效功**：
  //    coil3 3.6.3 的 `ImageRequest` 是 final class 且**重写了 equals**
  //    （已 javap 核验，构造器 25 个参数逐个比较），字段完全相同的新实例
  //    与旧实例 **相等**；`AsyncImagePainter$Input.equals` 又走
  //    `modelEqualityDelegate.equals(request, other.request)` ⇒ 判等通过 ⇒
  //    `set_input` 认为「没变」⇒ **不会 restart**。
  //    （下拉刷新之所以有效，是因为 `fs/list` 给了**新 sign**，
  //     `NetworkPhotoRequest` 是 data class，sign 一变 request 才真的不等。）
  //    想强制重发就用 [coil3.compose.AsyncImagePainter.restart]，见下面 onRetry。
  val request = remember(connectionId, path, sign) {
    NetworkPhotoImageLoader.request(context, connectionId, path, sign = sign)
  }
  // 内存缓存里已有 ⇒ 首帧就有图，不必画骨架屏。
  // 否则返回上一级时每张封面都会「闪一下」（painter 从 Loading 重新走一遍），
  // 而图其实一直在缓存里。
  val memoryCached = remember(connectionId, path) {
    NetworkPhotoImageLoader.isMemoryCached(context, connectionId, path)
  }
  val painter = rememberAsyncImagePainter(
    model = request,
    imageLoader = imageLoader,
    contentScale = contentScale,
  )
  val state by painter.state.collectAsState()
  val shimmer = rememberShimmer()
  val scope = rememberCoroutineScope()

  /**
   * 重载：**先清掉这一张的缓存**，再让 painter 重走一次请求。
   *
   * 两步各有各的用途，别合并：
   * 1. `evict()` —— 清内存 + 磁盘缓存。取流失败常常在磁盘里留下半截数据，
   *    不清的话重走同一个 key 又会命中那块坏数据。见 [NetworkPhotoImageLoader.evict]。
   * 2. `painter.restart()` —— Coil 官方的「重发当前请求」入口。
   *    已 javap 核验 coil3 3.6.3：`restart()` → `launchJob()` →
   *    `imageLoader.execute(input.request)`，**确实会重新走完整管线**
   *    （内存缓存 → 磁盘缓存 → 网络）。它唯一的守卫是 `isRemembered`
   *    （`false` 时整个方法是空操作）—— 用户能看见并点到这个按钮，
   *    说明 item 正在组合里，必然是 true，所以这里是安全的。
   *
   * ⭐ 真正让「重载刷不出来」的是**取流那一层**，不在这个按钮上：
   *    AList 的签名会过期，旧签名每次都 401，而 `fs/get` 对同一个文件
   *    返回同一个结果 ⇒ 重发多少次都撞同一堵墙（下拉刷新能好，是因为
   *    重新 `fs/list` 拿到了新签名）。修复见
   *    [app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.AlistClient.getFileStream]。
   *
   * 清缓存是磁盘 IO，放 IO 线程；`restart()` 回主线程调。
   */
  val onRetry: () -> Unit = {
    scope.launch {
      withContext(Dispatchers.IO) {
        NetworkPhotoImageLoader.evict(context, connectionId, path)
      }
      painter.restart()
    }
  }

  // 失败后**自动重载一次**：网络抖一下（首包超时 / 会话过期）能自己救回来。
  // 只试一次 —— 真挂了（文件已删 / 编码解不动）就别在后台空转，留给用户点。
  // 延迟 600ms 是给网络栈喘口气，也避开「刚失败立刻又失败」的瞬时状态。
  var autoRetried by remember(connectionId, path) { mutableStateOf(false) }
  LaunchedEffect(connectionId, path, state) {
    if (state is AsyncImagePainter.State.Error && !autoRetried) {
      autoRetried = true
      delay(600)
      onRetry()
    }
  }

  Box(modifier = modifier) {
    if (painter != null) {
      Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
      )
    }
    when (state) {
      is AsyncImagePainter.State.Loading -> {
        // 缓存命中就别画骨架屏了：那一下闪烁纯属噪音
        if (!memoryCached) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .shimmer(shimmer),
          )
        }
      }

      is AsyncImagePainter.State.Error -> {
        // ⭐⭐ 这里**绝不能**给整块加 `clickable` —— 曾经的写法就是这样，
        //    结果整张封面（网格卡里是 1:1 的大图、几乎占满卡面）变成了一个巨大的
        //    「重试」热区，把外层卡片的 onClick（进入文件夹）和 onLongClick（长按菜单）
        //    **全部吃掉** ⇒ 用户反馈「一个文件夹封面没加载出来，这个文件夹就点都点不进去了」。
        //
        //    ⚠️ 封面加载失败和「能不能进这个文件夹」是**两件完全无关的事**：
        //    用户点卡片绝大多数时候就是想进去，重试只是偶尔的次要意图。
        //    ⇒ 重试只占右下角一小块（[RETRY_TOUCH_SIZE]，Material 无障碍最小触控尺寸），
        //      其余区域保持「不消费点击」—— Compose 里没有 `clickable` 的 Box
        //      **不会**拦截手势，点击会正常冒泡到外层卡片。
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp),
          )
          IconButton(
            onClick = onRetry,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .size(RETRY_TOUCH_SIZE),
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "重新加载",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }

      else -> Unit
    }
  }
}

/**
 * 失败占位里「重新加载」按钮的触控区边长。
 *
 * 48dp 是 Material 规定的**最小可触控尺寸** —— 再小就很难点中，而它又必须和小小的
 * 破图图标共存于同一张缩略图里。
 *
 * ⚠️ 别为了「更好点」把它放大成整块：那会把外层卡片的进入 / 长按手势全吃掉
 *    （详见 [NetworkPhotoThumbnail] 里错误分支的注释）。
 */
private val RETRY_TOUCH_SIZE = 48.dp
