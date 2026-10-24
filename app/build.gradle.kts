import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
  alias(libs.plugins.aboutlibraries)
}

// ── release lint 开关（清单 1.2）──
// 定义在 android {} 之前，好让 lint { checkReleaseBuilds } 和下面的
// tasks.matching { lintVital } 共用同一个值，避免两处各读一遍属性。
// 取普通 Boolean 而非 Provider：checkReleaseBuilds 的签名不接受 Provider。
// 这里用 orNull 而不是 .get()，是为了不强制求值、不打断配置缓存。
val lintVitalOn: Boolean = providers.gradleProperty("lintVital").orNull?.toBoolean() ?: false

android {
  namespace = "app.marlboroadvance.mpvex"
  compileSdk = 37

  // 开发期快速出包开关（只打单个 ABI 包，避免 4×151MB 原生库合并把构建拖超时）：
  //   -Pabi=x86_64      只出 x86_64 单包（模拟器用这个）
  //   -Pabi=arm64-v8a   只出 arm64 单包（真机用这个）
  //   -Parm64Only       等价于 -Pabi=arm64-v8a（保留旧用法，向后兼容）
  // 不带任何参数时，构建行为与原来完全一致（4 个 ABI 拆包 + universal）。
  val singleAbi: String? =
    (project.findProperty("abi") as String?)?.takeIf { it.isNotBlank() }
      ?: if (project.hasProperty("arm64Only")) "arm64-v8a" else null

  defaultConfig {
    // 应用标识（安装包名）：不再沿用上游的 app.marlboroadvance.mpvex，
    // 改为项目自身的 app.cineisle.player。namespace 保持不动 ——
    // 它只决定 R / BuildConfig 的生成包名，改名会牵动全量源码，收益为零。
    applicationId = "app.cineisle.player"
    minSdk = 26
    targetSdk = 36
    // versionCode 决定能否覆盖安装：每发一版都要涨（APK 里的最终值 = versionCode * 10 + ABI 码）
    // versionCode 62（versionName 仍为 1.5.4，功能轮但不涨名）：网络浏览合并照片库能力 ——
    //   ① SMB/WebDAV/FTP 目录按「文件夹 / 图片 / 视频 / 其他」四分法混合展示，
    //      照片不再被 mimeType 过滤掉（此前只能扫到视频）；
    //   ② 统一网格卡 + 右上角类型小角标，视图切换与本地文件夹浏览共用 FolderViewMode；
    //   ③ 收藏目录（Room network_favorites，DB v15 + MIGRATION_14_15）：
    //      浏览页星形收藏、网络主界面「收藏的目录」横排快捷入口（可删除）；
    //   ④ 照片查看器（缩放 4× 橡皮筋 / 下拉关闭 / 幻灯片淡入淡出 + 倒计时 /
    //      保存到照片保存位置 / 前后预加载 / 沉浸模式），点击即看、无详情页；
    //   ⑤ 视频点击即播 mpv 队列（network_file_paths 同序表修连播进度串片）；
    //   ⑥ 随机播放视频 / 随机播放图片（洗牌 + 同一数量设置，空类型给 Toast 提示）；
    //   ⑦ 网络照片走 Coil 3 自定义 Fetcher（流式拉全图 + 采样解码 + 128MB 磁盘缓存），
    //      落盘逻辑抽成 PhotoStorage 与 Emby 照片保存器共用（保存位置不分叉）。
    // versionCode 61（versionName 仍为 1.5.4，修复轮不涨名）：超大媒体库「一直加载中 / 无响应」修复 ——
    //   ① 媒体库页普通分页 StartIndex 改「服务端实际返回条数」游标（consumedCount），
    //      不再用被客户端过滤/去重削过的 items.size（fresh=0 时冻结 ⇒ 同一页无限重拉）；
    //   ② 续页加并发闸门 loadingMore（触底回调每滚动帧都评估，没闸门会并发重拉同一页）；
    //   ③ 某页「全新条目为 0」/ 空页即把 totalCount 对齐已消耗，续页条件自然失效；
    //   ④ items.none{} O(n²) 去重改 HashSet；visibleItems 五遍 O(n) 过滤链 remember 化
    //      （此前每次重组全量重算，大库翻深后主线程全耗在这，界面发涩像卡死）；
    //   ⑤ 随机播放 PhotoRandomQueue 加两层上限（单 parent 2000 条 / 最多 4 个 parent），
    //      防百万张相册把进程内缓存撑爆（OOM）。
    // versionName 涨到 1.5.4：照片浏览器交互补齐 ——
    //   ① 下拉关闭大图（未放大时竖向拖拽，跟手位移 + 缩小变暗，过阈值松手退出）；
    //   ② Telephoto ZoomSpec 显式开橡皮筋回弹、最大倍率 2×→4×；
    //   ③ 记住上次浏览位置（按相册 Id 记页码，网格页重新进入时定位回去）；
    //   ④ 长按弹底部操作面板（保存原图 / 另存 JPEG / 收藏 / 旋转 / 信息），
    //      旧版「长按直接弹保存对话框」收编进面板第一组，长按只剩一个出口；
    //   ⑤ 底部缩略图胶片条（设置开关，默认关）；
    //   ⑥ 幻灯片转场由硬切改「淡出→换页→淡入」（手动滑动仍是跟手滑动）；
    //   ⑦ 「照片保存位置」下线系统 SAF 选择器，改弹 App 自研的 FolderPickerDialog。
    // versionCode 63：网络浏览（SMB/WebDAV/FTP）一轮体验补齐 ——
    //   ① 文件夹封面：目录内第一张图片作封面，懒加载 + 两级缓存
    //      （进程内 LRU → Room 持久表，含负缓存 / 下拉刷新失效 / 30 天 TTL），
    //      设置页可关；
    //   ② 排序多维度（名称 / 时间 / 大小 / 类型 / 随机）+ 升降序箭头，
    //      内容区控制条，切排序纯本地重排不发请求；
    //   ③ 类型筛选 chips（显示文件夹 / 图片 / 媒体 / 显示全部，复选框观感）；
    //   ④ 本地搜索（对已拉到的目录内容按名称过滤，零请求）；
    //   ⑤ 顶栏标题改显当前文件夹名（原显连接名）；
    //   ⑥ 收藏 / 取消收藏弹 toast；网格标题行数可设（一行 / 两行 / 全显）。
    // versionCode 63 同号补充（未发布，合并进 63）：
    //   ⑦ 【严重】CryptoUtils 加解密全程加锁：Keystore 的 Cipher 并发使用会互相
    //      prune（Invalid operation handle → IllegalBlockSizeException），网络浏览
    //      一屏十几个封面并发触发 getConnectionById → withDecryptedPassword，
    //      输的线程密码被兜底成空串 ⇒ 服务器 401 ⇒ 「只有一部分文件夹图片能
    //      加载 / 下载失败」。表现为随机部分失败、与文件名无关；
    //   ⑧ 筛选 chips 四联收成单个「筛选」按钮 + 小窗 Checkbox 多选（对齐 Emby
    //      媒体库筛选范式，多选不关窗，有筛选生效时按钮高亮）；
    //   ⑨ 排序控件返工：方向箭头包进类型 chip；菜单选中项 append ↑/↓ + 主色
    //      高亮、去掉勾、再点已选项只翻方向（对齐 Emby SortMenu 范式）；
    //   ⑩ 网格/列表卡片副标题日期格式「12月29」→「MM/dd」（Locale.US，纯数字）。
    // versionName 涨到 1.5.3：照片浏览器换 Coil 3 渲染（按显示尺寸采样 + 双层缓存），
    // 预加载张数可在设置页配（1~3）；加载中给骨架屏 / 进度条，失败给占位图 + 重试；
    // 点图片任意处进沉浸全屏（顶栏也有入口）；修幻灯片只翻一半、右下角加倒计时；
    // 照片网格工具行改为占高度的「安全区」，上滑不再与卡片叠在一起；
    // 全项目错误态上方加 Oops 动态指示牌动画。
    // 1.5.2：照片网格页两个真机问题（工具行压住第一排卡片 / 相册态返回键被拦）。
    // 1.5.1：混合库改「内容即所见」+ 子文件夹无限加载修复 + 照片卡统一等比。
    // 64：新增 **AList 原生协议**（不是 WebDAV）—— 一个请求拿整个目录（白送每条
    //     sign 与 thumb）、token 换一次用一整场、`/d/...?sign=` 直链可播（播放不用
    //     过本地代理）。契约实证来自 D:\project\AlistClientN。
    //     顺带修掉 WebDAV「前 30 个文件夹正常、后面全挂」的根因：取流原来每次
    //     `OkHttpSardine()` 新建（自带独立连接池 ⇒ 每张封面一条新 TCP 连接、
    //     keep-alive 完全不复用），叠加错误路径未关的响应体，连接与 fd 堆到耗尽。
    //     现在改成复用长活的 Sardine；AList 侧则用进程内共享的 OkHttpClient，
    //     且所有出口都保证 response body 被关闭。
    //     顶栏瘦身：收藏 / 随机播放 / 视图切换 三个图标收进竖三点「更多」菜单，
    //     顶栏常驻图标 5 → 3（更多 / 搜索 / 设置），宽度还给文件夹标题。
    // 65：【严重】AList 任何图片都加载不出来（封面 + 图片文件全挂，但目录能列）。
    //     根因（AList 服务端源码实证）：下载路由 `GET /d/*path` 挂的是
    //     `middlewares.Down(sign.Verify)`，**没有 Auth**；服务端开启「签名所有」/
    //     存储级签名 / 目录密码时 (needSign) 只认 `?sign=`，缺了直接 401 Abort，
    //     请求里的 `Authorization` 头**根本不被读取**。而列目录走 `/api/fs/list`
    //     （middlewares.Auth）只认 Authorization ⇒ 症状就是「目录能列、图片全挂」。
    //     修复：取流三级取值 —— ① 调用方给的签名（fs/list 条目自带）→ ② 进程内
    //     签名缓存（每次列目录顺手记）→ ③ 失败时 fs/get 现换新鲜签名 / raw_url 兜底；
    //     并把 `sign` 从卡片/查看器/保存一路贯穿到客户端。顺带把 `text/html` 响应
    //     判为失败（AList 静态兜底会把没匹配的路径返回 200 + index.html，放过去
    //     只会在解码阶段静默失败）。
    //     长按菜单改回 Emby 媒体库范式：锚定手指位置的 DropdownMenu 气泡菜单
    //     （原来做成了居中大 AlertDialog —— 自由发挥错了，Alist 库与 Emby 媒体库
    //     除接口外结构一致，交互观感该是同一套）；重命名 / 删除确认仍走小对话框。
    // 66：【严重】Emby 媒体库里「点进文件夹只有子目录、里面的视频全不见」。
    //     根因：库类型（CollectionType）被当成「文件夹里装了什么」的约束 ——
    //     点进一个真实文件夹后，子页仍套 `IncludeItemTypes=Movie/Series`
    //     + `MediaTypes=Video` + `IsFolder=false`，于是这一层里没被 Emby 识别成
    //     Movie/Series 的内容（`Type=Video` 的视频、普通目录）被服务端**整层滤空**。
    //     对照 Yamby（com.hush.yamby_2.1.0.11 反汇编实证）：它浏览时永远
    //     `Recursive=true`、不传 `IsFolder`/`MediaTypes`，且点进 Folder 走
    //     `RouteFolder` **原样继承父页过滤档、从不按库类型重新收窄**。
    //     修复：新增 `isFolderDive` —— 从 `Type=Folder` 下钻的页面改用「内容原样」
    //     口径（不限类型 / 不限媒体形态 / 不限容器），并把 Series / Season 也算容器；
    //     该口径下不再排掉 Season（Season 也是文件夹里真实存在的子目录）。
    //     库根（CollectionFolder / UserView）与剧集层级（Series→Season→Episode）口径不变。
    // 66b：网络浏览「网格视图里排序没反应」。根因：NetworkBrowserScreen 的 gridItems
    //     写死了 `(photos + videos).sortedBy { name }`，把用户选的排序键又覆盖回名称序；
    //     列表视图走 files 分节（尊重排序）所以正常，纯目录的那一层整层都是文件夹、
    //     没被重排，看起来也正常 —— 于是症状表现为「只有照片/视频目录里排序失效」。
    //     修复：gridItems 直接沿用 files 的顺序，只滤掉 OTHER（字幕 / nfo 等）。
    // 67：网络浏览加「路径导航」——多层级目录的快速出口（用户需求）：
    //     ① 顶栏「回到首页」图标：进到文件夹里之后一键回首页（MainScreen 的网络模块首页），
    //        不必连按好几次「上一级」；在连接根目录不显示（那一层没几级可退）。
    //     ② 点顶栏标题 → 「跳转到」底部弹窗：列出 根目录 → 当前目录 的**全部层级**，
    //        点任意一层直达（当前层打勾，点了只关窗）。根目录不接管点击、不显示下拉箭头。
    //     范式参考 AlistClientN 的路径导航弹窗（场景同上），并修掉它几处边界问题：
    //      · 列表顺序按 root→当前自然排（它是 reversed().skip(1)，最近的上级却排在最前）；
    //      · 保留「当前层」并打勾（它 skip 掉当前层，看不出自己站哪）；
    //      · 目标层不在栈里时**只 push、绝不先弹栈**（它的 _navigateToPath 会先 Get.until
    //        把栈弹空再判断，路径没匹配上就把整条导航栈销毁了）；
    //      · 统一 trim('/') 归一化路径，避免尾斜杠拼出 `/a/b/` 匹配不上。
    // 68：网络浏览顶栏两处按用户反馈收一收：
    //     ① 顶栏标题**小两档**（24sp → 20sp，`BrowserTopBar.compactTitle`；lineHeight 一起
    //        从 32sp 收到 28sp）。标题常年是长文件夹名、右侧又挂着几个图标，24sp 太挤。
    //        只作用于网络浏览这一页，Emby 库 / 本地文件夹等其他页面维持 24sp 不变。
    //     ② 「回到首页」从**顶栏图标收进「更多」菜单**（顶栏常驻图标回到 更多 / 搜索 / 设置
    //        三个，横向空间全留给标题）。语义不变：`while (size > 1) removeLastOrNull()`。
    // 69：网络模块卡片重设计（用户反馈「卡片太多太大太丑」）+ 置顶能力：
    //     ① 连接卡由「全宽 + 4~5 段堆叠、单卡近 200dp」改为**单行紧凑卡（约 60dp）**：
    //        左侧协议徽标（40dp 圆角块 + SMB/FTP/DAV/AL 字母代号 + 主题色阶着色）、
    //        中间「名称 / 协议•host:port」两行、右侧状态点 + ⋮ 溢出菜单；
    //        起始路径 / 账号 / 匿名等细节全部收进「编辑」表单，不再占列表空间。
    //     ② **点击整张卡 = 主操作**（已连接→浏览，未连接→连接，连接中→无动作）——
    //        这是卡片能压到 60dp 的关键：唯一高频动作变成「点整张卡」，
    //        而不是塞一个撑高卡片的大按钮。次级操作（浏览 / 断开 / 启动时自动连接 /
    //        编辑 / 删除 / 置顶）全部收进 ⋮ 菜单。
    //     ③ **置顶 / 取消置顶**：NetworkConnection 加 `pinned` 列（DB v17 + MIGRATION_16_17，
    //        `ALTER TABLE ... ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0`），
    //        DAO 改为 `ORDER BY pinned DESC, id ASC`；置顶卡换 secondaryContainer 底色 +
    //        状态点左侧图钉。置顶走专用 `updatePinned`（只翻一列），**不复用 updateConnection** ——
    //        后者会重写密码密文并断开活跃连接，置顶不该把用户正在浏览的会话踢掉。
    //     ④ 「收藏的目录」按同一套设计语言重做：通用文件夹图标 → 所属连接的协议徽标
    //        （与连接卡视觉串联）、卡片 148→156dp、删除钮降级为右上角淡 ✕（低频 + 破坏性，
    //        不与「点开目录」抢注意力）、连接已删除时整卡去色 + 副标题说明。
    //     ⑤ 网络页三个段落标题（直链 / 收藏的目录 / 局域网）统一走 SectionHeader 组件，
    //        不再三处各写一份 titleLarge+Bold+primary 的样式。
    //     ⑥ 【Emby】文件夹宫格封面「有的四宫格、有的一整张缩略图」修复。
    //        根因：宫格判定里有一条 `!hasBorrowedPrimaryImage()` 豁免 —— 服务端会给
    //        **视频文件夹**也「借」一张图（PrimaryImageItemId 指向它内部第一条媒体），
    //        被借图的文件夹于是整条跳过拼图、直接显示那**一张**（就是它第一个视频的一帧）。
    //        更关键的是这条豁免本意是给**相册**省子项查询，而相册根本不在 FOLDER_TYPES 里、
    //        走不到该判定 ⇒ 它从未按设计意图生效，只是在误伤视频文件夹。
    //        修复：删掉该豁免，并把两处重复的内联判定抽成 `needsFolderMosaic()` 单一实现
    //        （批量预取与卡片必须逐字一致，否则会出现半套行为）。
    // 70（修复轮，versionName 仍为 1.5.5）：照片浏览器信息呈现两处打磨（用户反馈）：
    //     ① 【顶栏标题】长标题被**硬切**成半截（如「exxx xxx [1728P/」）—— 根因是
    //        `Text` 默认 `overflow = Clip`，两处照片查看器（网络版 / Emby 版）都漏了
    //        显式 `TextOverflow.Ellipsis`（BrowserTopBar 一直是给全的）。补上 Ellipsis
    //        （截断处显示「…」）+ 标题右侧留 8dp，与「3 / 120」计数器不再贴在一起。
    //     ② 【底部信息栏】原名 + 大小 + 序号挤一行、文件名无右间距 ⇒ 文件名尾部数字与
    //        大小连读（`...kobayashi1027` 接 `719 KB` 被读成「1027719」）。改**两行**：
    //        文件名独占一行（补 Ellipsis），第二行「大小 · 日期」+ 序号靠右。二者物理
    //        隔开，误读消失；悬浮控件让位高度 CONTROLS_BOTTOM 64→84dp。
    // 71（修复轮，versionName 仍为 1.5.5）：三处交互补齐（用户反馈）：
    //     ① 【全局卡片按压反馈】所有卡片单击时给一点「按下去」的轻微缩放 ——
    //        新增 `ui/utils/PressFeedback.kt` 里的 `pressDownScale()` 自包含修饰符
    //        （pointerInput 记按下 → Animatable 缩放 → graphicsLayer 只重绘不重组；
    //        超过 touchSlop 即视为滑动、立刻弹回，避免整列卡片随滚动一起缩）。
    //        挂在各类卡片修饰符链**链尾**：缩放不参与布局、也不影响长按菜单要用的
    //        detectTapGestures 本地坐标。覆盖本地/网络/Emby 全部卡片。
    //     ② 【继续观看剩余时间角标】Emby 媒体库「继续播放」分类的卡片左下角给「剩余
    //        xx分xx秒」。进度与文案抽成单一来源 `EmbyItemProgress.kt`（首页与媒体库页
    //        共用，替掉两处重复实现），复用卡片既有的左下角角标（演员条目仍显示「作品数」）。
    //     ③ 【顶栏 Emby 图标长按 → 媒体库显示 / 隐藏】长按顶栏 Emby 图标弹出小菜单：
    //        列出全部媒体库，每行左侧复选框（勾=首页显示 / 不勾=隐藏）+ 「全部勾选」
    //        「全部取消」。菜单**复用长按媒体库卡片那套 DropdownMenu 版式**（同一个
    //        `EmbyItemActionsDialog` 文件、同一骨架），保证两个长按菜单是一套观感。
    //        隐藏是纯显示层过滤（按服务器存 `emby_hidden_libraries_$serverId`），
    //        库仍在服务器上、「调整顺序号」弹窗里照旧可见可排。单击图标语义不变（进维护）。
    // 72（修复轮，versionName 仍为 1.5.5）：v71 五项真机反馈返工：
    //     ① 【角标范围】「剩余 xx分xx秒」只在「继续播放」分类里显示（v71 是全部条目都显示，
    //        用户反馈在「全部 / 合集 / 收藏」里是噪音）。`EmbyLibraryMediaCell` 新增
    //        `showRemaining` 入参，两个调用点都传 `category == EmbyCategory.RESUMABLE`；
    //        演员的「N 部」角标不受影响。
    //     ② 【菜单定位】长按顶栏 Emby 图标的「媒体库显示」菜单原来被**钳在屏幕最右侧**
    //        （M3 DropdownMenu 在 LTR 下把菜单左边缘对齐锚点左边缘、向右展开，此处右边放不下
    //        ⇒ 被钳到 `窗口宽 − 菜单宽`），与手指长按的图标脱开。改：固定菜单宽 240dp +
    //        负的水平 offset（48 − 240dp），把菜单**右边缘**对到图标右边缘、向左展开。
    //     ③ 【按压幅度】`pressDownScale()` 默认 0.96 → **0.94**（用户反馈「有点轻」）。
    //     ④ 【详情页标签】`EmbyItem` 新增 `TagItems`（+ `EmbyNameIdPair` 类型）：部分服务端
    //        （用户环境）只写 `TagItems` 而 `Tags` 为空/不返回 ⇒ 原来只读 `Tags` 一条都显示不出来。
    //        详情页改「优先 TagItems、为空退回 Tags」。`TagItems` **不**加进请求 Fields
    //        （不在官方 ItemFields 白名单里，怕 400；单条目查询默认就带）。
    //     ⑤ 【详情页外部链接 + 宣传语/导演】新增两行：
    //        「外部链接」= ProviderIds 每项按 `服务器地址 + ?redirect=<值>` 拼 URL，
    //        点 chip 交系统浏览器（如 MetaTube）；「宣传语」= Taglines 原文一行，
    //        其下紧跟一行「导演：<名字>」（People 里 Type=Director）。getItem 的 Fields
    //        补上 `Taglines` / `ProviderIds`（两个都是合法值）。
    // 73（修复轮，versionName 仍为 1.5.5）：v72 两处返工（真机反馈）：
    //     ① 【媒体库显示菜单回到「吸在屏幕右侧」】v72 把用户第 2 条反馈（「长按的媒体库
    //        显示的弹窗吸在最右侧」）**误读成了 bug**，给菜单加了个负的水平 offset 想把
    //        它挪到「右边缘贴图标右边缘」⇒ 菜单飘到顶栏中段，用户打回
    //        （「我不是让你把小菜单吸在屏幕右侧吗」）。现在删掉那个 offset，回到 M3 的
    //        自然行为：锚点（顶栏 Emby 图标）右边只剩约 96dp、放不下固定的 240dp 菜单
    //        ⇒ 必然被钳到窗口最右侧 = 用户要的「吸在屏幕右侧」。KDoc 里写死了
    //        「别再当成 bug 修」和它的两个前提（右边空间 < 菜单宽、宽度必须固定）。
    //     ② 【详情页外部链接跳错页（跳到 Emby 首页）】v72 只按
    //        `"$baseUrl?redirect=$value"` 现拼（少了根路径的 `/`，参数还是裸 Id），
    //        真机跳到了 Emby 首页，而 Yamby 跳的是
    //        `http://192.168.2.124:8080/?redirect=JavBus:ACZD-218`。改：
    //        a. **首选服务端 `ExternalUrls`**（`EmbyItem` 新增该字段 + 新类型
    //           `EmbyExternalUrl(Name/Url)`）：`Url` 是插件拼好的完整地址，直接用就与
    //           Emby Web / Yamby 一致。getItem 的 Fields 加上 `ExternalUrls`，
    //           **并在失败时退回不带它的清单重试**（它不在官方 Fields 描述清单里，
    //           万一某版服务端 400，不能把整个详情页带下水）；
    //        b. 退回分支改拼 `<服务器根>/?redirect=<Provider>:<Id>`（补 `/` + 补
    //           `Provider:` 前缀；值本身已带前缀的不重复补），抽成 `externalLinkUrl()`。
    // 74（修复轮，versionName 仍为 1.5.5）：详情页「滚动就重复刷新推荐」的根因修复：",
    //     ① 现象：详情页从上滑到底再滑回来，两块推荐（「推荐」按演员/导演、「同类型」）",
    //        都会重新发网络请求刷新。用户预期只有「重新进详情页」或「下拉刷新」才刷。",
    //     ② 根因：两个推荐区是 `DetailBody` 里 LazyColumn 的 `item {}`。LazyColumn 对",
    //        **滚出视口的 item 会销毁其组合**、滚回来时**重新组合** —— 请求原先挂在推荐区",
    //        内部的 `LaunchedEffect` 上，键（item.Id / server）虽然没变，但**组合重建本身**",
    //        就会让 LaunchedEffect 重跑 ⇒ 每次滚回来都重新请求一轮。",
    //     ③ 改法（数据提升 / state hoist）：把两块推荐的**状态 + 加载逻辑**从推荐区内部",
    //        提到 `EmbyDetailScreen` 这一层（随页面存活、不随列表滚动被回收），",
    //        `RecommendationSection` / `GenreRecommendationSection` 退化成**纯渲染**",
    //        （数据由参数传入，自己不再发请求）。加载只由两个键决定：",
    //        a. `item.Id` 变化 ⇒ 重新进 / 切到另一部片的详情页；",
    //        b. `recommendReloadKey` +1 ⇒ 下拉刷新（`PullRefreshBox` 的 onRefresh 里",
    //           `load()` 之后跟上 `recommendReloadKey++`，于是下拉也会刷新推荐）。",
    //        滚动不在键里 ⇒ 怎么滚都不会再触发。两块推荐并发取（`coroutineScope`），",
    //        并把「随机取样 / 随机挑类型」保留在每次重跑时执行，语义仍是「每次进页面或",
    //        下拉刷新都换一批」。原先内联在 `GenreRecommendationSection` 里的同类型查询",
    //        抽成 `loadGenreRecommendations()`；`DetailBody` 不再需要 `viewModel` 参数。",
    // 75（修复轮，versionName 仍为 1.5.5）：详情页「点进下一层再返回、推荐被换掉」的补齐：",
    //     ① 现象：在详情页点推荐卡（或演职员头像 / 类型 chip）进下一层，返回时两块推荐会",
    //        重新取一批。根因：navigation3 的 NavDisplay 只组合当前 entry，被压住的 entry",
    //        其**组合会被销毁**，返回时重新组合 ⇒ 详情页里那些 `remember` 状态（v74 刚把",
    //        推荐数据提到这一层）也一起从零开始，于是重新请求 + 重新随机。",
    //     ② 但**不能无脑缓存**：既定语义是「重新进详情页要换一批」（推荐取样与同类型",
    //        的类型选择都是刻意随机的）。所以必须区分「返回」与「重新进入」—— 两者在",
    //        组合层面长得一模一样，于是**由压栈方留标记**：新增 `EmbyRecommendCache`",
    //        （进程内、LRU 封顶 24 条、`hasChild` 标记带 10 分钟有效期兜边角）。",
    //        a. 详情页压栈子页面之前 → `markChildPushed()`（新增本地 `pushChild()` 统一",
    //           三个压栈点：演职员 / 类型 chip / 推荐卡，别再直接 `backStack.add`）；",
    //        b. 返回后重新组合 → effect 先 `takeForRestore()`，命中就复用并**消费掉标记**；",
    //        c. 从列表 / 首页重新进 → 无标记 ⇒ 正常加载，「换一批」照旧；",
    //        d. 下拉刷新 ⇒ `recommendReloadKey++` 走正常加载并**回写缓存**（含「空结果」，",
    //           否则查不到推荐的条目返回时还会再请求一轮）。",
    //     ③ 【网络模块三条真机反馈】",
    //        A. 局域网连接卡「错误被一行省略截断」：错误不再挤在主行里当第三行文字",
    //           （紧凑行是 1 行省略版式，而失败原文动辄几十上百字符，等于把排错线索丢掉）",
    //           ⇒ 卡片改成 Column = 主行 + 可选「错误块」：满宽、`errorContainer` 底、",
    //           ⚠ 图标、最多 2 行，整块可点开 `ConnectionErrorDialog` 看完整原文并可复制；",
    //           正常（无错）卡片高度不变。",
    //        B. 「添加连接」FAB「只有滚到顶部才显示」：根因是 `LazyListState()` 每重组新建",
    //           （「上次滚到哪」恒为初始值 ⇒ 显隐判据失效），且显隐逻辑把赋值写在",
    //           `derivedStateOf` 里（反模式）⇒ 改用 `rememberLazyListState()` 并把显隐整段",
    //           删掉、FAB 常驻；列表底部补 `FAB_RESERVED_HEIGHT`(88dp) 免被 FAB 压住。",
    //        C. 连接表单「长 + 输账号密码时抖动」：`AlertDialog` 内容垂直居中，键盘弹出压缩",
    //           窗口 ⇒ 居中位置逐帧变化 + 内部 `verticalScroll` 可变高度 = 整张表单抖；",
    //           且 `widthIn(min = 400.dp)` 会把小屏撑出屏幕。⇒ 新增/编辑连接表单改为",
    //           **底部抽屉**（`ModalBottomSheet` + `skipPartiallyExpanded = true`），",
    //           与 `PlayLinkSheet` 同一套范式；两表单字段权重也统一回 0.50/0.50。",
    // 76（修复轮，versionName 仍为 1.5.5）：三处收口：
    //     ① 「合成随机封面图」支持配置**重新合成策略**：新增 `FolderCoverRefreshPolicy`
    //        （每次进入都重新合成 / 满 X 小时 / 满 X 天）+ 两个 X + 落盘的
    //        `emby_folder_cover_last_synth_at`。判定抽成纯函数 `shouldResynthesizeCover()`
    //        —— `Always`、从没合成过、**时钟回拨**三种情况一律判「要换」（不挡时钟回拨的话
    //        差值恒为负、阈值永远达不到，封面会永久冻结在旧图上）。触发点两个：
    //        a. 进库时判一次（超期 ⇒ `bumpGeneration()` + 记时间戳；只换代不清缓存，
    //           旧图继续显示、新图回来同键覆盖）；
    //        b. 下拉刷新**无条件**换代（手动意图优先），并**同时记时间戳** —— 只换代不记
    //           时间的话，退出再进来会被「满 X 小时」判成超期，刚刷完又白换一批。
    //        ⚠️ 时间戳必须落盘且用**墙钟**：`elapsedRealtime` 开机清零，跨重启会把
    //        差值判成负数 / 极小值，两种都错。关掉随机合成时策略整体跳过（固定取样结果确定）。
    //     ② 网络模块「局域网」→「网络位置」：用户完全可能在**公网服务器**上跑 SMB /
    //        WebDAV / AList，写死「局域网」既不准确也劝退（`i18n_local_network` →
    //        `i18n_network_locations`，zh/en 两处同步）。
    //     ③ 网络文件夹封面「加载失败后点重载没反应」的两个根因：
    //        a. `ensureFolderCover` 用 `listFiles(...).getOrNull()` 把**失败吞成 null**，
    //           再 `putFolderCover(..., null)` 落盘 ⇒ 「这次没列成」被记成「这个文件夹
    //           没有图片」，整个 30 天 TTL 内都不再加载（而用户点进去明明有图）。
    //           改为区分 Result：失败**不写**负缓存、不写本页 null，自动重试一次
    //           （单次 2 试 × 本页 2 轮，计数封顶 —— 目录真被删时来回滑不会一直发请求）。
    //        b. 缩略图重试只调了 `painter.restart()`（重走同一个请求），而失败常常已在
    //           磁盘缓存里留下半截数据 / 签名已过期 ⇒ 每次都撞同一堵墙。
    //           新增 `NetworkPhotoImageLoader.evict()`：重试先清掉这一张的缓存再请求。
    //           c. 附带：失败后自动重试一次（600ms），且整个错误区域都可点重试
    //           （原来只有右下角 32dp 的小图标，很容易点偏）。
    // 77（崩溃 + 回退修复，versionName 仍为 1.5.5）：两件事：
    //     〔A〕修「网络模块挂载 AList 服务器，进图片文件夹直接崩溃」。
    //     堆栈是 `java.lang.IllegalStateException: directory == null`，
    //     抛自 `coil3.disk.DiskCache$Builder.build()`。
    //     根因：两处自建 ImageLoader 都写成
    //       `DiskCache.Builder().maxSizeBytes(DISK_LIMIT_BYTES).build()`
    //     —— **漏了 `.directory(...)`**。已 javap 核验 coil-core 3.6.3：`Builder.<init>()`
    //     只给 fileSystem / maxSizePercent / minimumMaxSizeBytes / maxSizeBytes 赋默认值，
    //     **directory 从头到尾没被赋值**；而 `build()` 第一行就是
    //     `checkNotNull(directory) { "directory == null" }` ⇒ 只要不给目录，必抛。
    //     （Coil 自己 `singletonDiskCache` 里是显式传
    //      `SYSTEM_TEMPORARY_DIRECTORY.resolve("coil3_disk_cache")` 的，我们抄漏了这一句。）
    //     ⭐ 为什么 v76 之前「只是加载不出来」、v76 之后「直接崩」：
    //        ImageLoader 是 `lazy` 单例，异常发生在**图片请求协程**里 —— v75 及以前这条路上
    //        没有人主动去碰 ImageLoader，异常被 Coil 自己兜成 `ErrorResult`，
    //        表现为「封面加载失败」（就是 ③ 里那个「点了没反应」的真凶之一）；
    //        v76 新增的 `evict()` 在**普通协程**里调 `get()`，不在 Coil 的 try/catch 之内，
    //        同一个异常就冒到崩溃上来了。
    //     修法（两处一起）：补 `.directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(...))`，
    //     网络照片用 `network_photo_cache`、Emby 照片用 `emby_photo_cache`（后者是**潜伏**
    //     的同一个雷，Emby 照片缩略图重载一旦走到 evict 也会崩，先一起拆掉）。
    //     外加一道保险：`evict()` 整段包 `runCatching` —— 清缓存是纯 UI 触发的旁路，
    //     失败最多「这次没清干净」，不该让界面崩。
    //     ⚠️ 教训：第三方 Builder 的「默认值」不能靠猜。`maxSizeBytes` 没给时 Coil 会自动
    //        算百分比，**不代表** `directory` 也有兜底 —— 用前 javap 看 `<init>` 才知道。
    //     〔B〕修「进一个已经合成好四宫图的文件夹，滑一下 → 返回上一级 → 再进来，
    //         有些文件夹又重新生成了，但不是全部、还挺随机」—— 两个独立成因：
    //        B1（主因，v76 引入的回退）：`FolderCoverRefreshPolicy` 的默认值写成了
    //           `Always`，而 v76 之前**只有下拉刷新**会换代；`Always` 让「每一次进入
    //           任意一层」都整屏重新取样 ⇒ 四宫格当场洗牌换脸。
    //           ⇒ 新增 `Manual`（「仅下拉刷新时重新合成」）并**改为默认**，回到 v76 之前
    //             的行为；`Always` 仍保留成可选项（想要每次都新鲜的用户自己选）。
    //           （看着像「只有一部分在变」，是因为子项 ≤4 个的文件夹怎么抽都是同一批图，
    //             子项多的那些每次都不一样 —— 于是显得随机。这条判断当时写错了，
    //             KDoc 里曾声称 `Always` 「与旧版本行为一致」，实为不一致，已更正。）
    //        B2（一直存在）：批量取封面那个 effect 的 key 里带着 `mosaicFolderIds`
    //           （这一屏所有需要宫格的文件夹 Id 拼成的串），而无限加载会不断往里加新的
    //           ⇒ 签名一变，effect 就被**取消重跑**，连已经拼好的那批一起重新取样。
    //           ⇒ 改成**只取这一代里还没定下来的**（新增 `EmbyFolderCoverCache.isResolved`）：
    //             翻页 / 返回 / 重组都不再动已经合成好的封面；换代（下拉刷新 / Always）
    //             时代数变化 ⇒ pending 仍是全部，换脸行为不变。
    //        B3（顺手）：批量 effect 的 `finally` 原来无条件写 `batchCoveredSignature`。
    //           签名变化会先取消老协程再起新的，老协程的 finally 可能排在新协程**之后**，
    //           写进去的是**旧签名** ⇒ `folderCoverBatchReady` 恒 false，所有卡片永远卡在
    //           「等批量」，整屏封面不加载。⇒ 加 `isActive` 守卫，被取消的协程不写。
    //        B4（顺手）：宫格封面缓存上限 200 → 800（一条只是 4 个地址串，开销可忽略）。
    //           定小了会被 LRU 淘汰，淘汰掉的文件夹下次进来要重新取样，同样表现为
    //           「有些封面又重新生成了一遍」。
    // 78（手势交互修复，versionName 仍为 1.5.5）：网络浏览「封面没加载出来 → 文件夹点不进去 →
    //     就算进去了也滑不动」—— 用户报的是三个现象，实为**两个根因串成一条链**：
    //     ① **封面加载失败 ⇒ 整个文件夹点不进去**（v76 我自己引入的）：
    //        `NetworkPhotoThumbnail` 的错误态整块 `fillMaxSize().clickable(onClick = 重试)`。
    //        网格卡里封面是 1:1 大图、几乎占满卡面 ⇒ 这一块变成巨大的「重试」热区，
    //        把外层卡片的 onClick（进入文件夹）和 onLongClick（长按菜单）**全部吃掉**。
    //        ⚠️ 我当时还在注释里写「顺手挡掉外层卡片的点击，用户想重试不是想进去」——
    //        这个判断是错的：**封面加载失败和「能不能进这个文件夹」毫无关系**，
    //        用户点卡片绝大多数时候就是想进去，重试只是偶尔的次要意图。
    //        ⇒ 整块的 `clickable` 去掉（Compose 里没有 clickable 的 Box 不拦截手势，
    //          点击会正常冒泡到外层卡片），重试只保留右下角一个 48dp 的按钮
    //          （Material 无障碍最小触控尺寸，比 Emby 侧同款的 32dp 更好点）。
    //        （Emby 侧 `EmbyPhotoThumbnail` 本来就是「只给一个角上的圆钮、不占整块」，
    //          网络侧当初照抄时跑偏了 —— 这次对齐回正确写法。）
    //     ② **列表视图每次加载都滚回顶部、根本滑不动**（一直存在，列表视图特有）：
    //        `NetworkBrowserList` 里写的是 `val networkListState = LazyListState()`
    //        —— **不是** `rememberLazyListState()`。裸构造每次重组都 new 一个 state，
    //        滚动位置随之归零。放大它的正是封面懒加载：文件夹越多，`folderCovers`
    //        更新越频繁 ⇒ 重组越频繁 ⇒ 越滚不下去（滚回顶部又重新组合顶部卡片 →
    //        又触发封面加载 → 又重组 ……）。这正是用户说的「里面有很多图库文件夹」。
    //     ③ **「手势冲突」是 ② 的副作用，不是独立问题**：`PullRefreshBox` 的启用判据是
    //        `listState.firstVisibleItemIndex == 0 && scrollOffset == 0`，而一个刚 new
    //        出来的 state **永远满足**它 ⇒ 下拉刷新在任何滚动位置都 enabled ⇒
    //        在列表中段往下拉被判定成「下拉刷新」而不是滚动，两个手势直接打架。
    //        网格视图用的是 `rememberLazyGridState()`，所以只有列表视图有这毛病 ——
    //        与用户「我用列表视图进去的」完全吻合。
    //     ④ 顺手修同类隐患：`PlaylistScreen` 里同样的裸 `LazyListState()` / `LazyGridState()`
    //        （那行注释还写着「用 CompositionLocal 里共享的那个」，代码却自己在 new）。
    //        ⚠️ 全局只剩这两处裸构造，其余要么是 `remember { ... }` 要么是 `rememberXxxState()`。
    // 79（取流链路修复，versionName 仍为 1.5.5）：用户报「偶尔有几个没刷出封面图，
    //     然后**重载也刷不出来**，但正屏下拉刷新能刷出来」，并提醒「我是 alist 协议的，
    //     刷新应该要走 alist 的 api」。根因不在 UI、不在缓存，在 `AlistClient.getFileStream`
    //     的取值顺序上，是一整条链：
    //     ① **过期的签名会在第 ① 级无限复活**。`/d/?sign=` 的签名有时效，过期即 401；
    //        而调用方（Coil Fetcher）手里攥的是**列目录那一刻**的旧签名，它在第 ① 级
    //        优先级最高。`fs/get` 换来的新签名被 `rememberSign` 写进了 `signCache`，
    //        却永远排不上号 —— 于是每次取流都先撞一次旧签名，再走 `fs/get`。
    //     ② **`fs/get` 对同一个文件返回同一个结果**。如果它给的 `raw_url` 本身取不到
    //        （过期 / 指向服务端内网地址），那「重载」只不过把同一串字节再请求一遍：
    //        状态 Error → Error，界面就是「点了没反应」。
    //     ③ **以前只试 `raw_url`，漏了新签名那条**。`fs/get` 同时给 `raw_url` 和 `sign`，
    //        旧代码写成 `raw_url ?: directUrl(sign)` —— `raw_url` 非空就**永远不会**
    //        去试 `/d/?sign=<新签名>`，raw_url 一坏整条链就断死，重载也救不回来。
    //     三条合起来正好解释全部现象：「偶尔有几个」= 那几个的签名过期了；
    //     「重载刷不出来」= ①②；「下拉刷新能刷出来」= 重新 `fs/list` 拿到全新签名，
    //     第 ① 级直接成功。**差别不在缓存，在签名新不新。**
    //     修法（两半，缺一不可，均落在取流层、对所有调用方生效）：
    //       A. 新增 `failedSigns`（path → 已证伪的签名）：失败过的签名记下来，
    //          下一次**直接跳过**，不再复活；`rememberSign` 收到新签名 /
    //          `fs/list` 刷新 / `disconnect` 时作废该记录 —— 链条因此是**自愈**的：
    //          失败 → 跳过旧签名 → fs/get 换新签名 → 成功 → 记录作废 → 回到零额外请求的快路。
    //       B. 第 ② 级把 `raw_url` 和 `/d/?sign=<新签名>` **都试一遍**（buildList + distinct），
    //          不再让 `raw_url` 一票否决。
    //     ⚠️ 顺手记录一条 Coil 3.6.3 的核验结论（javap，别再踩）：
    //        `ImageRequest` 是 final class 且**重写了 equals**（25 个构造参数逐个比），
    //        字段相同的新实例与旧实例**相等**；`AsyncImagePainter$Input.equals` 又走
    //        `modelEqualityDelegate.equals(request, other)` ⇒ 想靠「换一个全新的
    //        ImageRequest」来强制重发是**无效功**（v79 试过，`set_input` 判等通过、不 restart）。
    //        强制重发只能用 `painter.restart()`（已核验：`restart()` → `launchJob()` →
    //        `imageLoader.execute(input.request)`，唯一守卫是 `isRemembered`）。
    //        同理，「下拉刷新之所以有效」正是因为 `fs/list` 给了**新 sign** ——
    //        `NetworkPhotoRequest` 是 data class，sign 一变 request 才真的不等。
    //        这两个事实都写进了 `NetworkPhotoThumbnail` 的注释，防止后来又去改 UI。
    // 80（自然排序，versionName 仍为 1.5.5）：用户报「网络模块里挂载的网络位置排序不生效，
    //     比如按名称排序是 e1018 e1019 e102 e1020 e1021，希望自然排序」。
    //     **根因**：`NetworkBrowserViewModel.networkFileComparator` 的名称键写的是
    //     `compareBy { it.name.lowercase() }` —— 纯字典序把数字段**按字符**比
    //     （'1' 后的 '0' < '2'）⇒ `e1018 < e1019 < e102 < e1020 < e1021`。
    //     这类 `e0xx` / `Ep 2 vs Ep 10` 命名在媒体库极常见，而**本地文件夹、本地视频、
    //     文件选择器三处早就用 `SortUtils.NaturalOrderComparator`**（`utils/sort/SortUtils.kt`，
    //     连续数字段按数值比），网络模块当初漏接了这一层。
    //     改动（全部对齐既有 DEFAULT 实例，不新造轮子）：
    //       ① `networkFileComparator` 的名称键 + 同键名称兜底都改自然序
    //          （兜底不跟着改的话，同日期/同大小的项之间仍会退回字典序，自相矛盾）；
    //       ② `_rawFiles` 的基准序同样改自然序（最终顺序由比较器决定，但基准序是兜底链）；
    //       ③ 文件夹封面的「第一张图片」从 `minByOrNull { it.name.lowercase() }` 改成
    //          `minWithOrNull(NaturalOrderComparator)` —— 否则 `img9/img10` 这种命名下
    //          封面会取到第 10 张，而网格里排第一的是第 9 张，封面与列表对不上。
    //     ⚠️ 网格视图无需改：v66b 起 `gridItems` 已直接沿用 `files` 的顺序（不再自己排一遍），
    //        改比较器即同时生效于列表与网格。
    // 81（顶栏路径名修复，versionName 仍为 1.5.5）：用户报「挂载了个 alist 服务器叫 ABC，
    //     访问 ABC 顶栏显示 ABC 没错；进 ABC 里的 volume1 挂载路径，顶栏**还是显示 ABC**
    //     （应显示 volume1）；再从 volume1 进『存储』就正常了」。
    //     **根因**（Kotlin 标准库语义坑，不在网络模块逻辑里）：`substringAfterLast` 的签名是
    //     `substringAfterLast(delimiter, missingDelimiterValue = this)` —— 第二个参数是
    //     **「整个串里找不到分隔符时的返回值」**，默认 `this`。旧代码显式传了 `""`：
    //       currentPath.trimEnd('/').substringAfterLast('/', "").ifBlank { connectionName }
    //     单段路径（`volume1`，整串没有 '/'）于是拿到 `""`，被 `.ifBlank` 退化成连接名；
    //     而多段路径（`volume1/存储`）能取到最后一段所以正常；根目录 trim 完本就是空串、
    //     本来就该退化 ⇒ **这个 bug 只在「第一层子路径」露出来**，极易漏。
    //     改动：新增 `NetworkPathDisplayName.kt` 的 `networkPathDisplayName(path, fallback)`
    //     （`trim('/')` + `substringAfterLast('/')` 不传第二参），顶栏标题
    //     （`NetworkBrowserScreen.dirTitle`）与收藏目录名（`NetworkBrowserViewModel
    //     .toggleFavoriteCurrent`）两处共用这一份口径。
    // 82（侧边滚动条进 Emby + 全局开关，versionName 仍为 1.5.5）：两件事（用户反馈）：
    //     ①「网络模块那个能拖动调位置的侧边导航条很好，Emby 媒体库也搞上去」——
    //        把 nanihadesuka 滚动条移植进 `EmbyLibraryScreen`：三个视图模式（演员网格 /
    //        紧凑列表 / 网格+年份 stickyHeader 时间轴）共用同一个 gridState，各自套壳。
    //     ②「加个开关，放外观里，app 内所有用这滚动条的地方都听它的」——
    //        新增 `ui/utils/SideScrollbar.kt` 统一外壳（LazyGridSideScrollbar /
    //        LazyListSideScrollbar），读 `BrowserPreferences.sideScrollbarEnabled`；
    //        开关落在「设置 → 外观 → 文件浏览 → 侧边滚动条」；同时把「外观定制」入口
    //        从设置主页挪进外观页（用户明确要求）。
    //     ⚠️ 关掉开关时外壳是**不套壳**直接渲染内容，不是把 thumb 透明度归零 ——
    //        透明 thumb 仍占触摸拖动区，会留下摸得到却看不见的死区。
    //     ⚠️ 外壳里两个出现判据（isAtTop / hasEnoughItems）必须走 `derivedStateOf`：
    //        裸读 firstVisibleItemIndex 会让每帧滚动都重组（原网络模块就是这么写的）。
    //     ⚠️ javap 核验：`LazyVerticalGridScrollbar` 的 content 是普通
    //        `@Composable () -> Unit`（无 BoxScope / LazyGridScope 接收者）。
    // 83（「媒体库显示」菜单吸右侧 + 服务器维护页美化，versionName 仍为 1.5.5）：
    //     ① 用户报「长按首页 Emby 图标的『媒体库显示』菜单，我之前让你把它吸在屏幕
    //        右侧，现在还是弹在手指触摸的位置」。
    //        **根因**（material3 1.5.0-alpha15 定位算法，javap 实证，类在
    //        `androidx.compose.material3.internal.DropdownMenuPositionProvider`，
    //        编译自 MenuPosition.kt）：横向有三个候选**按序试、第一个「放得下」的胜出**
    //        （放得下 = `x ≥ 8dp && x + 菜单宽 ≤ 窗口宽 − 8dp`），顺序是
    //        `[startToAnchorStart, endToAnchorEnd, 中心判定(left/rightToWindow)]`，
    //        全放不下才用最后一个。v73 起删掉 offset「靠 M3 钳到窗口最右」的前提
    //        「240dp 在锚点右侧放不下」**是错的**：`MenuHorizontalMargin` 实证只有
    //        **8dp**（不是想当然的 48dp；48dp 是垂直边距），锚点右侧约 96dp 里放
    //        240dp 菜单完全放得下 ⇒ `endToAnchorEnd`（右边缘贴锚点右边缘）**永远胜出**
    //        ⇒ 菜单右边缘一直贴在图标（= 手指位置），从来没能吸到屏幕右侧。
    //        改动：`EmbyLibraryVisibilityMenu` 显式传 `offset.x = 屏宽`，候选 1、2
    //        必然被「放不下」否掉 ⇒ 确定性地落到 `rightToWindowRight`
    //        （右缘贴 `窗口宽 − 8dp`），不依赖锚点位置与屏宽；仅 LTR 下强制。
    //        ⚠️ 三不：不删这个 offset（删了退回 endToAnchorEnd）、不改负偏移
    //        （v72 老路，只会把菜单推到锚点左侧）、不动其他菜单（用户明确要求）。
    //     ②「美化一下 emby 服务器维护页的 UI」：`EmbyServerManageScreen` 三处 ——
    //        ServerCard 加 40dp 圆形 tonal 状态徽章 + 名字旁「当前」小胶囊 +
    //        普通卡 outlineVariant 细边 / 当前卡 primary 实边 + 三个动作按钮
    //        从信息行拆到独立的右下角一行（原来和名字挤一行，名字列被 144dp
    //        按钮区压剩一百多 dp）；顶栏标题加「共 N 台」副标题；登录失败信息
    //        从一行红字改成满宽 errorContainer 块（与网络连接卡片同一约定）。
    // 84（网络位置卡片「启动应用时自动连接」菜单项左侧空图标，versionName 仍为 1.5.5）：
    //     用户报「网络模块 网络位置的卡片 启动应用时自动连接如果没启动 他左边是
    //     没图标的 你看看怎么优化下 现在只有开启了会显示个勾」。
    //     定位：autoConnect 的唯一开关/展示入口就是卡片 ⋮ 菜单里的这一项
    //     （新增/编辑弹窗均无该控件），旧实现是「开态 ✓ / 关态 24dp 空白占位」
    //     —— 同一列其他项（置顶/连接/编辑/删除）全有图标，唯独它时有时无，
    //     看着像没画完。改法（`NetworkConnectionCard.kt`）：leadingIcon **恒显示**
    //     `Icons.Filled.Bolt`（「自动」语义；用 Bolt 不用 PowerSettingsNew 是为了
    //     和菜单里 Link/LinkOff 的连接语义图标拉开形状差距），开关态交给颜色 ——
    //     开 = primary（与卡片「已连接」状态点同色，App 内"激活"色），
    //     关 = onSurfaceVariant（与 ⋮ 同色，"待机"灰）。
    //     ⚠️ 两不：不改回「关态留空」（空占位即修复对象）；不做成两种不同图标
    //     （同一项图标形状必须恒定、只变颜色，否则开关切换时图标会跳）。
    // 85（启动页动画 + UI 审计，versionName 仍为 1.5.5）：用户要「炫酷的启动动画」
    //     + 顺手排查可优化的 UI。启动页（`MainActivity.AppSplashScreen`）从静态
    //     logo 重写为品牌化「揭幕」三段式动画：① 进入——adaptive-icon 以弹性曲线
    //     弹入（scale 0.6→1 + alpha 0→1），背后 tertiary 光晕无限脉冲（radial
    //     gradient glow，无需 blur，规避 BOM 差异）；② 停留；③ 揭幕——整体放大
    //     1.2× 并淡出，露出背后已布局好的 Navigation3 主界面（覆盖层方案，不白屏）。
    //     名称错峰上移淡入。三段时间见 `MainActivity` 顶部 SPLASH_*_MS 常量
    //     （ENTER 850 / HOLD 650 / EXIT 420）。
    //     ⚠️ 红线：启动页是覆盖层、动画结束才 onFinished 卸掉自己；不要改成「替换
    //     内容」，否则会白屏一闪。颜色全部走 colorScheme（primary/tertiary/surface），
    //     不在播放器控制条外硬编码颜色。
    // 86（启动页修透 + 快捷 UI 打磨，versionName 仍为 1.5.5）：用户复盘 v85 启动页发现
    //     主界面从动画中央透出来——根因是根背景径向渐变的**中心停靠色半透明**（primary 0.14）
    //     ⇒ 整层不透明失效。改法（`MainActivity.AppSplashScreen`）：根 `Box` 背景改为**不透明**
    //     `surface`，品牌辉光另起一层 `primary.copy(0.18)→Transparent` 叠在 surface 之上
    //     （不透明底色 + 炫光层，既盖住主界面又保留炫酷 glow）。顺手两处 S 级快捷打磨：
    //     ① 搜索「无结果」从一行裸 `Text` 升级为图标 + 标题 + 提示的居中列（EmbyHomeScreen）；
    //     ② 「更多」面板 `QuickActionItem` / `PlaybackToggleRow` 加 `pressDownScale()` 按压反馈，
    //     与全局卡片手势一致（MoreSheet）。M/L 级（空态统一、下拉刷新指示器、「换一批」crossfade、
    //     主题圆形揭幕、列表→详情 hero 转场）留作下一轮，逐个验证不回退再发。
    // 87（卡片长按轻度震动反馈，versionName 升至 1.5.6）：用户要「卡片长按除了原来的事件外
    //     再加上轻度震动反馈」。落点：① 共享 modifier `tapAndLongPress`（EmbyMediaCard 私有 +
    //     NetworkMediaCard 内部，承载 Emby 媒体/文件夹卡 + 网络媒体/视频/文件夹卡）的 onLongPress
    //     里先 `performHapticFeedback(HapticFeedbackType.LongPress)` 再派发；② 直接走
    //     `combinedClickable` 的本地卡 FolderCard / VideoCard / M3UVideoCard 用新建的
    //     `longClickWithHaptic()`（`ui/utils/LongPressCopy.kt`）包一层同样的长按震动。
    //     与播放器 `hapticFeedbackEnabled` 开关无关——那是播放手势专属，浏览卡片长按反馈用户明确要、
    //     全局常开。PlaylistCard 透传到 FolderCard、故自动带上。
    // 88（网络浏览排序后回到顶部，修复轮 versionName 仍为 1.5.6）：用户要「改了排序之后视图
    //     自动停在第一个项目（顶端）」，原来排序只重排 items、滚动 state 保留在原位置，
    //     可能停在中段或底部。落点 NetworkBrowserScreen：内容区/网格/列表补 sortType+sortOrder，
    //     两个子组件各 `LaunchedEffect(sortType, sortOrder) { state.scrollToItem(0) }`。
    //     ⚠️ 键只放排序两项，别带 items（下拉刷新/封面懒加载会抢滚动位置）。
    // 89（Emby 文件夹卡片左下角文件夹图标，versionName 仍为 1.5.6）：进入文件夹后子文件夹
    //     与媒体文件视觉上无区别。EmbyMediaCard 新增 isFolder；左下角原角标重构为
    //     Row[文件夹图标 chip + 角标 chip]（图标永远最左 ⇒ 结构上不互遮），
    //     padding(bottom=8) 让开 3dp 进度条；图标不受 showBadges 控制（始终可见）。
    //     判定复用 isFolderLike(item)（Folder/CollectionFolder/UserView/BoxSet），
    //     与宫格封面/点击语义同口径；紧凑行 EmbyCompactRow 同步补 10dp 图标。
    // 90（纯子文件夹目录工具行 + 随机入口手势分工，versionName 仍为 1.5.6）：
    //     ① 【按钮消失】用户报「进入的文件夹只有子文件夹（没有直接媒体文件）时，随机播放 /
    //        随机播放收藏 / 视界流 / 筛选 / 视图 这几个按钮会消失，可随机播放本来就该支持
    //        下钻子目录」。根因是 `showVideoActions = pagePlayableCount > 0 || items.isEmpty()`：
    //        纯目录页 `pagePlayableCount = 0` 且列表非空 ⇒ 整组被收掉。补 `pageFolderCount > 0`
    //        —— 有子文件夹就说明「下钻随机」是有效入口（筛选 / 视图本来就与条目类型无关）。
    //     ② 【手势分工】三个随机入口改成 `ToolbarIconAction`（自铺 48dp 圆形触控区 +
    //        combinedClickable，尺寸对齐 M3 IconButton）：单击 = 下钻（老行为）、长按 = 只播
    //        当前目录。`loadRandomForView` / `startRandomPlayback` / `startFeedPlayback` 统一加
    //        `drillDown` 参数（传给 `recursive`）。⚠️ 提示必须弹在**下钻真正生效**的分支里，
    //        且文案里的手势名跟着设置项走，否则用户把分工调反后提示就成了错话。
    //     ③ 【Toast】下钻随机播放 / 视界流在进播放器（或竖屏流页）**之前**弹一句
    //        「<入口>：含子文件夹 · 长按只播当前目录」；只播当前目录且取不到内容时改弹
    //        「当前目录没有可直接播放的内容 · 单击可含子文件夹」，避免纯目录页上的死胡同。
    //     ④ 【设置项】新增 `embyRandomDrillDownOnTap`（默认 true）可把手势调反：
    //        设置 → 界面与外观 → 随机播放默认下钻。开关文案随状态给出当前分工。
    //     ⚠️ Kotlin 坑：字符串模板里 `$gesture只播…` 会被当成一个含中文的标识符 ⇒
    //        必须写 `${gesture}`（本次真实编译错误 Unresolved reference）。
    // 91（随机入口提示两种手势都给 + 提示可关，versionName 仍为 1.5.6）：用户反馈
    //     「现在只有非长按播放会有 toast，长按没有 toast」+「设置里加个开关 toast 可开关，
    //     反转后 toast 也要跟着反转」。落点：
    //     ① `showDrillDownHint` → `showGestureHint(label, drillDown)`，**两种手势都弹**，
    //        且**只说本次行为**：`"<入口>（含子文件夹）"` / `"<入口>（仅当前目录）"`。
    //        ⚠️ 不要在 toast 里提「另一半手势」：初版写成「… · 长按只播当前目录」，
    //        用户单击后看到「长按」二字，第一反应是「提示写反了／我是不是按错了」——
    //        那句提示只负责确认取片范围，不是教手势；手势说明留在设置项副标题里。
    //     ② 新增偏好 `embyRandomGestureToast`（默认 true）：「随机播放」卡里第三行
    //        「显示手势提示」。⚠️ 只关「动作范围说明」，**不关**「没有可播放内容」的失败提示
    //        —— 那是唯一的失败反馈，关掉就退化成「点了没反应」。
    // 92（照片浏览器底部缩略图扩到网络位置，versionName 仍为 1.5.6）：用户报
    //     「设置里的照片浏览器底部缩略图目前只应用于 emby 媒体库，请扩宽支持到网络模块」。
    //     ① `NetworkPhotoViewerScreen` 读同一个偏好 `photoFilmstrip`，新增
    //        `NetworkPhotoFilmstrip`（44dp 格 / 白框标当前 / 55% 淡化其余 / 当前页自动滚入可见），
    //        与 Emby 版同一套视觉语言；胶片条 + 信息栏放进同一个底部 `Column` 自下而上堆，
    //        不手算信息栏高度。
    //     ② `NetworkPhotoThumbnail` 新增 `showRetry`（默认 true）——胶片条传 **false**：
    //        44dp 格子里塞 48dp 重试钮会盖满整块，把「点一下跳到那张」吃掉。
    //     ③ 悬浮控件（翻页 / 幻灯片）在胶片条开着时抬到 `CONTROLS_BOTTOM_WITH_STRIP`(148dp)。
    //     ⚠️ 一个开关管两个照片浏览器，不按入口分两套偏好。
    // 93（两个照片浏览器合并成共享外壳，versionName 仍为 1.5.6）：用户提「Emby 与网络位置
    //     的照片浏览器是两个独立组件，很容易那边改了这边没改，能不能合并复用」。
    //     ① 新增 `ui/browser/photo/SharedPhotoViewer.kt`：**整套外壳只有一份** ——
    //        横向分页、沉浸模式（点画面 / 顶栏钮 / 返回键先退沉浸）、下拉关闭（未放大时
    //        跟手位移 + 缩小变暗）、幻灯片（计时协程不带 currentPage、淡出→换页→淡入、
    //        倒计时圆环、播完自停、keepScreenOn）、底部胶片条、上一张/下一张、顶栏、信息栏。
    //     ② 两个屏幕退化成**适配层**，差异全走插槽：`renderPage`（取图方式）、
    //        `renderThumbnail`（胶片条缩略图）、`topBarActions`（保存 / 旋转 / 更多）。
    //        数据模型不同（EmbyItem vs NetworkFile）→ 用最小载体 `PhotoViewerEntry`
    //        （key / title / meta）给外壳，外壳不碰任何协议细节。
    //     ③ 死代码清理：两边各自的 PhotoFilmstrip / SlideshowButton / PhotoNavButton /
    //        PhotoInfoBar 与相关常量、import 全部删掉（留在原地就是下一次分叉的种子）。
    //     ⚠️ 胶片条开关 `photoFilmstrip` 仍然只有一个，两个入口共用。
    //     ⚠️ 缩略图格 44dp 里不能塞重试钮（48dp 触控区会盖满整格、吃掉点击），网络版
    //        `NetworkPhotoThumbnail(showRetry = false)`。
    // 94（合并后的残留分叉收口，versionName 仍为 1.5.6）：用户看得很准 ——「网络位置的照片
    //     浏览器缩略图下方还会显示文件名等信息（底部信息栏）啊，emby 那边就没有；然后网络位置
    //     的照片浏览器就不能实现全屏、旋转、更多菜单了吗」。
    //     ⇒ 这不是两个独立 bug，是 v93 合并时把「哪边有、哪边没有」原样搬进了共享外壳：
    //     ① 信息栏不再有开关（删 `showInfoBar` 参数），**两个入口都显示**；Emby 侧补
    //        `PhotoViewerEntry.meta = 大小 · DateCreated(前 10 位)`，否则信息栏第二行空着。
    //     ② **全屏 / 旋转**从 Emby 适配层的 `topBarActions` 收进外壳：以前这两个按钮写在
    //        Emby 屏幕里，网络侧自然没有 —— 这是「谁先写的谁有」的典型结果。
    //        旋转角度改由 `PhotoRotationState`（按 key 存）统一持有，适配层的长按面板
    //        「旋转 90 度」与「另存为 JPEG」导出角度读**同一份**，不会出现「顶栏转过了导出还是正的」。
    //        新增 `formatPhotoFileSize` 到外壳共用（两边各写一份必然出现 1.2 MB / 1.20 MB 差异）。
    //     ③ 网络侧顶栏对齐 Emby：去掉单独的下载图标，改成**更多菜单**（图片信息 / 保存原图），
    //        `NetworkPhotoPageImage` 新增 `rotationDegrees`（`.rotate` 放在 `.zoomable` 之前，
    //        与 Emby 同序）。
    //     ⚠️ 沉浸态整条顶栏是淡出的 ⇒ 外壳**不画**「退出全屏」图标（永远看不见 = 死代码），
    //        退出靠点画面 / 返回键。
    //     ⚠️ 顶栏的「3 / 120」计数器**删掉**：信息栏右下角已有同一序号，重复只把标题挤窄
    //        （网络版顶栏 3 个钮 + 返回已占 192dp）；信息栏与顶栏同隐同现，没有死角。
    //     ⚠️ `photoControlsBottom` 只剩「有无胶片条」一个变量（信息栏恒有）：148dp / 84dp。
    // 95（UI 全局体检整改 + AList 大目录只加载一半，versionName 升至 1.5.7）：两批。
    //     〔A〕四项 UI 整改（用户点名的四条）：
    //     ① 【英文文案混进中文界面】约 70 条用户可见英文全部中文化：删除/重命名确认、
    //        刷新与复制 Toast、导入导出、权限说明长文（整段英译中）、播放器轨/字幕/截图
    //        提示、媒体信息页标题与错误、FileSystem 搜索态漏网的 "Searching..."。
    //     ② 【卡片圆角只生效一半】新增 `ui/theme/CardAppearance.kt` 的 `LocalCardCorner`
    //        （主题层与 `EmbyAppearance` 用**同一个** `embyCardCorner` 下发），本地/网络
    //        7 个卡片文件 11 处写死的 `RoundedCornerShape(12.dp)` 改读它 ⇒「卡片圆角」
    //        设置现在真的全 App 生效（此前只有 Emby 卡片跟着动）。
    //     ③ 【网络根页半成品】把被注释掉的搜索接回来：`NetworkSearchField` 从浏览页抽到
    //        `ui/browser/components/` 两页共用；根页按名称/地址/协议/用户名过滤连接，
    //        有关键词时收起直链与收藏段，无命中给「没有匹配的连接」提示，返回键先退搜索。
    //        顶栏那排写死的 `onXxx = null` 删掉（根页没有勾选态，本就不该传）。
    //     ④ 【骨架屏只有 Emby 有】新增 `ui/browser/states/BrowserSkeleton.kt`：复用
    //        EmbySkeleton 的扫光原语（同一份动画实例策略），圆角读 `LocalCardCorner`；
    //        网络浏览（网格 3 列 1:1 / 列表 16:9 行）、本地目录浏览、本地搜索、
    //        最近播放（按「网格 / 列表」设置与列数选版式）四处替换居中转圈。
    //     〔B〕AList 大目录只加载一部分（用户报「有些目录会只加载一部分」）：
    //     根因不在 UI —— AList 后端对 `per_page` 做归一化：`<= 0` ⇒ **默认 200 条**，
    //     `> 500` ⇒ 截断 500（官方 API 文档 `fs/list`）。所以流传已久的「per_page: 0
    //     一次拿全」实际只拿前 200 条，200+ 条的目录在界面上就是缺内容且无任何提示。
    //     ① CineIsle `AlistClient.listInternal`：改「每页 500 + 自动翻页」——判据优先级
    //        `has_more`(v3.58+) → `filtered_total`/`total` → 满页启发式；按文件名去重；
    //        50 页（25000 条）保护上限 + 本页无新增即停（防服务端忽略 page 死循环）；
    //        `listPage` 单页解析，签名仍逐页写入 `signCache`。
    //     ② AlistClientN（Flutter）：新增 `lib/net/alist_fs.dart` 的 `AlistFs.requestAll`
    //        （与 `requestNetwork` 同形，便于逐点替换；首页失败才回调 onError），12 处
    //        `per_page: 0` 的全量场景全部改走它：目录浏览 / 子目录预加载 / 空目录清理 /
    //        收藏与最近播放的缩略图 / 复制移动选目录 / 详情页「N 个项目」与递归体积 /
    //        整理器的目标目录重名缓存 / 歌词目录；目录级 TikTok 播放也一并拉全。
    // versionName 涨到 1.5.8：崩溃收口 + 网络照片内存缓存分桶 ——
    //     ① CrashActivity 的「重启」按钮不再直接拉起 MainActivity：用户换成非默认桌面图标后
    //        AppIconManager 会把 MainActivity 置为 DISABLED、只留 activity-alias 启用，
    //        旧写法必抛 ActivityNotFoundException，形成「崩 → 点重启 → 再崩」死循环。
    //        改用 getLaunchIntentForPackage（只会返回**当前启用**的 LAUNCHER 入口），
    //        并先确认拉起来再 finish，失败只 Toast 不再二次崩溃。
    //     ② MainActivity.onSaveInstanceState 加体积闸门：实测 saved state 冲到 2.2MB
    //        （Compose 两个 rememberSaveable 子项各约 1.1MB），stop 时 Binder 回传必抛
    //        TransactionTooLargeException。现按 Parcel 实测字节数判断，>512KB 才丢弃
    //        Compose 那一块，正常体积原样保留（旋转恢复不受影响）。
    //     ③ 网络照片内存缓存按**显示尺寸分桶**（thumb / film / full）：原先网格 360px、
    //        列表 64dp、查看器 Size.ORIGINAL 共用同一个 memoryCacheKey，点开一次大图
    //        就把该 key 覆盖成几十 MB 的原图，一张即触发 LRU 淘汰掉周围所有缩略图 ⇒
    //        「滚回来缩略图全部重新加载」。磁盘 key 仍不带尺寸（存的是原始字节，一份即可）。
    //     ④ 缩略图 Empty 首帧并入骨架屏判断：item 被 LazyGrid 回收重建时 painter 初始为
    //        Empty（不是 Loading），此前那一帧什么都不画，露出卡片底色像「图没了」。
    // versionName 涨到 1.5.9：网络照片「滚多了缩略图就丢」的真凶 ——
    //     ① 磁盘缓存 128MB → 512MB。这条缓存存的是**原始字节**（网络照片没有服务端
    //        缩放，单张 3–8MB），128MB 只够 20–40 张，滚过这一段之后前面看过的全被
    //        LRU 挤掉 ⇒ 滚回来只能重新走网络。
    //     ② 内存缓存 25% → 35%，并夹到 64MB~256MB。原先只给了百分比、没有下限，
    //        低堆设备上算出来的值小到「滚两屏就淘汰」。
    //     ③ 新增 loadedKeys（进程内 LRU，4096 条）：成功显示过的图，滚回来时即使
    //        内存缓存已被淘汰，也只走磁盘解码（几十毫秒），不再闪骨架屏 ——
    //        骨架屏原先只查内存缓存，磁盘命中那一刻也会闪，观感像「图丢了又重下」。
    //     ④ 磁盘缓存目录改用 context.cacheDir（原 FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    //        即 java.io.tmpdir，部分 ROM 指向非应用私有且会被清空的目录）。
    // 98（修复轮，versionName 仍为 1.5.9）：一轮 UI 收口，五项：
    //     ① GsyPlayerActivity 代码区残留的 U+FFFD 乱码全部还原（3 个 Toast、
    //        joinToString 分隔符、网速前缀、4 个颜色注释）。文件里另有 465 行
    //        **注释**乱码，不影响运行，逐行还原成本高于收益，暂不动。
    //     ② Emby 收藏 / 演员页的网格列数从写死 3 列改为跟随「每行个数」设置
    //        （与媒体库同一个 embyLibraryGridColumns），加载骨架屏同步列数 ——
    //        此前用户把媒体库调到 5 列后进收藏页会突变回 3 列。
    //     ③ 底部留白统一：首页 / 收藏 / 历史共 5 处 `96.dp` 改读
    //        LocalNavigationBarHeight（+16 呼吸，与原值等值，底栏高度若变则跟随）。
    //        ⚠️ 媒体库 / 详情 / 演员 / 播放列表等 9 处仍是 96dp —— 它们是 NavDisplay
    //        推入的全屏页、底下没有导航栏，那是内容留白而不是给底栏让位；
    //        换成 LocalNavigationBarHeight 会取到默认 0.dp，底部直接贴边。
    //     ④ 列表条目加 Modifier.animateItem()：Emby 媒体库 4 处、收藏 3 处、
    //        演员 1 处、网络目录（网格 + 列表）4 处 —— 切排序 / 增删条目时平滑重排，
    //        不再整屏硬切。只加在 items 带稳定 key 的列表上。
    //     ⑤ 卡片「新视频」角标的硬编码红 Color(0xFFD32F2F) 改走 colorScheme.error，
    //        文字同步换 onError（深色主题下 error 是浅红，白字会糊）。
    // 99（修复轮，versionName 仍为 1.5.9）：网络图片「进查看器再返回、滚动位置丢失」加固。
    //     用户实测两个目录：一个返回后停在中间（框架 saveable 恢复了串键的旧值），
    //     一个直接回顶端。根因两条：① nav3 的 NavDisplay 只组合栈顶 entry，被查看器
    //     盖住时浏览页组合即被销毁，rememberLazyGridState 的保存恢复链不稳；
    //     ② LaunchedEffect(sortChangeTick) 每次全新组合都会启动一次，返回时立刻
    //     scrollToItem(0)。修法：滚动 state 提升到页面层，组合销毁时按「是否还在
    //     返回栈」记位置（被 pop 不记），返回后等条目落位显式 scrollToItem 恢复；
    //     排序回顶加「已消费 tick」比对，只有真的动过排序才回顶。
    // 100（功能轮，versionName 涨到 1.5.10）：「聚合库」——把同一个服务器下多个
    //     同类型媒体库合并成一个本地视图（首页出卡片，点进去是一个归并网格）。
    //     形态调研结论：Emby 服务端只有「建带多路径的新物理库」（写服务器目录结构、
    //     需管理员、单 CollectionType），没有「合并库」概念 ⇒ 做成本 App 内的本地
    //     聚合：Room 存定义（按 serverId 分桶，多服务器不串），AggregateItemPager
    //     给每个成员库开一条泳道（独立游标 + 缓冲），按 SortBy 语义比较取队头接上，
    //     Id 做 tiebreak ⇒ 深翻页不重不漏；随机模式固定种子各库混排 = 换一批。
    //     配置入口在设置里；管理页按当前服务器分桶并显示在管哪台。
    // 101（修复轮，versionName 仍为 1.5.10）：设置页图标补齐 —— 用户看到聚合库
    //     入口有图标、其他条目没有，要求统一。me.zhanghai.compose.preference 2.2.0
    //     的 Preference/SwitchPreference/ListPreference/SliderPreference/
    //     TextFieldPreference/TwoTargetIconButtonPreference 都支持 icon 槽位
    //     （composable lambda，RadioButtonPreference/BasicPreference 不支持、
    //     项目也没用）。做法：全量扫描 11 个设置页的偏好组件，凡无 icon 的一律补
    //     Icons.Outlined.X（tint = colorScheme.primary，与既有条目同风格），图标名
    //     逐一对照 material-icons-extended/core 1.7.8 AAR 验证存在性后注入，共
    //     152 条；注入前先确认无位置参数调用（icon 是命名参数）。
    // 102（修复轮，versionName 仍为 1.5.10）：设置页标题单行化 + 补充图标 + 文案修正。
    //     ① 加图标后长标题（"下载完成后清除服务端播放进度" 13 字、"进入照片浏览器
    //     自动播放幻灯片" 13 字）因可用宽度变窄而折行 ⇒ 给全部 11 个设置页的偏好行
    //     title Text 统一加 `maxLines = 1, overflow = TextOverflow.Ellipsis`（Android
    //     标准单行列表项写法；正常字号下完整显示，超大字号才省略，绝不折行）。
    //     ② 「推荐算法」此前是自定义 Column（非库组件），漏加图标 ⇒ 补 AutoAwesome
    //     （primary 着色，与其它条目一致）。
    //     ③ 「间隔小时数」改名为「合成封面图间隔小时数」（偏好项与输入框标题同步，
    //     该项是随机封面图的重新合成间隔，原命名指向不明）。
    // 103（重构轮，versionName 仍为 1.5.10）：设置页按功能模块重排 ——
    //     ① 原来「Emby 媒体库」是一个 800+ 行、塞 28 项的巨型分区（库组织 / 推荐 /
    //        播放同步 / 照片浏览 / 照片保存 / 卡片外观 / 封面合成 / 元数据 / 随机播放
    //        全混在一起）；拆成 14 个模块分组，每组「1 个分区标题 + 1 张 PreferenceCard」。
    //     ② 跨模块项按真实归属挪位：播放界面布局 → 播放与控件；多版本优先选择 →
    //        Emby 播放与数据同步；卡片快捷收藏 → Emby 卡片与封面；演职员合并 →
    //        Emby 媒体库与元数据；应用锁 → 独立「安全与应用锁」。
    //     ③ 依赖子项（开了主功能才显示）补视觉层级：包进 Column(padding(start=24.dp))
    //        逐级缩进，两条链 = 照片保存位置→恢复默认保存位置、
    //        合成随机封面图→重新合成策略→(合成封面图间隔小时数｜间隔天数)。
    //     ⚠️ 只动组织方式与信息层级：45 项设置的偏好键与功能语义一律未改。
    // 104（返工轮，versionName 仍为 1.5.10）：v103 的「主→子→孙」缩进层级用户反馈违和，
    //     改为**不缩进、靠分组与条件出现表达依赖**：
    //     ① 封面合成三项（合成随机封面图 + 重新合成策略 + 间隔小时数/天数）独立成组
    //        「Emby 封面合成」，组内扁平并列；后两项仅在开关打开 / 策略命中时出现。
    //     ②「Emby 卡片与封面」改名「Emby 卡片外观」（封面已挪走，只剩 4 项）。
    //     ③「恢复默认保存位置」的缩进也去掉 —— 全页不再有任何缩进层级。
    //     ④「下载完成后清除服务端播放进度」13 字加图标后被截断 ⇒ 缩短为
    //        「下载后清除播放进度」（9 字），「下载完 / 服务端」细节留给 summary。
    //     ⚠️ 偏好键与功能语义均未改。
    // 105（修复轮，versionName 仍为 1.5.10）：弹幕 + 分集 + 大文件卡顿 三批（用户反馈）：
    //  ① 弹幕搜索恒空：端点参数名 `anime` → `keyword`（服务端回 200+success:false，原先被静默吞掉）；
    //     并显式消费 success / errorMessage，失败给可读提示而不是"没搜到"。
    //  ② 带分集的电视剧：详情页新增分集区（默认收起，展开为横向卡片列表，多季出季 chip）；
    //     点集 = 整季入队从该集起播（可连播）、长按 = 进该集详情。
    //  ③ 点分集必崩：根因是 `Series`/`Season`/文件夹本身无媒体文件，却拿它的 Id 去请求
    //     `/Videos/{id}/stream`，播放器一路走到 mpv 初始化后崩。play() 增加容器路由
    //     （Series 自动挑"有进度→首个未看→首集"，其余容器直接返回），详情页按钮文案同步。
    //  ④ 弹幕集号错集：传入的是**队列下标**而非真实集号 ⇒ 从第 5 集点播时取到第 1 集弹幕。
    //     新增 `emby_episode_numbers`（与 itemIds 同序，编码 season*1000+episode），
    //     播放页 `currentEpisodeIndex()` 解码；队列换位/删除同步维护该平行数组。
    //  ⑤ 70GB 大文件卡成幻灯片（三个独立原因）：
    //     · `MpvDiskCache.trimIfNeeded` 在 handleFileLoaded（主线程、首帧要上屏那一刻）
    //       里 `deleteRecursively()` 几百 MB~2GB 缓存 ⇒ 挪到 IO 线程异步清理；
    //     · `cache-secs`/`demuxer-readahead-secs` 原为 30 秒，高码率下远超 64MB 字节闸门
    //       ⇒ readahead 反复停等；收到 8 秒与字节上限匹配；
    //     · `time-pos` / 网速采样在主线程每 42ms/1s 抢 mpv 播放线程锁（JNI 同步调用），
    //       码率越高持锁越久 ⇒ 挪到 Dispatchers.Default。
    //  ⑥ 解码通用提速：`vd-lavc-dr=yes` 原被误写在 applyAnime4KShaders 里（不开着色器就
    //     没有这层优化）⇒ 挪到 initOptions 无条件生效；`vd-lavc-film-grain` cpu → gpu。
    // 106（修复轮，versionName 仍为 1.5.10）：弹幕时间轴偏移改为**按剧记忆**（用户反馈）：
    //  ① 原先全局偏移对所有片生效，换片必须先手动清零。新增 `danmaku_series_offsets`
    //     偏好（"seriesKey=ms;..." 紧凑串），key = Emby SeriesId，无 SeriesId 时退回全局。
    //  ② `DanmakuPreferences.timeOffsetFor(seriesKey)` / `setSeriesOffset` / `hasSeriesOffset`；
    //     `setTimeOffset` 写「本剧覆盖」，新增 `clearTimeOffset` 删覆盖回落到全局。
    //  ③ 换剧（seriesKey 变化）时同时清 `manualAnime` —— 上一部的弹幕源选择不该带到下一部；
    //     `rememberSelection` 关掉不影响「按剧偏移」（两者独立）。
    //  ④ 弹幕面板在偏移滑杆下显示「已按本剧记住」+「跟随全局」撤销按钮。
    // 106 追加（同号未发布）：四项对标 Yamby 的播放链路能力（用户要求「考虑全面、注意联动」）：
    //  ⑦ 弹幕时间轴按剧记忆（见上 ①-④）。
    //  ⑧ 片尾「下一集」悬停卡（对标 Yamby `next_up_sec`）：片尾前 N 秒（默认 30）右下角出卡片，
    //     带下一集标题、点即切；与原有「连播倒计时胶囊」分工不同（那个是最后几秒催切，
    //     这个是提前问要不要接着看），两者可同时出现、上下错开摆放。
    //     偏好 `show_next_up_card` / `next_up_show_seconds`；关闭 = 完全回到旧行为。
    //  ⑨ 下一集衔接预热：片尾前 N 秒（默认 45）对下一集流地址发一次 64KB Range 请求，
    //     只为把 DNS/TCP/TLS 与服务端 prepare 焐热 ⇒ 切集少黑一下。
    //     ⚠️ 刻意不用 mpv `prefetch-playlist`：那会让两路同时拉流，70GB 片源上会把当前集拉卡
    //     （v105 刚修完大文件卡顿，不能再引入这个副作用）。偏好 `prefetch_next_episode(_seconds)`。
    //  ⑩ 分集批量下载：分集区展开后新增「下载本季 N 集」（只挑没下过/不在队列里的集），
    //     分集卡右上角新增单集下载小钮（三态：可下载 / 已交出去 / 已下好）。
    //     状态复用详情页已有的 downloadTasks Flow，不额外发请求。
    // 107（修复轮，versionName 仍为 1.5.10）：三件事（用户指定「低成本项 + 新 UI 结构」）：
    //  ① 弹幕开关「点不动」根因修复：原先 `setEnabled` 只写偏好 + 刷渲染参数，
    //     **没有任何拉取触发**；而拉取的唯一入口 `onEpisodeChanged` 只在切集时调用 ⇒
    //     开播时关着、后来打开就永远没弹幕。现在 `pendingTitle`/`pendingEpisodeIndex`
    //     无条件记录（开关关着也记），打开时按「已有弹幕 / 记住的手动选番 / 自动匹配」补拉；
    //     `setAutoMatch`、`setWithRelated` 两个开关同样补拉。抽屉里补加载态指示器。
    //  ② 防重叠（对标 `prevent_danmaku_overlapping`）：`DanmakuContext.preventOverlapping`
    //     接线 + 面板开关，默认开。⚠️ 必须逐个弹幕类型给值，Map 缺的 key 视作 false。
    //  ③ 按剧记亮度（对标 `resume_brightness`）：`PlayerPreferences.brightnessFor` /
    //     `setSeriesBrightness`，与全局 `rememberBrightness` **叠加**；切内核经
    //     `PlayerHandoff.brightness` 透传（GSY 是另一个 window，不传就弹回系统值）。
    //     ⚠️ 读 extra 必须判 `hasExtra` —— 默认 0f 是合法亮度（全黑）。
    //  ④ 分集三分区（对标 `siblingEpisodes` / `showingEpisodes` / `personEpisodes`）：
    //     `RelatedEpisodesSection` = 同季 / 特别篇 / 演员相关，只在**单集详情页**出现
    //     （那里原本没有任何切兄弟集的入口）。同季优先用 `SiblingIds`（随详情零额外往返），
    //     老服务端不返回时退回按 SeriesId 拉全剧再筛同季。演员相关只取首位 Actor。
    //  ⑤ 弹幕跨内核集号透传：`PlayerHandoff.episodeNumbers` + `episodeIndexAt()`，
    //     修掉 GSY 侧把队列下标当集号导致的错集。
    // 108（修复轮，versionName 仍为 1.5.10）：弹幕「抽屉里写着已匹配 N 条、画面上一条没有」，
    //   用户反馈「开关按了没反应」。根因在**渲染层**，不是拉取层（v107 修的是拉取）：
    //   ① `DanmakuOverlay` 灌数据的 `LaunchedEffect` 原来只以 `episodeKey` 为 key。
    //      切集瞬间 `onEpisodeChanged` 先把 comments 清空、联网回来才填满，而 mpv 侧
    //      episodeKey 在清空的同一刻就已经变了 ⇒ effect 拿着**空列表**跑了一遍走 `stop()`，
    //      之后 comments 从空变非空而 key 不变 ⇒ 永远不再 `prepare()` ⇒ 零渲染。
    //      改为三段 key `(danmakuView, episodeKey, comments.isEmpty())`：布尔只在
    //      「空 ↔ 非空」翻转时多跑一次，既保证「有数据必灌一次」，又不会因为列表内容
    //      变化（屏蔽词、偏移）重建引擎（那会新建 HandlerThread、弹幕叠两遍）。
    //   ② `setEnabled` 去掉「值没变直接 return」：它不是幂等 setter，而是「打开它」的意图，
    //      连点两下时第二次必须仍然补拉，否则观感就是彻底点不动。关闭时顺带清掉旧错误提示。
    //   ③ `pendingTitle` 为空时不再静默 return，给一句可读提示。
    //   ④ 排查用日志：`DanmakuSheet` 记点击回执（手势有没有落到开关上）、
    //      `PlayerDanmakuState` 记 setEnabled 的上下文、`DanmakuOverlay` 记灌数据条数/布局状态。
    //   ⑤ 顺带把 DFM 的一个误区写进注释：`start()` 在没布局好时**不会丢**，
    //      `DrawHandler.handleMessage(START)` 内部每 100ms 重投一次直到 `isViewReady()`，
    //      不需要我们额外兜「等布局完成再 start」。
    // 109（修复轮，versionName 仍为 1.5.10）：v108 实测日志 mpvex_logs(6) 定位到**三层**问题：
    //   ① 「开关点不动」的真根因：面板 `PlayerSheets.kt` 读的是 `danmakuState.sheet` 这个 flow，
    //      而 `_sheet` 初值 `DanmakuSheetState()` 的 `enabled` 是 **false**，全项目
    //      **一次都没调过** `readSheetState()` ⇒ 面板永远读 false，点了立刻弹回。
    //      日志铁证：连续 8 次点击全是「弹幕总开关点击：false → true」。
    //      修法：新增 `refreshSheet()` = `_sheet.value = readSheetState()`，
    //      `setEnabled` 落库后**立刻**调；同时把 host/appId/appSecret/autoMatch/withRelated/
    //      rememberSelection/fontScale/speedScale/alpha/area/density/blockWords*/timeOffset
    //      全部 setter 统一收口到它（原先各自 `.copy()` 漏字段是同类 bug 的温床）。
    //      * 顺带补 `readSheetState()` 漏掉 `withRelated` 字段（面板上那个开关永远是 false）。
    //   ② 首次起播**从不通知弹幕状态机**：`onCreate` 走 `player.playFile(uri)` 直通，
    //      不经 `loadPlaylistItemInternal` ⇒ `pendingTitle` 恒为空。
    //      日志铁证：`setEnabled(true)：pending=""`、`弹幕灌数据：… key=`。
    //      ⇒ 自动匹配永远拿不到片名（只能手动搜番），打开开关时的补拉也永远死在
    //      「还没拿到片名」分支。修法：`handleFileLoaded` 里补调一次
    //      `notifyDanmakuEpisodeChanged()`（封装：切集与首播共用同一套参数口径）。
    //      并且 `onEpisodeChanged` 在「片名空 + 集号 0」时直接跳过，不再把 pending 覆成空串。
    //   ③ `episodeKey` 退化成空串：改为「片名/集号都缺就留空」，
    //      并由 `syncEpisodeKey()` 在**拉到数据后**用 dandanplay 的 `番名#episodeId`
    //      覆盖成稳定身份（本地播放没有 Emby SeriesId 时也能区分换片）。
    //   附：`setBlockWords` 原来在**已过滤**的列表上再滤 ⇒ 删掉屏蔽词后条目回不来；
    //      改为在 `lastRawComments` 上重滤（与 `setTimeOffset` 同口径）。
    // 110（修复轮，versionName 仍为 1.5.10）：弹幕「开关能开、条数也有、画面上一条都没有」。
    //     用户一眼点到关键：「不是让你反编译 yamby 了吗，弹幕的整个流程链参考他的呀」。
    //     ⇒ 先把 Yamby 2.1.0.11 反编译取证（dexdump 全量 + ELF 符号表），结论：
    //       **Yamby 一行 DanmakuFlameMaster 运行时类都没引用**（DanmakuView /
    //       DanmakuContext / DanmakuParser / master/flame 命中数全 0，
    //       `DanmakuFlameMaster` 只作为 LICENSE 字符串出现）。它的
    //       `YambyPlayerControllerView` 有一整套 **NATIVE** 弹幕 API
    //       （getDanmakuCache / setDanmakuCache / getDanmakuTimeOffset / …），
    //       渲染入口是 `ۥۚۥۘۧ۠ۨ(List, Z)V`（NATIVE），调用点在
    //       `if (duration >= 10s) { native.render(getDanmakuCache(), true); setDanmakuCache(null) }`
    //       —— 一次性把弹幕推给 native，推完就清空 Java 侧缓存。实现落在
    //       `libnmmp.so`（1.1MB，导出 vmInterpret/gVm/cacheInitial + JNI_OnLoad = 加密 VM），
    //       `libmpv.so` 里 **0 处 danmaku 字样** ⇒ 也**不是**转 ASS 交给 mpv。
    //       连 `onPlayPause` / `onPlaybackSeek` 都是 NATIVE ⇒ 整个播放控制层在 native。
    //       ⇒ 「照抄 Yamby」= 自写 native 渲染器，工程量极大且不解决眼前问题。
    //       真正该抄的是**它的时序纪律**：不在 View 尚未就绪时驱动渲染。
    //     我们这边的真凶（DFM 源码级确认，v109 及以前一直在犯）：
    //     ① `prepare(parser, ctx)` → 同一帧 `start(0L)`。而 `DanmakuView.start(long)` 是
    //        `handler.removeCallbacksAndMessages(null)`（handler 非空时），
    //        把 prepare() 刚投出的 PREPARE 消息**连同它 100ms 自投的重试链一起清空**；
    //        而 `AndroidView` 是先组合、后布局 ⇒ 那一刻 `onLayout` 还没跑、
    //        `isViewReady()`(=isSurfaceCreated) 还是 false ⇒ PREPARE 本来就该重试。
    //        结果：再没人投 PREPARE ⇒ `mReady` 永远 false ⇒ `drawTask` 永远 null
    //        ⇒ `onDraw` 里 `handler.draw(canvas)` 直接返回 ⇒ 画布永远空白。
    //        `resume()` 救不了：它是 `if (prepared) post(...) else if (handler == null) restart()`
    //        —— 「handler 非空但未 prepared」这个**第三态什么都不做**（库的漏洞）。
    //     ② 空列表时调 `danmakuView.stop()`。`stop()` 不可逆（handler=null、线程 quit+join），
    //        之后 `resume()` 走 `restart()` → `start()` → handler==null → 调**无参** `prepare()`
    //        （只建线程、不 setParser）⇒ PREPARE 分支 `mParser == null` 恒成立 ⇒ 100ms 死循环。
    //     修法：① 只 `prepare()`，**不 start**；注册 `DrawHandler.Callback`，
    //        在 `prepared()` 回调里才 `start(当前播放位置)`（DFM 官方用法）；
    //        并按**当前**播放状态决定 resume 还是 pause（start 一定会把计时器跑到播放态）。
    //     ② 空列表只 `clear()`，绝不 `stop()`。
    //     ③ `DanmakuPositionSync` 增补「播放状态对账」：另一处 `DisposableEffect(playing)`
    //        是在引擎未 prepared 时下发的，DFM 第三态会静默吞掉 ⇒ 每 250ms 补一次。
    // 111（修复轮，versionName 仍为 1.5.10）：v110 出包后用户确认 `prepared()` **已经触发**
    //     （日志 13:18:18.354「DFM prepared，从 938437ms 开始绘制」）、2528 条已灌、
    //     size=2340x1080，但画面仍然一条都不动。⇒ 第二层根因，全部在 **parser 产出物**
    //     而不是在时序上（`DanmakuListParser.parse()` 缺了官方 `FakeDanmakuView$CustomParser`
    //     每次都写的三行赋值，DFM 源码逐行核对）：
    //     ① 缺 `item.setTimer(timer)`。`DrawTask.loadDanmakus()` 只做 `parser.setTimer(mTimer)`，
    //        把计时器存在 **parser 字段**上，**不会替你分发给每条 item**。缺了它：
    //          · `BaseDanmaku.isTimeOut()` = `mTimer == null || …` ⇒ **恒 true**
    //            ⇒ `DanmakuRenderer.Consumer.accept` 第一句就 `disp.recycle(drawItem);
    //               return ACTION_CONTINUE` ⇒ 2528 条全部绕过绘制。
    //          · `isLate()` 同理恒 true ⇒ 第二道保险 `ACTION_BREAK` 直接终止整轮 forEach。
    //          · `R2LDanmaku/FTDanmaku/L2RDanmaku.layout()` 是
    //            `if (mTimer != null) { … setVisibility(true) … } setVisibility(false);`
    //            ⇒ 走 else 分支永不置 VISIBLE。
    //     ② 缺 `item.flags = mGlobalFlagValues`。`BaseDanmaku.flags` 默认 null，而
    //        `isShown()/isMeasured()/isPrepared()/setVisibility(true)` 全都要读
    //        `flags.XXX_RESET_FLAG` ⇒ NPE 或判等失败。`DrawHandler.addDanmaku()` 里就有这行。
    //     ③ 缺 `item.setVisibility(true)`（须排在 ② 之后）。`visibility` 默认 0 = INVISIBLE。
    //     另修两个「同样让人误以为没渲染」的坑：
    //     ④ **`index` 恒为 0**：走 `parse()` 批量灌数据时没人赋 index（正常入口是
    //        `DrawTask.addDanmaku()` 里 `item.index = danmakuList.size()`）。而 `Danmakus`
    //        默认 `TreeSet(TimeComparator)`，落到 `DanmakuUtils.compare()` 的**最后一行是
    //        库自己的 bug**（`obj1.hashCode() - obj1.hashCode()` 恒 0）⇒ time 与 index 都
    //        相等时比较器返 0 ⇒ `TreeSet.add` 返回 false ⇒ 同毫秒的弹幕**静默被丢**。
    //     ⑤ **字号没换算**：dandanplay 第 3 列是「档位」(25=标准) 而 `paint.setTextSize()`
    //        吃的是 **px**；25px 在 1080p 横屏上小到看不见。按库自己的密度约定
    //        `(density - 0.6f)` 补齐（与 `updateViewportState` 折算滚动时长同一基准），下限 1f。
    //     顺带把 `prepared()` 里的 `start(at)` 改成 `seekTo(at)`：`start()` 会
    //     `removeCallbacksAndMessages(null)` 并把 `mInSeekingAction` 置 true，与 250ms 的
    //     进度同步撞车时 `syncTimer()` 一直返 0 ⇒ 计时器卡死在起点。
    //     `DanmakuPositionSync` 也不再绑 `playing`（暂停时退出 ⇒ 恢复时强制硬 seek），
    //     首轮不做 seek（交给 prepared 回调），播放状态对账拆成常驻 effect。
    // 112（修复轮，versionName 仍为 1.5.10）：两件事。
    //   〔A〕**弹幕自动匹配拿错名字**（用户反馈「这个自动匹配的逻辑你参考看看 yamby 的，
    //       不应该用单集标题去搜索电视剧名」）。根因：`PlayerActivity.notifyDanmakuEpisodeChanged`
    //       传的是 `fileName`，而 Emby 发起方写的是 `putExtra("filename", first.Name)` ——
    //       Episode 的 `Name` 是**分集名**（「都市传说」），拿它去查 dandanplay 当然搜不到番，
    //       要搜的是**剧名**（「某科学的超电磁炮」）。
    //       ⇒ 新增与 `playlistSeriesKeys` 平行的剧名数组（`EXTRA_SERIES_NAMES` /
    //       `EXTRA_SERIES_NAME`，源自 `EmbyItem.SeriesName`），由 `EmbyViewModel.play` 写出；
    //       播放器侧新增 `danmakuMatchTitle(index)`：有剧名用剧名，**没有就退回 fileName**
    //       （电影 / 本地文件 / 网络串流都没有 SeriesName，行为保持不变）。
    //       ⚠️ 并行数组必须在 `movePlaylistItem` / `removePlaylistItem` 里同步移动删除，
    //       且 intent 二次解析处（跨内核重建走的那条路）也要整份替换，否则队伍换错 ±1 位。
    //   〔B〕**滚动弹幕存活时间过短**（用户反馈「滚动一秒左右就消失了」「滚动速度能接受，
    //       就是消失太快」，剧集单集约 24 分钟）。`DanmakuFactory` 的
    //       `REAL_DANMAKU_DURATION` 被夹在 [MIN=4000, MAX=9000] 之间，而且所有滚动弹幕
    //       共用同一个 `Duration` 实例、事后还要被「滚动速度」偏好再乘一遍 ⇒ 实际值是黑箱，
    //       在横屏宽设备上会退化到三四秒。
    //       ⇒ ① 修正 `createDanmaku` 的重载误用：原先调的
    //          `createDanmaku(type, displayer, 1f, 1f)` 把 viewportScale **当作**
    //          viewportSizeFactor 传下去，等于把 `setDisplayer()` 刚设好的密度因子覆成 1.0；
    //          改用 `createDanmaku(type, danmakuContext)`，它用的是正确的
    //          `CURRENT_DISP_SIZE_FACTOR`。
    //        ② parse 结束后按「跑完一屏要多久」这个直观物理量重写时长（滚动 10s / 固定 5s），
    //          并抬高 `MAX_DANMAKU_DURATION` 渲染窗口 —— 窗口比时长小会让弹幕提前被
    //          `sub(begin,end)` 裁掉。
    //        ③ 新增运行时测量：`DFM 状态` 里带「时钟比」（timer 增量 / 真实时间增量）。
    //          一次就能区分「时长短」与「计时器跑太快」这两种完全不同的病因。
    //    ⚠️ 事后回归：自动匹配已确认正常（v112 的剧名数组生效），但用户实测滚动弹幕
    //       「还是两秒左右就消失」—— 即 112〔B〕只解决了一半。
    // 113（修复轮）：曾把时长重写下沉到 prepared() + 250ms 对账兜底「sizeChanged 覆写」。
    //   事后仍被用户抓到「有的消失快有的慢」—— 用户日志实锤两个叠加因：
    //     ① `弹幕时长重写(parse)：scroll=9441→16023ms`：用户速度偏好 1.6 经
    //        `setScrollSpeedFactor` 乘在 duration 上（DFM 里 factor 越大越慢，
    //        与我们 UI 文案「>1 更快」相反）⇒ 10 秒被乘成 16 秒；
    //     ② 滚动 16s vs 固定 5s 同屏 ⇒ 观感「有的慢有的快」。
    // 114（本轮，对照桌面 `Sample` 官方 demo 逐条清理）：**停止覆写框架内部时长**。
    //   · 删 `DanmakuFactory.MAX_Duration_*.setValue(...)` / `updateMaxDanmakuDuration()`
    //     全部外挂写入（parse / prepared / 250ms 对账三处），时长回归框架自己的
    //     `REAL = 3800 × viewportSizeFactor × 屏宽/682`（[4000,9000] 夹住；
    //     2340px 实测约 5.9s = 「从右出现到完全移出左边缘」）。
    //   · 用户可控项保留：`setScrollSpeedFactor` 改为 `1f / speedScale`（官方 demo
    //     自己也 setScrollSpeedFactor，只是它用固定常量）—— 默认 1.0 = 框架默认行为，
    //     调到 2.0 ⇒ factor 0.5 ⇒ duration 减半 ⇒ 更快，UI 文案「>1 更快」终于对得上。
    //   · 保留的被迫适配（与官方 demo 的差异，均非自作主张）：
    //     自写 parser（AAR 裁了 BiliDanmukuParser，字段赋值照抄 jar 内官方示例
    //     `FakeDanmakuView$CustomParser`）；`enableDanmakuDrawingCache(false)`
    //     （规避 CacheManagingDrawTask 的 NPE，崩在库自己的线程上 catch 不住）；
    //     `prepared()` 里 `seekTo(at)` 而非 demo 的 `start()`（播放位置由 mpv 驱动，
    //     demo 注释也写明 "sync to the video time in your practice"）。
    // 115（本轮，用户要求「从源码修并内置」）：DanmakuFlameMaster 从 AAR 改为**源码内置**
    //   （app/src/main/java/master/flame/danmaku/ + tv/cjump/jni/，AAR 依赖已删，
    //   proguard 补 keep），从引擎层修掉「寿命短」的三个真凶：
    //     ① **速度与寿命解耦**：新增 `Duration.getLifetime()`（= mInitialDuration，
    //        不吃速度因子）作寿命判据（isTimeOut/isOutside/layout/getAccurateLeft），
    //        滚动速度仍读 value（吃 factor）⇒ 调速不再改寿命。
    //     ② **计时器不再超前真实时间**：原库 syncTimer 每帧至少推进硬编码 16ms，
    //        90/120Hz 屏上 timer 持续超前（时钟比>1）⇒ 寿命被等比例烧短
    //        （120Hz 上 5.9s 只活约 2.9s 真实时间 —— 用户反馈「大概 2 秒就消失」）。
    //        改为按屏幕真实刷新率取帧间隔 + 超前时只按真实帧间隔推进。
    //     ③ 修两个库 bug：`DanmakuUtils.compare` 的 `obj1.hashCode() - obj1.hashCode()`
    //        笔误（同毫秒+同 index 弹幕被 TreeSet 静默丢弃）、`Duration.setFactor`
    //        的 `if (factor != f)` 守卫（先调速后 setValue 时 value 不重算）。
    //   · 新增引擎内诊断 `DFM-Diag`（2s 一条：timer/时钟比/取窗/scrollVal/scrollLife）。
    //   · ⚠️ v116 事后修正：115② 的「计时器超前」**不是**「2 秒就没」的主凶 ——
    //     用户切 60/120Hz 复测无效已证明；①③ 作为正确修复保留。
    // 116（本轮，v115 出包后用户实测「还是一样，60/120Hz 都只活 2 秒，长弹幕探个头就消失」）：
    //   真凶是 **DanmakuPositionSync 每 ~1.75 秒假 seek 一次**，与引擎无关：
    //   · 旧逻辑用「上次 seek 的位置 lastSeekSec」当对齐基准，而它**只在 seek 时更新**。
    //     正常播放时 now 每 250ms 前进 0.25s ⇒ 每约 1.75s 就超过 1.5s 阈值 ⇒ 硬 seekTo。
    //   · 而 DFM 的 seek 是「跳转」语义：`DrawTask.seek(pos)` 会把
    //     `mStartRenderTime = pos`，渲染器 `getActualTime() < startRenderTime` 的弹幕
    //     **永久跳过不画** ⇒ 每次假 seek 后所有在飞舞幕全部中途消失。
    //     每条弹幕最多活 ~1.75s（用户看到的「2 秒」）；长弹幕位移慢，1.75s 内只从
    //     右缘露出个头 ⇒ 「探了个头就消失」；与刷新率/速度设置全都无关。
    //   · 修法：对齐基准改为**引擎自己的计时器**（danmakuView.currentTime）——
    //     正常播放 drift≈0 永不 seek；只有真实跳变（拖动/卡顿/引擎计时器冻结）才纠正。
    //   · 教训：v112~v115 在 duration/lifetime/计时器上打转全砸，根因在集成层
    //     （seek 语义 + 错误的对账基准），不在引擎参数。详见 DanmakuOverlay.kt
    //     DanmakuPositionSync 文档与 2026-10-19.md v116 段。
    // 117（versionName 涨到 1.6.0；v116 经用户确认「弹幕正常了」后的一轮打磨）：
    //   〔A〕**竖屏播放器弹幕全挤在最上方**：mpv/GSY 竖屏下窗口铺满全屏、画面居中一条，
    //     而弹幕叠层跟着窗口铺满 ⇒ `AlignTopRetainer` 从**窗口顶部**往下 packing
    //     （topPos = lastItem.getBottom() + margin，isOutVerticalEdge 按 disp.getHeight()
    //     判溢出）⇒ 弹幕全落在画面上方的黑边/状态栏区域。
    //     ⇒ DanmakuOverlay 新增 `videoAspectProvider`：按视频宽高比算出居中画面矩形，
    //     把 DanmakuView 约束到它里面（offset+size，px 重载）；retainer 改为在画面
    //     条带内 packing，REAL 时长也改按画面宽度算。无黑边时矩形≈全屏自动回退。
    //     两个调用点：PlayerActivity 传 `player.getVideoOutAspect()`，
    //     GsyPlayerActivity 传 `activePlayer.currentVideoWidth/Height`。
    //   〔B〕**设置项文案截断**：「显示控件时同时显示系统状态栏」（14 字，加图标后
    //     maxLines=1 被省略号截断）⇒ 缩短为「显示控件时显示状态栏」，与相邻的
    //     「显示控件时显示导航栏」同长同构。
    //   〔C〕**关于-致谢补弹幕引擎**：DFM 自 v115 起源码内置、不再是 gradle 依赖，
    //     AboutLibraries 清单里不会出现 ⇒ 手动加一条 DanmakuFlameMaster 致谢
    //     （中英文 summary 各一条：i18n_ack_danmaku_summary）。
    //   〔D〕**弹幕面板开关错乱 / 「防止弹幕重叠」关了就打不开**（v117 出包前用户实测）：
    //     根因是 DanmakuSheetState 快照与偏好脱钩，两处：
    //       1. `setPreventOverlapping` 是全类唯一不调 `refreshSheet()` 的 setter
    //          ⇒ 点它偏好已改、面板 UI 停旧值（"关了再也打不开"）；之后碰任何一个
    //          会刷面板的控件（自动匹配/密度/滑杆…）旧值一次性追平，另一个开关
    //          跟着翻面（"开这个同时那个就会关掉"）。
    //       2. 面板打开时从不刷新 ⇒ 「设置-弹幕」页直接写偏好开了总开关+自动匹配，
    //          播放时弹幕正常加载，面板总开关却显示灭的（构造时默认快照 enabled=false）。
    //     ⇒ setPreventOverlapping 补 refreshSheet()；PlayerSheets 的 Sheets.Danmaku
    //       分支加 LaunchedEffect 打开即刷；refreshSheet() 转 public。
    //     另：关掉防重叠后弹幕允许互相覆盖是**设计行为**（换密度上限），叠在顶端
    //     是竖屏 packing 问题，由本条〔A〕修复。
    //   〔E〕**详情页分集卡片高度参差 + 「下载本季」不像按钮**（用户反馈）：
    //     ① EpisodeCard 标题 maxLines=2 ⇒ 一行标题的卡比两行矮一截，横向列表参差不齐。
    //        修法：标题按 bodySmall.lineHeight×2 固定占两行高（sp→dp 乘 fontScale，
    //        随系统字号缩放；lineHeight 缺省按 1.2×字号兜底），一行也占住位置。
    //     ② 「下载本季 X 集」原是一段带 clip 的文本，看着像正文链接 ⇒ 改成胶囊按钮
    //        （secondaryContainer 底 + 圆角 50% + Download 图标 + labelMedium）。
    //     另修 v117 编译回归：DanmakuOverlay 竖屏适配块缺 fillMaxSize import、
    //     offset/size 的 Int 参数不匹配 Dp 重载 ⇒ 改 Modifier.layout 做 px 精确定位。
    //
    // ────────────────────────────────────────────────────────────────────────
    // v118（1.6.1）—— 播放器点击回归 + 分集卡片显示 + 构建提速
    // -------------------------------------------------------------------------
    // 〔A〕⚠️⚠️ **弹幕层吞掉整屏触摸，点屏幕唤不出控制栏**（v117 竖屏适配引入的 P0 回归）。
    //     现象（用户实测，这条是定位的关键）：
    //       **弹幕还没渲染出来时点屏幕完全正常，弹幕一出来就怎么点都点不动了**。
    //     根因：弹幕层是 `binding.controls` 那棵 Compose 树里**最靠后（= 最上层）的
    //     全屏 `AndroidView`**。Compose 命中测试里兄弟节点按 z 序**上面那个先收事件**；
    //     而 `AndroidView` 的 LayoutNode 必挂 `pointerInteropFilter`（全屏尺寸的
    //     PointerInputModifier），DOWN 一到它就先接管 ⇒ 排在它后面的 `GestureHandler`
    //     收不到事件 ⇒ 单击永远走不到「显/隐控件」那条分支。
    //     ⚠️ v117 之前同样挂在最上层却没事，是因为那时自动匹配是坏的、弹幕平时根本
    //     进不了组合；v117 修好自动加载后它常驻，问题才暴露 —— 别再据此排除它。
    //     修法（**改分层，不赌事件传递**）：
    //       ① 弹幕层挪出控件树，独立成 `binding.danmaku` 这一 View 层，
    //          且在 XML 里声明在 `controls` **之前** ⇒ 在原生 View 体系的 z 序里
    //          它就**在控件下面**，触摸必然先给上层的 controls。
    //       ② 顺手把叠放修正成注释里一直写的「弹幕在画面之上、控制条之下」。
    //       ③ DanmakuView 外加 `InertTouchFrameLayout`，触摸在容器层就短路成 false
    //          ⇒ `pointerInteropFilter` 必然拿到 false。与 GSY 页的挂法统一。
    // 〔B〕**分集卡片把标题截断成一行**（用户对 v117 〔E〕①的反馈）：
    //     `height(两行)` 写死高度，算出来的高度比文字真正要的两行少一两个像素时
    //     第二行就被裁掉。改用 `heightIn(min=两行)`：一行标题照样占位、卡片等高，
    //     两行标题要多少给多少，两行都完整显示。
    // 〔C〕「下载本季 X 集」按钮按用户要求从右对齐改为**左对齐**（与上下行的 16dp
    //     起始边距对齐）。
    // 〔D〕**构建提速**（用户反馈「编译越来越慢」）：
    //     ① `org.gradle.workers.max=6`（物理核数）—— 之前命令行一直传
    //        `--max-workers=2`，把 6 核机器当 2 核用，这是慢的主因之一；
    //        ⚠️ 构建命令一律不要再传 --max-workers（会覆盖配置）。
    //     ② 配置缓存遇到不兼容时降级为 warn 而不是整构建失败
    //        （`org.gradle.unsafe.configuration-cache-problems=warn`）。
    //     ③ 复核 kapt→KSP：仓库里已无任何 kapt（只剩 `ksp(libs.room.compiler)`）。
    //     ④ 6G 堆 / 并行 GC / 构建缓存 / 配置缓存 / 并行 / VFS watch 早已开启。
    // 〔E〕去掉 v117 引入的 `BoxWithConstraints`：弹幕层容器尺寸改由 `onSizeChanged`
    //     拿（少一层 SubcomposeLayout，命中路径也更干净），几何计算一行没变。
    // ────────────────────────────────────────────────────────────────────────
    // v119（versionCode 119）—— **分集卡片真正等高**（用户对 v118〔B〕的反馈「还是有的高有的矮」）。
    //     卡高其实有**三个**变量，v118 只治了第一个，剩下两个漏了 ⇒ 照旧参差不齐：
    //       ① 标题 1 行 vs 2 行      → v118 的 `heightIn(min = 两行)` 已治；
    //       ② 时长行 `runTimeLabel()` 在 `RunTimeTicks` 缺失时返回 **null**
    //          ⇒ 整行塌掉，卡片矮一截（±20dp，这是最大的一个变量）；
    //       ③ 进度条只在「看过一部分」时才渲染 ⇒ 又是 ±9dp。
    //     ⚠️ 根因不是尺寸算错，是**卡高由「有没有内容」决定**：
    //        两个 `if (xxx != null)` 各自包一块，内容一缺高度就变。
    //     修法：②③ 合并进**同一行**并给它**固定高度**（缺内容渲染空串占位，位置不塌），
    //        进度条挪到时长右边做成 32dp（卡宽 150dp 塞不下一整行）。
    //        自此三处全部恒定，卡片张张等高，标题仍是两行完整显示不截断。
    // ────────────────────────────────────────────────────────────────────────
    // v120（versionCode 120）—— **「不喜欢视频管理」页删除确认改为按压保持式（press-and-hold）**。
    //     原二次确认要求手输「删除」二字；但删除入口来自侧滑手势（swipe-to-reveal）露出的按钮，
    //     属高误触路径，一击即过仍有风险。改为：在红色删除按钮上**持续按住**，进度条自左向右
    //     填充，达到 100%（按压时长阈值 2000ms，即持续按住 2 秒）才发起对 Emby 服务器端源文件的**物理删除
    //     （不可逆）**；任意时刻松手即中止并自动回退至 0%。以「按压时长阈值」替代字符输入，
    //     在保留二次确认防线的同时进一步压低不可逆数据损失概率。删除后仍以 Toast 汇总结果。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v121（versionCode 121，修复轮不涨名，versionName 仍为 1.6.1）—— **演员作品页收藏红心**。
    //     现象：演员作品页右上角红心永远置灰、状态读不到；导演作品页却正常。
    //     根因（用户服务端实测）：`/Persons?Ids=` 不被支持（恒返回空），
    //     `/Users/{uid}/Items/{personId}` 对部分人员卡满 30s 后回 HTTP 500 ⇒
    //     getPersonById 三条路径全败返回 null ⇒ 演员页 `?: return@LaunchedEffect` 直接退出，
    //     唯一可靠的「收藏列表法」压根没被执行；导演恰好走通单条读，所以看着正常。
    //     修法：① getPersonFavoriteState 把实测 54ms 且真值新鲜的
    //     `/Persons?IsFavorite=true` 列表法提到第一步，单条读降级为兜底；
    //     ② 演员页不再把「读收藏状态」挂在 loadPersonById 的非空返回值后面，并把它排在拉作品之前。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v122（versionCode 122，versionName 仍为 1.6.1）—— **演员 / 导演作品页：排序 + 页内搜索**。
    //     高产演员能有几百部作品，只能靠这两样在海里捞片。两者都是**纯本地、不发请求**：
    //     本页作品是进页面时一次性拉全的（loadPersonItems 翻页循环），所以本地过滤 / 重排零成本，
    //     而且「搜不到」不会因为分页漏页 —— 与收藏页那种「只搜到已翻到的页」不同。
    //     · 排序：名称 / 年份 / 评分 / 最新添加（与收藏页 FavoriteSort 同口径，
    //       名称走 SortName 而不是 Name，避免与服务端顺序打架）；选择落盘 embyPersonSort。
    //     · 搜索：匹配片名 + 原名（原名用于「用英文原名搜中文标题」），输入框清空键 + 空态回显搜索词。
    //     版式复用收藏页的 FavoriteSearchSortRow（左搜索框 + 右「当前排序」触发器 + 下拉带方向副标题）。
    //
    // v123（versionCode 123，versionName 仍为 1.6.1）—— **作品页排序改版 + 随机排序 + 修搜索闪封面**。
    //     · 排序改成**媒体库 / 首页那一套**：菜单里选中的那一项直接标出当前方向（↑ 升 / ↓ 降），
    //       再点一次已选中的项就反转方向，不再单列「升序 / 降序」两个选项（菜单短一半）；
    //       触发器上也带 ↑ / ↓，不看菜单就知道现在排的是什么方向。方向落盘 embyPersonSortAsc。
    //     · 新增**随机排序**：再点一次「随机」换一批。用固定种子洗牌（randomSeed），
    //       否则每次重组（敲一个搜索字都会重组）都重洗，列表会在手指底下乱跳。
    //     · 修「搜索时搜索框下面的封面闪一下」：① 空态提示改成叠在网格**上层**而不是替换网格，
    //       网格不再因「筛出 0 条」被整体卸载重建；② EmbyImage 首帧同步查一次内存缓存
    //       （EmbyImageLoader.peek），命中就直接上图，不再先闪一帧占位图标。
    // ────────────────────────────────────────────────────────────────────────
    //
    // v124（versionCode 124，versionName 仍为 1.6.1）—— 收藏页同款排序 + 回顶 + 排序键字段 + 再修闪封面。
    //     · 收藏页排序改成与作品页 / 媒体库同一套（↑↓ 箭头、再点反转方向、随机排序、方向落盘
    //       embyFavoritesSortAsc），空态提示同样改成叠在网格上层而不是替换网格。
    //     · 排序 / 随机切换后**滚动回第一条**（两页都加）：翻到第一百部再切排序，
    //       视口停在原处看到的完全是另一批内容，会让人以为「排序没生效」。
    //     · ⭐ 修「只有名称排序和随机排序有效」：年份 / 评分 / 添加时间这几个字段 Emby **默认
    //       不回传**（实测不带 Fields 时全是 null），而 ITEM_FIELDS 里也没有它们 ⇒ 本地排序的
    //       键值全是 null、稳定排序退化成原顺序。新增 ITEM_FIELDS_WITH_SORT_KEYS 供
    //       「要本地排序」的列表（收藏、作品页）索取这些字段。
    //       排序键为 null 的条目改为**沉底**（不再当 0 处理，否则升序时全顶在最前面）。
    //     · 再修「搜索时封面一闪而过」：produceState 的 state 不随 key 重建 ⇒ Lazy 列表槽位
    //       被复用给另一个条目时，会先画出**上一个条目的封面**再换（真正的“一闪而过”）。
    //       key 变化时立刻把值换成新 url 的缓存快照；占位图标再给 100ms 宽限期，
    //       缓存 / 磁盘命中时一次都不闪。
    // ────────────────────────────────────────────────────────────────────────
    // v125（versionCode 125，versionName 仍为 1.6.1）—— 修「一进收藏页就弹 没有匹配「」的收藏」。
    //     现象：冷启动进收藏页（上次停在演员 / 类型 / 工作室 tab）提示**空搜索词**的「没有匹配」，
    //     网格也是空的。不是「tab 没恢复」—— 恰恰是恢复到非「影片」tab 才暴露出来。
    //     根因：`remember(items, searchQuery)` 拿 items 当 key，而这三份列表是
    //     mutableStateListOf —— **对象实例永远不变**，内容从空变成有数据时 key 没变 ⇒
    //     缓存死在首次算出的空列表。影片 tab 的 PagedList.items 每次都是新 List 实例，
    //     所以同一个 bug 表现成「只有某个 tab 坏」。
    //     修法：三份列表改成 mutableStateOf<List<...>>（整体替换，与 PagedList / 作品页统一）；
    //     空态提示再加一道 `searchQuery.isNotBlank()` 闸门，杜绝「空词说没匹配」。
    // ────────────────────────────────────────────────────────────────────────
    // v126（versionCode 126，versionName 仍为 1.6.1）—— 注释瘦身（无功能改动）。
    //     · 清理源码里冗长 / AI 味浓烈的注释（版本考古、过程叙事、复述代码、⚠️ 小作文），
    //       保留工程化内容（真坑警告、TODO/FIXME、许可证头、公开 API 文档）。
    //     · 另修 EmbySkeleton.kt 的混合编码缺陷（KDoc 里混了一段 GBK 字节）。
    //     · 已核验：58 个文件剥离注释后与改动前逐字节一致，代码零差异。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v127（versionCode 127，versionName 仍为 1.6.1）—— 三项用户反馈。
    //     · 媒体库「随机排序 + 下拉刷新后停在列表中间」：`scrollToItem(0)` 会把归位落在
    //       **旧数据**上执行完，新数据落位时 LazyGrid 又按 `lastKnownFirstItemKey` 把原来
    //       那屏的条目重新定位到它在新顺序里的位置（随机排序整副牌重洗，甩到中段）。
    //       4 处改用 `requestScrollToItem(0)`（挂到下一次 measure + 丢弃旧锚点 key）。
    //     · 媒体库「文件夹里的『全部』不穿透」：`isFolderDive + ALL` 原本走「内容原样」
    //       不递归，只铺这一层的 4 个目录。改为递归（Emby `Recursive=true` 一次请求返回
    //       整棵子树），同时用 `ExcludeItemTypes` + 本地过滤排掉容器本身，避免
    //       「4 个文件夹 + 它们下面几百个视频」混排。
    //     · 播放器「Emby 网络播放直接闪退」：403 / 404 死链交给 libmpv 会在 **native 层**
    //       崩掉（整个 App 消失，崩溃页都出不来）。改为进播放页先探测一次地址
    //       （`EmbyClient.probeStreamUrl`，Range 探针 2.5s 超时），明确不可播就**不调
    //       playFile**、改弹错误卡片；超时 / 网络异常仍照旧播放。加载层同时补了文字提示。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v128（versionCode 128，versionName 仍为 1.6.1）—— 播放前探测「零额外请求」返工。
    //     用户指出：v127 在播放页起播前多打了一次 `Range: bytes=0-0`，网盘 / CDN 有风控，
    //     这条请求等于每次起播都多一次连接。返工原则是**一次都不新增**。
    //     · 判定合并进已有的预解析请求：`EmbyClient.resolveStreamUrl` 本来就会发同样一发
    //       （跟 302 拿直链），以前只丢掉了 `resp.code`。现在改成返回 `StreamProbe`
    //       （Ok / Denied / Unknown），401·403·404·410 判死并**立即返回不重试**
    //       （重试注定还是 4xx，只会多打一次 CDN）。
    //     · 顺带修掉一处原本就存在的浪费：单片播放路径以前**不管容器是不是原盘**都会为
    //       「ISO 探测」发一次 302 解析（请求在 `prepareRemoteIso` 的容器预筛之前）。
    //       现在先按容器名筛（`mayBeIsoContainer`），普通 mkv / mp4 起播**零请求**，
    //       只有 iso / strm / 容器未知时才发 —— 而 strm 恰好是最需要判死链的一类。
    //     · 播放页起播不再探测（`startPlayback` 直接 playFile）；判定结果由 Emby 侧经
    //       `play_error` extra 带进播放页显示成错误卡片。探测能力只保留给「重试」
    //       （`retryPlayback`，用户主动触发最多 1 次）。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v129（versionCode 129，versionName 仍为 1.6.1）—— Cine 界面 M2 + M3（媒体页）。
    //     M1 只重做了首页，其余四个 tab 全回落 Classic。这一版把「媒体」相关的页面
    //     全部换成 Cine：「首页 → 媒体库 → 详情 → 播放」主流程全程新界面。
    //     · 新增 `CineRoutes.kt`：在 `MainActivity` 的 `entryProvider` 里做渲染分流，
    //       **只登记已重做的路由**，其余原样 `route.Content()` 回落 —— 这是分期发版的根。
    //     · 媒体库（`ui/cine/library`）：分类 Chip（全部 / 类型 / 收藏）+ 统计与排序行 +
    //       2 列海报流 / 列表模式 + 分页 + 文件夹下钻 + 长按操作单。
    //     · 详情（`ui/cine/detail`）：Backdrop（屏高 45%）+ 上滑信息面板（压 18dp、
    //       上沿 26dp）→ 标题 / 元信息 / 角标 / 评分（tertiary）/ 播放与续播 /
    //       收藏 / 简介 / 演职员 / 按季分集 / 相似推荐。
    //     · 收藏 / 历史（`ui/cine/list`）：影片与演员两栏收藏、历史海报墙带进度。
    //     · token 补 `secondaryBarHeight`、`pageBarTitle`、`detailScrim`、`ratingAccent`。
    //     · 本地 / 网络仍回落 Classic：它们是文件系统页面（连接表单、协议浏览），
    //       重画收益低于风险，按方案第 12 类处理。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v130（versionCode 130，versionName 仍为 1.6.1）—— v129 详情页崩溃修复。
    //     现象：Cine 模式下点开任何条目，详情页第一帧就闪退。
    //     堆栈：`IllegalStateException: CineColors 未提供：Cine 组件必须包在 CineTheme { } 内`，
    //     帧上依次是 `CineRoute → CineDetailScreen`。
    //     真因：`CineTheme` 在 `MainScreen` 里**只包住了底部 tab 的 `CineRoot`**；
    //     详情页 / 媒体库页是独立导航 entry，由 `NavDisplay` 直接渲染，不在 `CineRoot` 子树内，
    //     所以拿不到 `LocalCineColors` / `LocalCineTypography` / `LocalCineSpacing`。
    //     修法：把 `CineTheme` 收进 `CineRoute` 的每个 Cine 分支（路由表是唯一渲染入口，
    //     以后新增 Cine 页也自动带上主题）；Classic 回落分支**不包**主题，保持原字阶。
    //     · 媒体库页同一个坑（同样走 `CineRoute`），一并修好。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v131（versionCode 131，versionName 仍为 1.6.1）—— 单击进详情 + 玻璃长按菜单。
    //     用户要求：保留 Cine 的玻璃质感，把老 UI 的长按弹窗以玻璃形式移植过来；
    //     单击改为进详情页，不再直接播放。**老 UI 一个文件都不动**（只新增 cine 文件与文案）。
    //     · 新增 `ui/cine/actions/CineActionSheet.kt`：玻璃底 ModalBottomSheet
    //       （containerColor = glassSurface + 顶角 26dp + 自绘拖拽条），头部是
    //       「缩略图 + 标题 + 元信息」，动作按「播放 / 收藏与状态 / 相关 / 维护 / 危险」分组；
    //       二级表单（刮削 / 刷新 / 编辑元数据 / 编辑图片）直接复用 Classic 的四个公开对话框，
    //       删除保留居中确认框（不可逆操作需要阻断式确认）。
    //     · 状态与渲染分离：`CineActionSheetState`（页面 remember 一份）+ `CineActionSheetHost`。
    //     · 接线：首页「最新加入 / 随便看看」、媒体库（网格 + 列表）、收藏、历史 ——
    //       单击一律进详情，长按弹菜单；文件夹单击仍是下钻。
    //     · 继续观看（16:9 卡）例外：单击=续播（这一行的意义就是接着看），详情走长按菜单。
    //     · `CineMediaListRow` 之前把 `onLongClick` 收下了却没用（只挂了 clickable），
    //       这版改 combinedClickable，列表模式长按才真的出菜单。
    //     · 写服务器的动作统一挂 `viewModel.viewModelScope`（菜单关掉界面作用域就没了，
    //       用 rememberCoroutineScope 会把「服务端已生效」误报成失败）。
    // ────────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────
    // v132（versionCode 132，versionName 仍为 1.6.1）—— Cine 观感对齐设计稿。
    //     用户反馈：真机上「跟设计稿感觉差很大」，点名「配色和氛围不对」+「排版密度和字号」。
    //     逐条比对 docs/design/ui-2.0-mockup.html 的 CSS 后发现三处**实打实的缺失**：
    //     1) 玻璃没有模糊。设计稿 `.dock{backdrop-filter:blur(22px) saturate(1.5)}`，
    //        全稿共 13 处 backdrop-filter；v126~v131 只做了半透明色 + 1dp 描边，
    //        Compose 没有 backdrop-filter 等价物，于是「玻璃」退化成一条色带。
    //        → 引入 `dev.chrisbanes.haze:haze:1.7.3`（**只被 ui/cine 使用**），
    //          新增 `ui/cine/components/CineGlass.kt`：`Modifier.cineGlass(shape, elevation, blur, tint)`
    //          = Haze 真模糊 + 投影 + 顶边渐隐高光 + 1dp 描边，一处实现、全局复用。
    //          ⚠️ 2.x 把 API 重写成 HazeEffectFactory/HazeInput，调用方要实现渲染器，故停在 1.7.3。
    //     2) 没有投影。设计稿 Dock 是 `0 14px 34px rgba(0,0,0,.42)` + `0 1px 0 rgba(255,255,255,.05) inset`，
    //        折叠顶栏 `0 8px 26px`，主按钮 `0 8px 22px`。v131 的 Dock / Rail / 顶栏 / 搜索胶囊
    //        **一个 elevation 都没有**，所以浮层是「贴」在内容上的。
    //     3) 配色被动态取色污染。Cine 之前完全从 colorScheme 派生，动态取色会把 surface
    //        染成壁纸色、且把 surface 与 surfaceContainer 的明度差压平 ——
    //        设计稿「近黑底 + 四档容器」的质感直接消失。现在 `CineTheme` 局部提供
    //        `cineColorScheme()`：中性阶梯取设计稿定值（#0D1014 / #12161C / #171D25 / #1E252F…），
    //        **primary / tertiary / error 仍跟随用户主题**。回落 Classic 的分支不包主题，
    //        老 UI 一个像素不变。
    //     · 排版密度按设计稿逐项校正：搜索胶囊 40→44dp、Dock 选中文字 12.5→13sp、
    //       Rail 间距 4→6dp 且项高 62dp、Hero 主按钮补 8dp 投影。
    //     · 折叠顶栏从「满宽贴边栏」改成设计稿的**浮起 18dp 圆角条**（左右 14dp、`0 8 26` 投影）。
    //
    //  ── v133 ──────────────────────────────────────────────────────────────
    //     · 侧边抽屉（CineDrawer）：老 UI AppBar 里的下载 / 离线 / 直播 / 播放列表 /
    //       猜你喜欢 / 合集 / Emby 维护 / 设置全部收进玻璃抽屉，首页顶栏左上角菜单图标弹出。
    //       下载项带活跃任务角标（与 Classic 顶栏同一份任务队列）。顶栏只留「菜单 + 搜索」。
    //
    //  ── v134 ──────────────────────────────────────────────────────────────
    //     · 详情页版式重做（CineDetailHero / CineEpisodeRail）：剧照底部渐隐融入底色、
    //       去掉 26dp 圆角纸片；标题（ClearLogo 优先 / 退文字）、评分+分级、技术规格、
    //       类型行全部居中；主按钮改「反相实心全宽胶囊」（onSurface 底 + 内高光 + 投影）；
    //       已看/收藏降为两个居中玻璃描边圆钮；简介 3 行。
    //     · 技术规格行新增数据源：MediaSources 的帧率 / 文件大小 / SDR 兜底，PremiereDate 格式化。
    //     · 分集区重做：季下拉 + 正/倒序切换 + 集号胶囊条（自动滚到续播集）+
    //       横滑 16:9 大卡（描边 / 进度条 / 已看角标）。替代原竖向长列表（376 集滑不动）。
    //     · 详情页「更多」补齐 Classic 全部功能：下载（含状态/暂停/继续）、标记为不喜欢、
    //       外部播放器，复用 CineActionSheetHost（fromDetail 隐藏「详情」项）。
    //     · 投屏搬到详情页：Hero 第三颗玻璃圆钮（Cast），抽屉是新写的 CineCastSheet
    //       （半透明玻璃面板 + 压暗 scrim），状态与动作共用 DlnaCastManager 单例。
    //
    //  ── v135（versionCode 135，versionName 涨到 1.6.3）────────────────────
    //     · 详情页「更多」菜单**文字颜色不齐**：收藏 / 标记为不喜欢 在未激活时
    //       把 `onSurfaceVariant` 当 tint 传下去，而 tint 是「文字 + 图标」共用的一档色，
    //       于是这两行的字比别的行灰一档。改成未激活传 null（文字回到 onSurface，
    //       只有图标自己降一档）。
    //     · 本地 / 网络 两个 tab 上浮起的 Dock **没有模糊**：这两个 tab 仍是 Classic
    //       整页实现，不会把自己标成 Haze 模糊源，玻璃层取不到底就退化成半透明平涂。
    //       在 CineRoot 里用 CineHazeBacked 给回落页补一层 cineHazeSource，老页面零改动。
    // ────────────────────────────────────────────────────────────────────────
    //  ── v136（versionCode 136，versionName 涨到 1.6.4）：Cine 第三阶段，六个页面全部迁完 ──
    //     · 演员 / 导演作品页 → `CinePersonScreen`：玻璃顶栏 + 头像圆章 + 类型/身份两行
    //       Chip + 排序抽屉（名称/年份/评分/最新/随机，再点反转）；收藏红心仍走
    //       `loadPersonFavoriteState` 的三层兜底，数据口径零改动。
    //     · 直播电视 → `CineLiveTvScreen` + `CineLiveTvGuideScreen`：频道瓦片（16:9 台标 +
    //       频道号 + 右上角节目单圆钮）、24 小时节目单按「今天/明天」分组、正在播出
    //       带主色底与细进度条，「现在」每 30 秒自走一步。
    //     · 下载管理 → `CineDownloadScreen`：玻璃任务卡 + 自绘进度条（未知总量走来回扫），
    //       暂停/继续/置顶/取消/清除已完成与 Classic 同一套 `EmbyDownloadViewModel`。
    //     · 服务器管理 → `CineServerManageScreen`：玻璃卡 + 三态状态徽章 + 玻璃上划表单，
    //       保留「添加时先登录成功才落库」与「加用户锁地址」两道闸门。
    //     · 本地 → `CineLocalScreen`：相册视图改 Cine 文件夹瓦片/行（新增计数角标、搜索、
    //       排序抽屉、网格/列表切换、长按隐藏文件夹）；树形文件管理器模式整页沿用 Classic
    //       （另一套导航范式），外套一层模糊源。Classic FAB 的「打开文件/最近播放/打开链接」
    //       收进顶栏「更多」抽屉。
    //     · 网络 → `CineNetworkScreen`：直链面板 + 收藏目录横排卡 + 连接卡（协议徽标/状态/
    //       连接·断开·置顶·编辑·删除）；目录浏览仍走 `NetworkBrowserScreen`（协议栈不动）。
    //     · `CineRoutes` 现在登记 7 条路由：详情 / 媒体库 / 演员作品 / 直播列表 / 节目单 /
    //       下载 / 服务器管理。回落只剩合集、播放列表、发现、离线、照片、网络目录浏览。
    //     · 新增共享组件 `components/CineFileTiles.kt`（玻璃面板 / 图下标题瓦片 / 横排行 /
    //       胶囊搜索框 / Dock 占位），四个非 Emby 页面共用，不再各写一套。
    //     ⚠️ 坑记录：`ui/cine/**` 这种写法在 KDoc 里会**再开一层块注释**（Kotlin 块注释可
    //        嵌套）⇒ 报「Syntax error: Unclosed comment」在文件末尾，连带该文件所有符号
    //        Unresolved。注释里不要出现 `/**`。
    // ────────────────────────────────────────────────────────────────────────
    //  ── v137（versionCode 137，versionName 涨到 1.6.5）：网络位置随机排序回顶 ─────────
    //     · 用户：「网络位置随机排序下拉又出现那个 item(0) 的问题了」。v127 的
    //       `requestScrollToItem(0)` 只补了 `EmbyLibraryScreen`，网络目录浏览页
    //       （`NetworkBrowserScreen`）还留着 `scrollToItem(0)` ⇒ 切随机 / 换一批
    //       之后画面停在列表中间（同一类 LazyGrid 按 key 重锚定）。
    //     · 随机排序下**下拉刷新**从来没有回顶：`loadFiles` 只换了随机种子（重洗
    //       整副牌），滚动位置原样保留。现在新列表落地之后给排序计数 +1，UI 用
    //       `requestScrollToItem(0)` 回顶（信号必须在数据落地之后发，否则定位请求
    //       落在旧列表上、随后又被 key 锚回中段）。
    //     · 顺手修 Cine 演员作品页同款：`CinePersonScreen` 的排序 effect 也是
    //       `scrollToItem(0)`，切随机同样停在中段。
    // ────────────────────────────────────────────────────────────────────────
    // v138 / 1.6.6
    // 网络位置 → 视界流播放（两个 UI 都有入口）
    //   · 新增 `NetworkFeedLauncher`：递归抽片 + 地址解析，与主播放器队列共用
    //     `NetworkVideoUriBuilder`（原 `playVideos` 内联的三分支提取出来，不留两份）。
    //   · 经典：连接卡 ⋮ 菜单加「视界流播放」；目录浏览页 ⋮ 菜单加「视界流播放」。
    //     新 UI：Cine 连接卡动作行加一枚视界流图标（Cine 卡没有 ⋮ 菜单）。
    //   · ⋮ 菜单加勾选项「穿透子文件夹」（`networkRandomDrillDown`，默认关）——
    //     同时作用于「随机播放视频」与「视界流播放」，只影响网络模块。
    //   · 数量沿用统一的 `randomPlayCount`，不新增配置项。
    //   · 网盘视界流不绑 Emby 条目（libraryId = null）⇒ 收藏 / 不喜欢按钮不显示
    //     （`FeedViewModel.embyBound`），点了必然失败的按钮不该摆着。
    // ────────────────────────────────────────────────────────────────────────
    versionCode = 138
    versionName = "1.6.6"

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())

    // ── dandanplay 开放平台凭证（可选，**刻意不硬编码**）──
    // 为什么走 local.properties 而不是写死：
    //   官方文档明确不建议在客户端硬编码 AppSecret（源码一开源就等于公开）。
    //   所以这里是「构建期从本机文件读 → 注入 BuildConfig → 运行期由用户可覆盖」。
    //   local.properties 已被 .gitignore 忽略，仓库里永远不会出现密钥。
    // 没配 local.properties 也能编：两个字段为空串 ⇒ 运行时不下发签名头，
    // 弹幕只对「自建服务端 / 未开认证的兼容服务端」可用（这本身就是一条有效路径）。
    buildConfigField("String", "DANDAN_APP_ID", "\"${localDanmakuAppId}\"")
    buildConfigField("String", "DANDAN_APP_SECRET", "\"${localDanmakuAppSecret}\"")
  }

  flavorDimensions += "distribution"

  productFlavors {
    create("standard") {
      dimension = "distribution"
      isDefault = true
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
    }

    create("playstore") {
      dimension = "distribution"
      versionNameSuffix = "-playstore"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "true")
    }

    create("fdroid") {
      dimension = "distribution"
      versionNameSuffix = "-fdroid"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")

      ndk {
        // 与 splits 的 ABI 过滤互斥：单包模式（-Pabi / -Parm64Only）下必须留空，否则 AGP 直接报配置冲突
        if (singleAbi == null) abiFilters += "arm64-v8a"
      }
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  splits {
    abi {
      isEnable = true
      reset()
      if (singleAbi != null) {
        include(singleAbi)
        isUniversalApk = false
      } else {
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        isUniversalApk = true
      }
    }
  }

  // ── Release 签名 ──
  // 私钥路径与口令统一放在仓库根的 keystore.properties（.gitignore 已屏蔽），
  // 避免把口令硬编码进构建脚本。该文件缺失时（例如 CI 只跑 assembleDebug / lint）
  // 不抛异常，仅表现为 release 未签名，不让「有没有签名」变成「能不能构建」的硬依赖。
  val keystorePropsFile = rootProject.file("keystore.properties")
  val keystoreProps = Properties()
  if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
  }
  val releaseKeystoreFile: File? =
    keystoreProps.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
  val hasReleaseKeystore = releaseKeystoreFile?.exists() == true

  if (!hasReleaseKeystore) {
    logger.lifecycle(
      "[CineIsle] 未找到 keystore.properties 或 jks 文件，release 变体将产出未签名 APK。"
    )
  }

  signingConfigs {
    if (hasReleaseKeystore) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
        // minSdk 26 → V2 已全线支持，无需 V1（省掉 META-INF 里的冗余签名文件）。
        enableV1Signing = false
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    named("release") {
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      ndk {
        debugSymbolLevel = "none"
      }
    }

    create("preview") {
      initWith(getByName("release"))
      signingConfig = null
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-${getCommitCount()}"
    }

    named("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
  }

  // ── 测试（清单 1.2）──
  sourceSets {
    getByName("androidTest") {
      // Room 迁移测试靠 MigrationTestHelper 从 **assets** 里读导出的 schema
      // （路径固定为 schema/<canonicalName 首字母小写>/<version>.json，
      //   本项目即 schema/app.marlboroadvance.mpvex.database.mpvExDatabase/12.json）。
      // 不把 $projectDir/schemas 挂进 androidTest assets 的话，
      // createDatabase()/runMigrationsAndValidate() 会全部抛
      //「Unable to find schema file」—— 测试代码写得再对也一条都跑不了。
      assets.srcDirs("$projectDir/schemas")
    }
  }

  testOptions {
    unitTests {
      // Robolectric 需要 android.jar 的全部资源（不只是桩方法），
      // 不开这个它读不到主题/字符串，纯逻辑测试也会在初始化时炸。
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  // ── Lint（清单 1.2）──
  // 规则集本身在 app/lint.xml 里（致命项 error、存量噪音降级 warning、误报 ignore）。
  lint {
    // 静态检查出错就中断构建 —— 前提是 lint.xml 已把存量噪音降级，
    // 否则第一次跑就会被几百条历史问题卡住，配置也就白配了。
    abortOnError = true
    // 警告不阻断出包，但会写进报告供体检用。
    warningsAsErrors = false
    checkDependencies = false
    // 项目里大量日志用的是 android.util.Log 而不是封装类，
    // 这条与本项目现状相悖，直接关掉免得刷屏。
    disable += "LogDetector"
    // release 变体的 lint 检查很慢（实测 lintVitalAnalyze 要 7 分钟），
    // 日常出包关掉；上线前体检加 -PlintVital=true 跑一次。
    // 与下面那段 tasks.matching { name.startsWith("lintVital") } 开关是同一件事，
    // 这里从 lint 配置侧再关一次，保证不带参数时任何路径都不会误触发。
    checkReleaseBuilds = lintVitalOn
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE*"
      excludes += "META-INF/NOTICE*"
      excludes += "META-INF/*.kotlin_module"
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    jniLibs {
      useLegacyPackaging = true
    }
  }

  @Suppress("UnstableApiUsage")
  androidResources {
    generateLocaleConfig = true
  }
}

// ── 构建提速：默认关掉 release 的 lintVital ──
//
// 实测（本机 6 核 i7-8750H）assembleStandardRelease 任务耗时分布：
//   lintVitalAnalyzeStandardRelease   7m02s  ← 比 R8 还慢
//   minifyStandardReleaseWithR8       6m31s
//   compileJavaWithJavac                54s
//   其余全部                          ~3m
// lintVital 是 AGP 给 release 变体加的「致命问题」静态检查，跑在 R8 之后、
// 且几乎吃不掉并行度（它和 R8 抢 CPU），是本机构建最大的一块纯开销。
// 日常出包关掉它；要上线前体检时加 -PlintVital=true 跑一次即可。
// 值复用 android {} 之前声明的 lintVitalOn（那里 lint { checkReleaseBuilds } 也要用），
// 避免同一个开关在两处各解析一遍。
tasks.matching { it.name.startsWith("lintVital") }.configureEach {
  enabled = lintVitalOn
}

androidComponents {
  val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4
  )

  onVariants { variant ->
    variant.outputs.forEach { output ->
      val abi = output.filters
        .find { it.filterType == FilterConfiguration.FilterType.ABI }
        ?.identifier

      output.versionCode.set(
        (output.versionCode.orNull ?: 0) * 10 + (abiCodes[abi] ?: 0)
      )
    }
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-Xcontext-parameters",
      "-Xannotation-default-target=param-property",
      "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      // 视界流翻页要用 VerticalPager（来自 androidx.compose.foundation）
      "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    )
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

composeCompiler {
  // 源信息（把重组对应回源码行号）只有 debug 的 Compose 工具链用得上。
  // release 打开会给每个 composable 塞额外字符串元数据：Compose 编译器更慢、
  // R8 要处理的代码量更大（R8 占 release 构建 6m31s），产物也更大。
  includeSourceInformation = false
}

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.activity.compose)
  // 应用锁判「是不是真的退出了 App」用 ProcessLifecycleOwner（进程级生命周期）——
  // 它不在 activity-compose 的传递依赖里，必须显式声明；版本与 lifecycle-runtime 对齐（2.10.0）。
  implementation("androidx.lifecycle:lifecycle-process:2.10.0")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation("com.google.android.material:material:1.13.0")
  implementation(libs.androidx.compose.material)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.constraintlayout)
  implementation("androidx.preference:preference-ktx:1.2.1")
  implementation("androidx.constraintlayout:constraintlayout:2.2.0")
  implementation(libs.androidx.material3.icons.extended)
  implementation(libs.androidx.compose.animation.graphics)
  // 视界流（仿抖音竖屏连播）：ExoPlayer 多实例 + Compose VerticalPager。
  // foundation 显式声明是为了 VerticalPager —— 原来它是靠 ui 传递进来的，
  // 现在翻页是视界流的核心路径，不该依赖传递关系。
  implementation(libs.androidx.compose.foundation)
  // 背景模糊（Cine 玻璃层）。Compose 官方只有 `Modifier.blur()`（模糊自己），
  // 没有 CSS `backdrop-filter` 的等价物 —— 设计稿里 Dock / 顶栏 / 长按菜单那种
  // 「透出下面内容并磨砂」的效果只能靠它。
  // ⚠️ 只在 ui/cine/** 里用；Classic 界面零引用，所以对老 UI 的实际影响为 0。
  implementation(libs.haze)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.mediasession)
  implementation(libs.androidx.documentfile)
  implementation(libs.saveable)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)
  implementation(libs.aboutlibraries.compose.m3)

  implementation(libs.accompanist.permissions)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)

  // 弹幕：DanmakuFlameMaster **源码内置**（v115）—— 源码在 app/src/main/java/master/flame/danmaku/
  // （+ tv/cjump/jni/），解析仍走我们自己的 parser（见 domain/danmaku/）。
  // 为什么内置：要修引擎自身缺陷（v115：滚动速度系数与存活寿命同源、计时器高刷屏超前），
  // 详见 2026-10-19.md v115 段与 DanmakuOverlay.kt 类头说明。
  // 原先的 AAR 依赖 `com.github.ctiao:dfm:0.9.25` 已移除；proguard 里补了 keep 规则。

  // Lottie：加载动画可换（外观 → 加载动画）。搬的是 Yamby 内置的那 12 个 json（res/raw/yamby_*.json）
  // + 自研的两套，用 lottie-compose 在 Compose 里渲染。
  implementation(libs.lottie.compose)
  // Telephoto：照片浏览器（图集）的捏合缩放 / 拖动平移 / 双击放大手势。
  // 只依赖核心 zoomable 模块 —— 手势与「图片怎么加载」是两件事，图片交给下面的 Coil。
  implementation(libs.telephoto.zoomable)
  // Coil 3：照片链路（浏览器 + 照片网格卡）的图片渲染层。
  // 字节仍然由项目自己的 EmbyImageLoader 取（节流 + 双鉴权 + 缩放失败退回原图），
  // 交给 Coil 的是「解码 / 采样 / 缓存 / 取消」，
  // 见 ui/browser/emby/photo/EmbyPhotoImageLoader.kt。
  // ⚠️ 刻意**没有**引官方的 coil-network-okhttp：那套网络实现不知道 Emby 的鉴权，
  //    引了还要跟我们自己的 Fetcher 抢 String 类型的请求。
  implementation(libs.coil.compose)
  // DLNA 投屏：UPnPCast 源码内置在 app/src/main/java/com/yinnho/upnpcast/ 下（同 embyclient 的做法），
  // 版本为上游 master / v1.3.0。
  // 为什么不用 Maven Central 的 com.yinnho.upnpcast:upnpcast:1.1.2：
  //   1.1.2 的 SSDP 有致命缺陷 —— SO_REUSEADDR 在 bind 之后才设置（等于没设）、1900 端口被占就再
  //   也搜不到设备、去重表跨搜索不清空（第二次搜索起永远返回空列表，必须重启 App）、
  //   search() 一发现首个设备就返回导致列表不全、location 无端口时拼出 http://host:-1/...、
  //   本地文件一律用 application/octet-stream（部分电视直接拒收）。
  //   这些正是 1.2.0 的修复项（upstream CHANGELOG 逐条对应：discovery #1 / MulticastLock /
  //   Control URL / search() semantics / MIME by extension）。1.3.0 只上了 JitPack，
  //   Maven Central 仍停留在 1.1.2，所以直接内置源码。
  // 运行时依赖：kotlinx.coroutines + nanohttpd（均已在本文件声明）。

    implementation(libs.truetype.parser)
    implementation(libs.fsaf)
    implementation(libs.mediainfo.lib)
    implementation(libs.mpv.android)

    // GSYVideoPlayer —— 双播放内核中的第二个内核（默认仍是 mpv）
    // java = 播放器 View + IJK Java 绑定；exo2 = GSY 的 ExoPlayer 内核；
    // arm64 / armv7a / x86 / x64 = IJK 的原生 so（按 ABI 拆包，缺了 IJK 起不来）。
    implementation(libs.gsy.java)
    implementation(libs.gsy.exo2)
    implementation(libs.gsy.arm64)
    implementation(libs.gsy.armv7a)
    implementation(libs.gsy.x86)
    implementation(libs.gsy.x64)

    // Network protocol libraries
    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.sardine.android) {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.nanohttpd)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.reorderable)

    // ── Emby Java SDK (OpenAPI 生成) ──
    // 源码 `app/src/main/java/embyclient/` 已从 sourceSet 排除（见上方 android{} 里的说明），
    // 它原本带来的 OkHttp 2 + Gson + gson-fire + swagger-annotations 依赖**全部移除**：
    //   - OkHttp 2 (`com.squareup.okhttp`) 早已 EOL，且与主用的 OkHttp 5 并存
    //     （OkHttp 5 的 Maven 坐标仍是 `com.squareup.okhttp3`，包名也仍是 `okhttp3.*`）；
    //   - Gson 同理，主项目统一用 kotlinx.serialization；
    //   - 主项目源码里 grep 不到 gson / swagger / javax.annotation 的任何引用。
    // 端点路径与参数仍可查 embyclient/ 源码（不参与编译，只作参考）。
    // javax.annotation.* 在 Android 上缺失，若将来真要启用那份 SDK 再补 jsr250-api。

    // ── 测试依赖（清单 1.2）──
    // 之前项目**一个测试都没有**（app/src/test 与 androidTest 目录都不存在），
    // 11 段手写 Room 迁移零覆盖 —— 这是全项目最高的风险点。
    //
    // 分工：
    //   testImplementation  → JVM 单测（分页状态机、错误分类、正则筛选、排序…），秒级
    //   androidTestImplementation → Room 迁移测试（MigrationTestHelper 需要真 SQLite）
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.room.testing)
}

