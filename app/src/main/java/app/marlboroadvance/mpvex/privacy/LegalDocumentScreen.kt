package app.marlboroadvance.mpvex.privacy

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 两份法律文档的资源位置与展示标题。
 *
 * 正文放在 `assets/legal/` 下，随 APK 一起分发 ⇒ 离线可读，
 * 不依赖任何 CDN、外部字体或远程脚本。
 */
enum class LegalDocument(
  val assetPath: String,
  val title: String,
) {
  PRIVACY_POLICY("legal/privacy_policy.html", "隐私政策"),
  USER_AGREEMENT("legal/user_agreement.html", "用户服务协议"),
}

/**
 * 法律文档阅读页（WebView）。
 *
 * ## 深色模式怎么生效
 *
 * HTML 里的配色全部走 CSS 变量，深色值同时挂在两个选择器下：
 * `:root[data-theme="dark"]` 与 `prefers-color-scheme: dark`。
 * 后者在老设备上不一定被支持（Chromium 76 才有），而本应用 minSdk 26，
 * 出厂 WebView 可能更老 ⇒ 这里**显式注入** `data-theme`，不赌系统能力。
 *
 * 判色取的是当前主题的 `background` 亮度，而不是系统夜间开关 ——
 * 应用自己有「深色 / 浅色 / 跟随系统」三档，用户在应用内选了浅色、
 * 系统是深色时，协议页必须跟应用一致，否则从设置点进来会突然变黑。
 *
 * ## 安全设置
 *
 * 协议页是纯静态文档：禁用 JavaScript、禁止文件跨域读取、禁止网络加载。
 * 正文里的外链（项目仓库）交给系统浏览器打开，不在 WebView 里导航 ——
 * 一旦在 WebView 里跳转，用户就离开了「协议」这个上下文，且没有返回路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegalDocumentScreen(
  document: LegalDocument,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

  // 系统返回键必须跟左上角箭头同义：回到调用方（确认页 / 设置页）。
  // 不拦截的话它会落到导航栈上，把用户直接带出设置页 —— 观感是「按返回把协议页
  // 和设置页一起关掉了」。本页是覆盖式渲染，没有自己的返回栈条目。
  BackHandler(enabled = true) { onBack() }

  // 正文只在文档或主题变化时读一次盘：一页几十 KB，滚动时反复读会拖慢触摸响应。
  val html =
    remember(document, isDark) {
      runCatching {
        val raw =
          context.assets.open(document.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val theme = if (isDark) "dark" else "light"
        // 只替换第一个 <html（文档开头那个），不会误伤正文里出现的同名文本
        raw.replaceFirst("<html", "<html data-theme=\"$theme\"")
      }.getOrElse {
        // 读不到就明说，不要给一片空白让用户以为页面还在加载
        "<html><body style=\"font-family:sans-serif;padding:24px\">" +
          "<p>无法读取${document.title}正文，请重新安装应用后再试。</p></body></html>"
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = document.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Default.ArrowBack,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        },
      )
    },
  ) { padding ->
    AndroidView(
      modifier = Modifier.fillMaxSize().padding(padding),
      factory = { ctx ->
        WebView(ctx).apply {
          // WebView 默认白底：深色主题下滚动到边界会闪一道白，先刷成透明
          setBackgroundColor(AndroidColor.TRANSPARENT)
          isVerticalScrollBarEnabled = false
          settings.javaScriptEnabled = false
          settings.allowFileAccess = false
          settings.allowContentAccess = false
          settings.blockNetworkLoads = true
          settings.textZoom = 100
          webViewClient =
            object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
              ): Boolean = openExternally(ctx, request.url)

              @Deprecated("Compile-time only fallback for API < 24 signatures")
              override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
              ): Boolean = openExternally(ctx, Uri.parse(url))
            }
          loadDataWithBaseURL(ASSET_BASE_URL, html, "text/html", "UTF-8", null)
        }
      },
      // 离开页面就把 WebView 彻底销毁：它持有独立渲染进程的句柄，
      // 只靠 GC 回收会让协议页反复进出后内存持续上涨。
      onRelease = { view ->
        runCatching {
          view.stopLoading()
          view.loadUrl("about:blank")
          view.destroy()
        }
      },
    )
  }
}

/** `assets/legal/` 作为基准地址，正文里的相对路径才能解析。 */
private const val ASSET_BASE_URL = "file:///android_asset/"

/**
 * 把点击交给系统浏览器。
 *
 * @return true 表示「已由外部处理，WebView 不要导航」
 */
private fun openExternally(
  context: android.content.Context,
  uri: Uri,
): Boolean {
  val scheme = uri.scheme?.lowercase()
  if (scheme != "http" && scheme != "https") return false
  return runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    true
  }.getOrDefault(false)
}
