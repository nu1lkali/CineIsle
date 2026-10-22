<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="影屿 · CineIsle" />
</p>

<h1 align="center">影屿 · CineIsle</h1>

<p align="center">
  <b>一个打通 Emby 的本地播放器 —— 基于 mpvEx 二次开发</b>
</p>

<p align="center">
  <a href="https://developer.android.com"><img src=".github/badges/platform-android.svg" alt="Platform: Android" /></a>
  <a href="https://developer.android.com"><img src=".github/badges/minsdk-26.svg" alt="minSdk: 26" /></a>
  <a href="https://kotlinlang.org"><img src=".github/badges/kotlin-2.3.20.svg" alt="Kotlin: 2.3.20" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src=".github/badges/compose-material3.svg" alt="Compose: Material 3" /></a>
  <a href="LICENSE"><img src=".github/badges/license-apache2.svg" alt="License: Apache-2.0" /></a>
</p>

<p align="center">
  <a href="https://github.com/nu1lkali/CineIsle/releases"><img src=".github/badges/release-1.6.1.svg" alt="Release: v1.6.1" /></a>
  <img src=".github/badges/flavor-standard.svg" alt="Flavor: standard" />
  <img src=".github/badges/package-cineisle.svg" alt="Package: app.cineisle.player" />
  <img src=".github/badges/kernel-mpv-gsy.svg" alt="Kernel: mpv + GSYVideoPlayer" />
</p>

<p align="center">
  <a href="#项目背景">项目背景</a> ·
  <a href="#技术栈">技术栈</a> ·
  <a href="#模块">模块</a> ·
  <a href="#功能">功能</a> ·
  <a href="#更新记录">更新记录</a> ·
  <a href="#致谢">致谢</a>
</p>

<hr />

<p align="center">
  🎬 直链播放不转码 · 📥 离线下载 · 🖥️ mpv / GSY 双内核 · ☁️ AList 网盘 ·
  📀 远程 ISO 原盘 · 🧑 演职员合并 · 📺 DLNA 投屏 · 🖼️ 图集与照片
</p>

<!-- 截图：三竖一横。三张竖屏并排铺满一整行（各 32.5%，等高）；横屏播放页整行居中，宽度与三张竖图总宽一致，视觉上成框 -->

<p align="center">
  <a href="https://img.cdn1.vip/i/6ad5640a2a11d_1792369674.webp"><img src="https://img.cdn1.vip/i/6ad5640a2a11d_1792369674.webp" width="32.5%" alt="首页与媒体库" /></a>
  <a href="https://img.cdn1.vip/i/6ad5640acfc4c_1792369674.webp"><img src="https://img.cdn1.vip/i/6ad5640acfc4c_1792369674.webp" width="32.5%" alt="媒体库详情" /></a>
  <a href="https://img.cdn1.vip/i/6ad56404e622b_1792369668.webp"><img src="https://img.cdn1.vip/i/6ad56404e622b_1792369668.webp" width="32.5%" alt="剧集详情页" /></a>

</p>

<p align="center">
  <a href="https://img.cdn1.vip/i/6ad56404f41e5_1792369668.webp"><img src="https://img.cdn1.vip/i/6ad56404f41e5_1792369668.webp" width="100%" alt="mpv 内核播放页" /></a>
</p>

<p align="center">
  <a href="https://img.cdn1.vip/i/6ad60927a712d_1792411943.webp"><img src="https://img.cdn1.vip/i/6ad60927a712d_1792411943.webp" width="100%" alt="弹幕渲染" /></a>
</p>

<p align="center">
  <img src=".github/badges/shots-caption.svg" alt="首页 · 媒体库 · 详情页 · 播放页" />
</p>

## 项目背景

一开始用的是某款第三方 Emby 播放器。用着还行，但总有些自己想要的功能它没有，于是去提需求——作者不太愿意做，也没怎么回复。

那没辙了，自己做呗。