/*
 * ── 关于 Emby Java SDK（OpenAPI 生成）目录 `app/src/main/java/embyclient/` ──
 *
 * 它**已从源码目录移出**到 `app/embyclient-reference/`，故不再参与编译，
 * 上面那批 OkHttp 2 + Gson + gson-fire + swagger-annotations 依赖才能全部移除。
 *
 * 移出理由（全部已核实，不是猜测）：
 *   - 全项目零引用：`Grep "^import embyclient"` 在 mpvex 下无命中。实际请求全部走
 *     `domain/emby/EmbyClient.kt` 手写的 OkHttp 5 + kotlinx.serialization。
 *   - OkHttp 2 (`com.squareup.okhttp`) 早已 EOL，却和主用的 OkHttp 5 并存 ——
 *     后者坐标仍是 `com.squareup.okhttp3`，包体、混淆、依赖冲突三重风险。
 *   - Gson 与主项目统一的 kotlinx.serialization 重复；主项目源码里 grep 不到
 *     gson / swagger / javax.annotation 的任何引用。
 *
 * ⚠️ 源码没删，只是挪了位置 —— 查某个 Emby 端点的确切路径 / 参数名时，
 * 去 `app/embyclient-reference/` 照抄到 EmbyClient 手写即可，这本来就是生成它的用途。
 * 新加的功能（播放列表 / 字幕下载 / 相似推荐 / 虚拟库等）都按这个流程做。
 */


