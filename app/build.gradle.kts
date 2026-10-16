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
    versionCode = 72
    versionName = "1.5.5"

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

  // 弹幕：只取 View + 数据模型，解析走我们自己的 parser（见 domain/danmaku/）。
  // 这个 AAR 里没有 .so，纯 Java，所以不需要 ndkbitmap-* 的分 ABI 依赖。
  implementation(libs.dfm)

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
    // 它原本带来的 OkHttp2 + Gson + gson-fire + swagger-annotations 依赖**全部移除**：
    //   - OkHttp2 (com.squareup.okhttp) 早已 EOL，且与主用的 OkHttp3 (okhttp3.*) 并存；
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
 * 上面那批 OkHttp2 + Gson + gson-fire + swagger-annotations 依赖才能全部移除。
 *
 * 移出理由（全部已核实，不是猜测）：
 *   - 全项目零引用：`Grep "^import embyclient"` 在 mpvex 下无命中。实际请求全部走
 *     `domain/emby/EmbyClient.kt` 手写的 OkHttp3 + kotlinx.serialization。
 *   - OkHttp2 (`com.squareup.okhttp`) 早已 EOL，却和主用的 OkHttp3 (okhttp3.*) 并存 ——
 *     包体、混淆、依赖冲突三重风险。
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