从零写一个播放器内核不现实，所以找了一个足够扎实的播放器做底座：**[mpvEx](https://github.com/marlboro-advance/mpvEx)**（底层是 mpv-android / libmpv）。mpvEx 本身是一个好用的**本地**播放器，但不带 Emby 客户端。

于是就有了 **影屿 / CineIsle**：在 mpvEx 上补出一整套 Emby 客户端，再把那些「自己用得上」的功能一个个加上去。

播放器不能只播 Emby 里的片子，所以网络存储也补齐了：SMB / FTP / WebDAV，以及给 AList 单独写的一套原生 API 客户端——同一台 AList 用 WebDAV 也能连，但列目录、取封面、播放的请求次数差一个数量级，具体见[功能](#功能)。

名字取自「电影的岛屿」——把自己想看的片子收在一个地方。

---

## 技术栈

| 领域 | 选型 |
| :-- | :-- |
| **语言 / UI** | Kotlin · Jetpack Compose（Material 3 Expressive）· 单 Activity + Navigation 3 |
| **架构** | 分层（`domain` 领域 / `ui` 界面 / `database` 持久化 / `preferences` 设置）· Koin 依赖注入 · 协程 + Flow |
| **持久化** | Room 2.8（KSP 生成、schema 导出、迁移测试）· `me.zhanghai.compose.preference` |
| **网络** | OkHttp 5 + kotlinx.serialization（手写 Emby 客户端，以及 AList 原生 API 客户端）· SMBJ / Sardine / Commons Net（SMB / FTP / WebDAV）· NanoHTTPD（本地 Range 代理） |
| **播放** | libmpv（`mpv-android`）为主内核 · GSYVideoPlayer 13.2（IJK / System / ExoPlayer）为备选 · AndroidX Media3 ExoPlayer 1.10 承担视界流多实例播放 |
| **投屏** | UPnPCast（源码内置） |
| **加密** | Android Keystore AES-256-GCM |
| **构建** | AGP 9.4 + Gradle · Kotlin 2.3.20 · Compose BOM 2026.03 · JDK 17 · release 走 R8 混淆 + 资源裁剪 |
| **设备** | `minSdk 26` · `targetSdk 36` · `compileSdk 37` · flavor `standard` / `playstore` / `fdroid` |
| **测试** | JUnit4 + Robolectric + Truth（JVM 单测）· Room `MigrationTestHelper`（androidTest） |

---

## 模块

| 模块 | 职责 |
| :-- | :-- |
| `domain/emby` | Emby 客户端核心：手写 OkHttp 5 + kotlinx.serialization，覆盖条目 / 媒体库 / 搜索 / 播放列表 / 会话 / 演职员等端点，含分页游标、直链预解析、筛选与排序 |
| `domain/iso` | 远程 ISO 原盘播放：ISO9660 解析、蓝光 `mpls` 解析、段映射、HTTP Range 读取与本地代理 |
| `domain/dislike` | 「标记为不喜欢」的领域逻辑与持久化 |
| `domain/media` · `network` · `thumbnail` · `playbackstate` · `recentlyplayed` · `anime4k` | 媒体信息解析、网络存储、缩略图、播放进度、最近播放、Anime4K |
| `ui/browser/emby` | Emby 界面：首页、媒体库、详情页、搜索、筛选、播放列表、演职员合并、不喜欢管理页 |
| `ui/browser/networkstreaming` | 网络存储：`clients` 下四套协议客户端（SMB / FTP / WebDAV / AList 原生 API）、目录浏览、混合媒体列表、照片查看器、AList 写操作 |
| `ui/browser/filesystem` · `folderlist` · `playlist` · `videolist` · `recentlyplayed` | 本地文件浏览与各列表页 |
| `ui/player` | 播放页：`controls` 控件簇布局引擎、`engine` 双内核抽象、`gsy` GSY 内核、`feed` 视界流 |
| `ui/preferences` | 设置页（含设置搜索） |
| `database` | Room 实体 / DAO / 仓库，含迁移与 schema 导出 |
| `di` | Koin 依赖注入 |
| `presentation` | 通用组件与崩溃页 |
| `app`（外壳） | MainActivity：NavDisplay 导航栈、启动揭幕 |
| `preferences` | 设置项持久化 |
| `dlna` | DLNA 投屏 |
| `widget` | 桌面「继续观看」小组件 |
| `utils` | 通用工具 |
| `com/yinnho/upnpcast` | UPnPCast（源码内置） |
| `app/embyclient-reference` | Emby Java SDK（OpenAPI 生成的参考实现，**不参与编译**，仅供查询端点路径与参数） |

---

## 功能

### 🖥️ Emby 客户端（本项目从零实现）

| 功能区 | 要点 |
| :-- | :-- |
| **服务器与账号** | 多服务器添加 / 编辑 / 删除 / 切换；用户名 + 密码登录；配置存 Room，Token 失效自动重登 |
| **首页** | 媒体库入口卡片、继续观看（带进度）、最新加入、随便看看（可整块关掉）、全局搜索（400ms 防抖 + 类型筛选 + 历史 + 联想建议） |
| **媒体库浏览** | 分类（全部 / 继续播放 / 合集 / 收藏 / 文件夹 / 演员）、多种排序、三种卡片样式（海报 / 背景图 / 横幅）与 1~2 行标题、文件夹 2×2 宫格下钻、按当前屏幕结果随机播放、库内搜索；结果与滚动位置进程内缓存 |
| **图集与照片** | 照片库独立入口（相册 → 照片瀑布流，保留原图比例）；混合媒体库可切「仅媒体 / 全部 / 仅照片」；全屏浏览器支持双指缩放、幻灯片播放（间隔与是否自播可设）、相机参数信息、保存原图 / 另存 JPEG，HEIC 可看可存 |
| **筛选** | 类型 / 标签 / 年份 / 官方分级 / 评分 + 播放状态 / 字幕 / 只看收藏 / 中文字幕；面板内改动只是草稿，点「确定」才生效，条件按库各记一份 |
| **片单与推荐** | 播放列表（新建 / 增删 / 拖动排序）、猜你喜欢 · 连播（服务端或本地算法）、合集 |
| **聚合库** | 把同一服务器下多个同类型媒体库合并成本地视图：首页出卡片（Layers 图标 + 「N 库聚合」角标），归并网格深翻页不重不漏；随机播放 = 换一批；只在本 App 内生效，不修改服务器 |
| **详情页** | 可折叠的封面头部、简介展开 / 收起、演职员与同类型推荐、剧集按季集展开、媒体信息、收藏 / 已看 / 加入下载、更多菜单（刮削 / 刷新元数据 / 删除） |
| **播放与进度同步** | 直链播放 `stream?static=true` 不转码；剧集连播；进度回传 `/Sessions/Playing` · `/Progress` · `/Stopped`，多端互通 |
| **离线下载** | `Range` 断点续传；Android 10+ 经 `MediaStore` 写公共 `Download/CineIsle`（免存储权限）；最多同时下 2 个，任务持久化 |

### ☁️ 网络存储与 AList 网盘

支持 SMB / FTP / WebDAV 三种通用协议，外加 **AList 的原生 API**。同一台 AList 两种连法都能用，但原生协议值得单独做一套：列目录、取封面、播放的差别是数量级的。

| 功能区 | 要点 |
| :-- | :-- |
| **连接管理** | 添加 / 编辑 / 删除 / 置顶；连接列表可按名称、地址、协议搜索；密码统一 Keystore 加密落盘；可填子路径（base path），HTTP 类协议可选 `https` |
| **目录浏览** | 文件夹 / 图片 / 视频 / 其它文件混排，各带类型角标；点顶栏标题弹出层级列表，可直达任意一层；网格 / 列表两种视图，侧边滚动条 |
| **目录封面** | 文件夹封面自动取目录里第一张图（按自然序，`img9` 排在 `img10` 前面）；结果按连接 + 路径缓存进 Room；取不到封面不会被记成「这文件夹没图」——下拉刷新就能恢复 |
| **照片** | 独立查看器：双指缩放、下拉关闭、幻灯片播放、保存原图；网格与胶片条按各自显示尺寸分别缓存 |
| **快捷操作** | 收藏当前目录（网络首页有「收藏的目录」入口）、当前目录随机播放、点视频即从该条起连播整个目录 |

**AList 原生协议另外还多这些：**

| 要点 | 说明 |
| :-- | :-- |
| **一个接口拿全目录** | `POST /api/fs/list` 按每页 500 自动翻页合并，几千条的大目录也能列全。注意 `per_page: 0` 并不是「一次拿全」——服务端会把它归一化成默认 200 条，这正是早期大目录只显示一部分的原因 |
| **播放不走本地代理** | `fs/get` 给出的 `raw_url`（对象存储直链）或 `/d/<路径>?sign=` 签名直链可以直接交给 mpv / GSY；SMB / FTP / WebDAV 才必须过本地 Range 代理 |
| **token 换一次用一场** | `POST /api/auth/login` 拿到 token 后复用（AList 默认 48 小时有效），401 自动重登一次；不像 Basic 认证那样每个请求都要解一次 Keystore 密文 |
| **签名失效能自愈** | 列目录时服务端会顺手带回每个条目的签名，取封面 / 取图就是最后一跳、零额外请求；签名过期则记住这个「已证伪」的签名、下次直接跳过，再向 `fs/get` 换一个新的 |
| **访客模式** | 账号密码都留空即访客身份，只读浏览公开目录 |
| **写操作** | 长按条目可重命名、删除，直接作用在服务器上（删除要过二次确认，无回收站）。SMB / FTP / WebDAV 三个协议是只读的 |

### 🚀 播放内核与扩展能力

| 能力 | 要点 |
| :-- | :-- |
| **双播放内核** | 默认 mpv，另备 GSYVideoPlayer（IJK / System / ExoPlayer）。设置里选默认内核；播放页一键互切；长按播放键用另一内核起播；GSY 有独立设置页 |
| **视界流** | 竖屏上下滑连播。相邻条目各自预起实例并提前缓冲；左边缘竖滑调亮度、右边缘竖滑调音量、横拖调进度；截图存相册，出错可重试 |
| **演职员合并** | 归并服务器上因名字写法不同（中英文 / 括号 / 全角半角 / 艺名）而裂开的同一个人。扫描疑似重复 / 一键合并 / 手动合并 / 按批次撤销；**会写入服务器元数据**、所有客户端立即生效，需管理员 |
| **中文字幕筛选** | Emby 没有这个维度，按条目**路径**匹配（默认 `-C`，标记表可增删），开启后扫全库边扫边出 |
| **弹幕** | 播放页叠加层，弹幕引擎已源码内置。自动按片名匹配 / 手动搜番，切集自动跟着换；时间轴偏移与亮度**按剧各记一份**；密度、速度、字号、透明度、显示区域、屏蔽词、防重叠均可调 |
| **标记为不喜欢** | 详情页菜单、播放页控制栏、视界流工具栏三处入口，长按可写一句原因；设置里分入口开关，另有管理页（左滑「取消标记 / 删除影片」，支持全选与批量） |
| **远程 ISO 原盘播放** | 纯客户端解码、不转码。自己解析 ISO9660 与蓝光 `PLAYLIST/*.mpls`，只把主影片那一段喂给 mpv |
| **DLNA 投屏** | 实验性。自动发现局域网接收设备，详情页与播放页均可遥控播放 / 暂停 / 音量 |
| **安全与隐私** | Emby 与 SMB / FTP / WebDAV 的密码统一用 Android Keystore AES-256-GCM 加密落盘（`enc:v1:`）；导出备份主动丢弃密码；界面不显示直链里的 `api_key` |
| **其它** | 长按复制（片名 / 原名 / 演职员 / 条目 ID）、视频预加载、连播下一集倒计时、记住每部剧的播放速度与音轨、外挂字幕记忆、播放页按钮可自定义、设置可搜索、桌面「继续观看」小组件 |
| **继承自 mpvEx** | 本地文件播放、Material 3 Expressive、SMB / FTP / WebDAV、外部音轨与字幕、手势控制、画中画、后台播放、Anime4K 着色器 |
| **中文本地化** | 滤镜预设、播放配置名、排序与视图弹窗等此前仍是英文的文案已补齐 |

---

## 更新记录

> 按版本倒序，当前版本 **v1.6.1**；历史安装包见 [Releases](https://github.com/nu1lkali/CineIsle/releases)。

### v1.6.1
- **演员 / 导演作品页：排序 + 页内搜索**（`ui/browser/emby`）：高产演员动辄几百部作品，以前只能一页页翻。现在可按**名称 / 年份 / 评分 / 最新添加**排序，再点一次已选中的项就反转方向（菜单里直接标 ↑ / ↓，触发器上也带箭头，不看菜单就知道现在怎么排）；另有**随机排序**，再点一次换一批。搜索匹配**片名 + 原名**（用英文原名也能搜到中文标题），空态会回显搜索词；**换排序后自动回到第一条**，翻到第一百部再切排序不会停在原处
- **收藏页接入同一套排序**（`ui/browser/emby`）：与作品页 / 媒体库完全统一 —— ↑↓ 箭头、再点反转、随机、方向记忆；同样排序后回顶
- **修「排序只有名称和随机有效」**（`domain/emby`）：年份 / 评分 / 添加时间这几个字段 Emby **默认不回传**，本地排序的键全是空 ⇒ 稳定排序退化成原顺序，看着就是「点了没反应」。现已随列表一并索取；缺值的条目**沉底**而不是挤在最前面
- **修搜索时封面闪一下**（`ui/browser/emby`）：两处叠加 —— ① 筛出 0 条时整个网格被卸载重建，改回容器常驻、提示叠在上层；② 异步图片的第一帧一律是占位图标，改为**先同步看一眼内存缓存**（命中就第一帧直接出图）
- **修一进收藏页就提示「没有匹配」**（`ui/browser/emby`）：冷启动停在演员 / 类型 / 工作室 tab 时会弹出空搜索词的「没有匹配」且网格全空
- **修演员作品页收藏红心置灰点不动**（`domain/emby` · `ui/browser/emby`）：收藏状态改为独立读取、且排在拉作品列表之前，不再受「读条目失败」牵连
- **分集卡片真正等高**（`ui/browser/emby`）：卡高此前由「有没有内容」决定（标题一两行、时长缺失、进度条有无），三个变量逐个收口后张张等高，两行标题完整显示不截断
- **「下载本季 N 集」改成按钮**（`ui/browser/emby`）：原先是一段看着像正文链接的文字，现改为胶囊按钮并左对齐
- **播放器点屏幕唤不出控制栏**（`ui/player`）：弹幕层是全屏叠层里最靠上的那层，把整屏触摸都吞了 —— 弹幕没渲染时点击正常、弹幕一出来就点不动，正是这个特征。改为独立分层并置于控件之下
- **「不喜欢」管理页的删除改为按住 2 秒**（`ui/browser/emby`）：删除入口来自侧滑手势（高误触路径），红色按钮上持续按住、进度条填满才真正删除服务器源文件（不可逆），中途松手即中止
- **搜索历史按服务器隔离**（`database`）：切换 Emby 服务器 / 账号后不再看到别的库的历史词（那些词在本库根本搜不到）

### v1.6.0
- **竖屏弹幕不再全挤在画面上方**（`ui/player`）：竖屏下播放器窗口铺满全屏、画面居中一条，弹幕叠层却跟着窗口铺满 ⇒ 弹幕全落在画面上方的黑边里。现按视频宽高比算出真实画面矩形，弹幕只在画面条带内排布，无黑边时自动回退全屏
- **弹幕面板开关错乱**（`ui/player`）：「防止弹幕重叠」关掉就再也打不开、开一个另一个跟着翻面 —— 面板快照与偏好脱钩所致，所有设置项收口到统一刷新，面板打开即同步一次
- **详情页分集卡片高度参差**（`ui/browser/emby`）：一行标题的卡片比两行矮一截，横向列表高低不齐
- **关于页补弹幕引擎致谢**（`presentation`）：弹幕引擎已改为源码内置，不再出现在开源许可清单里，手动补一条
- **设置项文案截断**（`ui/preferences`）：「显示控件时同时显示系统状态栏」加了图标后被省略号截断，已缩短

### v1.5.10
- **封面合成独立成组**（`ui/preferences`）：「合成随机封面图 + 重新合成策略 + 间隔小时数/间隔天数」这三项原先缩进挂在「Emby 卡片与封面」里当子项，主→子→孙三级缩进观感违和。现**整块抽成独立分组「Emby 封面合成」**，组内三行扁平并列、不再缩进；后两行仅在开关打开、且策略选了「小时 / 天」时才出现 —— 依赖关系改为用「分组 + 条件出现」表达，而不是缩进。原分组相应改名「Emby 卡片外观」（只剩卡片相关 4 项）；「恢复默认保存位置」的缩进也一并去掉，设置页不再有任何缩进层级
- **超长标题缩短**（`ui/preferences`）：「下载完成后清除服务端播放进度」13 字在加了图标后被截断成「…清除服务端播放进…」，现缩短为「**下载后清除播放进度**」（9 字），「下载完成后」「服务端」这些限定信息留给下方说明文字，功能与偏好键不变
- **设置页按模块重排**（`ui/preferences`）：原来「Emby 媒体库」一个分区塞了 28 项 —— 库组织 / 推荐 / 播放同步 / 照片浏览 / 照片保存 / 卡片外观 / 封面合成 / 元数据 / 随机播放全混在一起，找一项要翻很久。现按功能模块拆成 14 个分组（外观 / 播放与控件 / 本地媒体库 / 媒体渲染 / Emby 媒体库与元数据 / Emby 推荐与首页 / Emby 播放与数据同步 / Emby 照片浏览与保存 / Emby 卡片与封面 / Emby 随机播放 / 网络浏览 / 数据与缓存 / 安全与应用锁 / 高级与关于），每组「1 个分区标题 + 1 张卡片」，标题即锚点、与分组一一对应。跨模块项按真实归属挪位：播放界面布局 → 播放与控件、多版本优先选择 → Emby 播放与数据同步、卡片快捷收藏 → Emby 卡片与封面、演职员合并 → Emby 媒体库与元数据、应用锁 → 独立「安全与应用锁」。**45 项设置的偏好键与功能语义一律未改**，只调组织方式与信息层级
- **设置页标题单行化 + 补图标 + 文案**（`ui/preferences`）：补齐图标后，长标题（如「下载完成后清除服务端播放进度」「进入照片浏览器自动播放幻灯片」）因图标挤占宽度而折成两行 ⇒ 给 11 个设置页全部偏好行 title 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`（Android 标准单行列表项写法，正常字号完整显示、仅超大字号才省略）；「推荐算法」此前是自定义 Column（非库组件）漏加图标，补 `AutoAwesome`；「间隔小时数」改名为「合成封面图间隔小时数」（偏好项与输入框标题同步，该值是随机封面图重新合成间隔）
- **设置页图标补齐**（`ui/preferences`）：聚合库入口加了图标后其他设置条目仍「光板」一块，现 11 个设置页凡无图标的偏好条目统一补全（共 152 条）—— me.zhanghai.compose.preference 2.2.0 的 Preference / SwitchPreference / ListPreference / SliderPreference / TextFieldPreference / TwoTargetIconButtonPreference 都带 icon 槽位，图标用 `Icons.Outlined.X` + `colorScheme.primary` 着色，风格与既有条目完全一致；每个图标名都对照 material-icons-extended / core 1.7.8 的实际 AAR 验证过存在性
- **聚合库（新功能）**（`database` · `domain/emby` · `ui/browser/emby` · `ui/preferences`）：把同一个服务器下多个**同类型**媒体库（如多个电影库）合并成一个本地视图。首页媒体库行末尾出现聚合库卡片（Layers 图标 + 「N 库聚合」角标，与真实库明显错开），点进去是一个归并网格；配置入口在设置里
- **深翻页不重不漏**（`domain/emby`）：每个成员库一条独立泳道（游标 + 本地缓冲），按排序语义从各泳道队头挑「当前最该出现」的条目接上，Id 做 tiebreak —— 滑到第 300 条时不会退回「每个库的前几条」拼重复片段
- **随机播放 = 换一批**（`domain/emby`）：随机排序下服务端每次重洗、游标无意义，改为固定种子在各库混排；下拉刷新换种子，语义就是整副牌重洗
- **单库故障不拖垮整页**（`domain/emby` · `ui/browser/emby`）：某个成员库被删 / 网络失败只标记该泳道，其余库照常出内容，页面顶部给一句提示；只有首屏全失败才转错误态
- **成员库失效提示**（`ui/browser/emby`）：建库后成员库在服务端上被删、或类型变了，详情页顶部提示「N 个成员库已在服务器上删除，已跳过」，不打断浏览
- **多服务器 / 多用户不串**（`database` · `ui/browser/emby`）：聚合库按 serverId 分桶，首页只列当前服务器的；详情页带归属校验，拿当前服务器的 token 取不到别台服务器的库；删除服务器时级联清掉它的聚合库
- **管理页**（`ui/browser/emby`）：设置 → 聚合库进入；新建 / 编辑（改名 / 改成员）/ 删除（二次确认）；同类型约束（勾选第一个库后不同类型置灰）、至少 2 个成员；标题下显示当前在管哪台服务器
- **只在本 App 内生效**（全链路）：不写服务器、不改目录结构，普通用户密钥即可，建完即时生效
- **弹幕从「能开」到「能显示」**（`ui/player`，v105–v116 多轮）：这个坑挖得比预想深 —— 表现为「开关能开、抽屉里写着已匹配 N 条、画面上一条都没有」。逐层查下来是四层独立问题：① 开关只写偏好、**没有任何拉取触发**（开播时关着、后来打开就永远没弹幕）；② 面板读的快照初值恒为 false（点了立刻弹回）；③ 渲染层在 `prepare()` 同一帧就 `start()`，把引擎自己投出的准备消息连同重试链一起清空 ⇒ 画布永远空白；④ 自写 parser 漏了给每条弹幕设计时器，引擎判「全部超时」逐条绕过绘制。修完后自动匹配、切集换弹幕、暂停 / 恢复都对得上
- **弹幕集号不再错集**（`ui/player`）：传的是队列下标而非真实集号 ⇒ 从第 5 集点播会拿到第 1 集的弹幕；跨内核（mpv / GSY）也透传同一份集号
- **片尾「下一集」卡片 + 衔接预热**（`ui/player`）：片尾前 N 秒右下角出卡片，点即切集（与原来的连播倒计时胶囊分工不同，可同时出现）；同一时刻对下一集发一次小 Range 请求把连接焐热，切集少黑一下（刻意不用双路预拉，避免大码率片源被自己拉卡）
- **分集批量下载**（`ui/browser/emby`）：分集区展开后「下载本季 N 集」，单集卡片右上角有下载小钮（可下载 / 已交出去 / 已下好三态）
- **按剧记忆时间轴偏移与亮度**（`ui/player`）：弹幕偏移和亮度都按剧各记一份，换片不必再手动清零，可一键回落全局
- **分集三分区**（`ui/browser/emby`）：单集详情页新增同季 / 特别篇 / 演员相关三个区 —— 那里原本没有任何切兄弟集的入口
- **70GB 大文件不再卡成幻灯片**（`ui/player`）：三个独立原因 —— 磁盘缓存清理撞在首帧上屏的主线程、readahead 30 秒远超字节闸门、`time-pos` 采样每 42ms 抢一次播放线程锁；另把 `vd-lavc-dr` 从着色器分支挪到初始化选项无条件生效
- **点分集必崩**（`ui/player`）：`Series` / `Season` / 文件夹本身没有媒体文件却直接拿去请求流地址，播放器一路走到初始化后崩；现按容器类型路由

### v1.5.9
- **网络照片缩略图改为压缩缓存**（`ui/browser/networkstreaming`）：以前磁盘里存的是原图（单张 3–8MB），只装得下几十张，滚过去再滚回来就得重新下载。现在首次加载后会把缩略图压成 JPEG 单独存一份（约 60KB/张），同样的空间能放几千张
- **缓存容量上调**（`ui/browser/networkstreaming`）：磁盘 128MB → 512MB，内存 25% → 35%（并设 64MB 下限、256MB 上限）
- **不再闪骨架屏**（`ui/browser/networkstreaming`）：显示过的图滚回来时直接出图，不再扫一遍加载动画
- **画质**（`ui/browser/networkstreaming`）：缩略图按显示尺寸采样解码（网格 512px、胶片条 192px），JPEG 质量 85 —— 屏幕上看到的就是原尺寸的清晰度，不是「压过的糊图」
- **看大图返回后停在原位置**（`ui/browser/networkstreaming`）：在图片文件夹里滚到某处、点开大图再看完返回，列表不再莫名弹回顶端或跳到中段；网格与列表视图表现一致
- **播放器提示乱码**（`ui/player`）：内核切换、ISO 转码、全屏切换、网速显示等提示与列表分隔符里的乱码字符已还原
- **Emby 收藏 / 演员页列数**（`ui/browser/emby`）：跟随设置里的「每行个数」，不再固定 3 列（此前把媒体库调成 5 列，进收藏页会突变回去）
- **列表重排动画**（`ui/browser/emby` · `ui/browser/networkstreaming`）：切换排序、增删条目时条目平滑移动到位，不再整屏硬切
- **「新视频」角标配色**（`ui/browser/emby`）：改用主题的错误色，深色模式下不再和背景糊在一起
- **底部留白**（`ui/browser/emby`）：首页 / 收藏 / 历史的底部间距随导航栏高度走，底栏变高时内容不再被压

### v1.5.8
- **崩溃：崩溃页的「重启」按钮自己崩**（`presentation`）：换成非默认桌面图标后，重启按钮拉起的入口被系统禁用，一点就再崩一次，形成死循环；现已改为拉起当前实际生效的那个入口
- **崩溃：切后台偶发闪退**（`app`）：界面状态的保存体积一度涨到 2 MB，超过系统 1 MB 的上限；现加了体积闸门，正常状态照常保留（旋转屏幕不受影响）
- **网络照片缩略图滚回来要重新加载**（`ui/browser/networkstreaming`）：网格缩略图与大图此前共用同一份内存缓存，看过一次大图就会把周围缩略图挤出缓存；现按显示尺寸分开缓存，滚回原位置直接命中
- **缩略图首帧空白**（`ui/browser/networkstreaming`）：列表回收后重建的那一帧不再露出卡片底色

### v1.5.6 – v1.5.7
- **AList 大目录只显示一部分**：修复目录条目超过 200 条时只加载前 200 条的问题（服务端对每页条数有默认上限，现在会自动翻页取全）
- **界面中文化**：删除 / 重命名确认、导入导出、权限说明、媒体信息、播放器提示等此前仍是英文的文案全部补齐
- **卡片圆角**：设置里的「卡片圆角」现在对全 App 生效（此前本地与网络卡片固定 12dp）
- **网络首页搜索**：连接列表支持按名称 / 地址 / 协议搜索；有关键词时自动收起直链与收藏入口
- **加载骨架屏**：本地文件夹、网络目录、最近播放加载时先显示版式骨架，不再是整屏转圈
- **照片浏览器**：Emby 与网络位置合并为一套 —— 底部信息栏、全屏、旋转、更多菜单两边一致；底部缩略图条也支持网络位置
- **播放与手势**：新增启动揭幕动画；卡片长按带轻微震动；改排序后自动回到顶部；Emby 子文件夹卡片带文件夹图标
- **随机播放**：只有子文件夹的目录也能用；单击下钻、长按只播当前层，提示可在设置里关闭

### v1.5.5
- **网络存储**：连接列表卡片重新设计，改为单行紧凑样式，一屏能看更多；新增「置顶」，常用连接固定排在最前
- **网络存储**：顶栏更清爽 —— 标题字号收小、主页入口收进「更多」菜单，长文件夹名不再挤掉右侧图标
- **Emby 媒体库**：修复部分文件夹封面只显示一张缩略图、拼不齐四宫格的问题
- **照片浏览器**：超长标题自动以「…」收尾；底部信息改为两行，文件名不会再和文件大小紧贴在一起被看错

### v1.5.4
- **网络存储（SMB / WebDAV / FTP）大改版**：目录里的文件夹、图片、视频、其他文件混合展示，配统一卡片与类型角标；新增「收藏的目录」快捷入口
- **网络照片查看器**：双指缩放、下拉关闭、幻灯片播放、一键保存原图
- **路径导航**：浏览网络目录时点顶栏标题，可弹出层级列表直达任意一层，并能一键回到首页
- **新增 AList 网盘支持**：走的是 AList 自己的原生 API（不是 WebDAV）——目录一个接口列全、图片与视频直链播放不走本地代理；长按条目可重命名 / 删除，写操作直接作用在服务器上
- **Emby 媒体库**：修复点进文件夹后内容空空如也、网格视图里「排序点了没反应」
- **大库体验**：修复超大媒体库「一直加载中 / 无响应」；十万条大库的首页加载明显更快
- **修复**：AList 图片全部无法显示、WebDAV 靠后的文件夹与图片加载失败

### v1.5.3
- **照片浏览器**：加载更快更跟手，加载中显示占位骨架、失败可一键重试；预加载张数可在设置里调整
- 点图片任意处进入沉浸式全屏；幻灯片右下角显示倒计时，并修复幻灯片「只翻一半」
- 修复照片网格工具行压住第一排卡片

### v1.5.1 – v1.5.2
- 混合媒体库改为「内容即所见」，子文件夹不再无限加载
- 修复照片网格页两个问题：工具行压住卡片、相册页返回键被拦截
- 照片卡片统一按原始比例显示

---

## 致谢

| 项目 | 用途 |
| :-- | :-- |
| [mpvEx](https://github.com/marlboro-advance/mpvEx) | 直接的上游 |
| [mpv-android](https://github.com/mpv-android) | mpv 绑定与 libmpv 集成（主播放内核） |
| [GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer) | 第二个播放内核（含自带的 IJK / FFmpeg 引擎） |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | 视界流的多实例播放 |
| [Emby Java SDK](https://github.com/MediaBrowser/Emby.SDK) | 客户端接口层 |
| [UPnPCast](https://github.com/yinnho/UPnPCast) | DLNA 投屏 |
| [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | 开源许可清单 |
| [SMBJ](https://github.com/hierynomus/smbj) · [Sardine](https://github.com/lookfirst/sardine) · [Commons Net](https://commons.apache.org/proper/commons-net/) · [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 网络存储与本地服务 |
| [AList](https://github.com/AlistGo/alist) | 网盘聚合服务端。客户端按它的公开 API 独立实现（仅作协议对端，未引入其代码） |
| [mpvKt](https://github.com/abdallahmehiz/mpvKt) · [Next player](https://github.com/anilbeesetti/nextplayer) · [Gramophone](https://github.com/FoedusProgramme/Gramophone) | 界面参考 |

> 上游项目的开源许可（见 [LICENSE](LICENSE)）继续适用。

---

## 支持

影屿是业余时间维护的独立项目。如果它对你有所帮助，欢迎扫码赞赏 —— 完全自愿，不影响任何功能。

<p align="center">
  <img src=".github/assets/wechat-reward.png" width="320" alt="微信赞赏码" />
</p>