/* ---------------- Git helpers ---------------- */

/**
 * 本机可用的 git 可执行文件（找不到就是 null）。
 *
 * 为什么要先找绝对路径：本机 git **不在 PATH 上**（只有 GitHub Desktop 自带的那份），
 * 直接 exec "git" 必然起不来；而配置期起外部进程失败会让 Gradle 9 判定配置缓存无效
 * （实测报 "failed to compute value with custom source ... starting process 'command 'git''"，
 * 缓存每次被丢弃）。所以只在**确认文件存在**时才真的调用。
 */
fun findGitExecutable(): String? {
  val candidates = mutableListOf<String>()
  System.getenv("GIT_EXE")?.let(candidates::add)
  val ghRoot = System.getenv("LOCALAPPDATA")?.let { File(it, "GitHubDesktop") }
  ghRoot
    ?.listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("app-") }
    ?.forEach { appDir ->
      candidates += File(appDir, "resources/app/git/cmd/git.exe").absolutePath
    }
  return candidates.firstOrNull { File(it).exists() }
}

/** 提交数（用作 versionCode / debug 版本号后缀）；拿不到就退回 0 */
fun getCommitCount(): String = runGit("rev-list", "--count", "HEAD") ?: "0"

/** 短提交号（写进 BuildConfig.GIT_SHA）；拿不到就退回 unknown */
fun getCommitSha(): String = runGit("rev-parse", "--short", "HEAD") ?: "unknown"

/**
 * 从本机 `local.properties` 读一个键；读不到返回空串。
 *
 * ⚠️ 只在**值看起来合法**时才返回 —— 它要进 `buildConfigField` 的字符串字面量，
 * 一个没转义的 `"` 或 `\` 会让生成的 BuildConfig.java 语法错误，
 * 而报错信息指向 BuildConfig、完全不提local.properties，极难定位。
 */
fun localSecret(key: String): String {
  val file = rootProject.file("local.properties")
  if (!file.exists()) return ""
  return try {
    val line = file.readLines()
      .map { it.trim() }
      .firstOrNull { it.startsWith("$key=") }
      ?.substringAfter('=')
      ?.trim()
      .orEmpty()
    // 只放行字母数字与下划线：AppId/AppSecret 本来就只由这些字符组成。
    if (line.isNotEmpty() && line.all { it.isLetterOrDigit() || it == '_' }) line else ""
  } catch (e: Exception) {
    ""
  }
}

val localDanmakuAppId: String = localSecret("dandan.appId")
val localDanmakuAppSecret: String = localSecret("dandan.appSecret")

/**
 * 用 providers.exec 而不是 ProcessBuilder 起 git。
 *
 * providers.exec 会被 Gradle 当构建输入跟踪，配置缓存能正常命中；
 * 而 ProcessBuilder 属于「配置期外部进程」，Gradle 9 直接判缓存无效。
 */
fun runGit(vararg args: String): String? {
  val exe = findGitExecutable() ?: return null
  return try {
    providers.exec {
      commandLine(listOf(exe) + args)
      // 不在 git 仓库时不要让构建失败
      isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
  } catch (e: Exception) {
    null
  }
}
