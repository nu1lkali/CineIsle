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
  <a href="https://github.com/nu1lkali/CineIsle/releases"><img src=".github/badges/release-1.2.1.svg" alt="Release: v1.2.1" /></a>
  <img src=".github/badges/flavor-standard.svg" alt="Flavor: standard" />
  <img src=".github/badges/package-cineisle.svg" alt="Package: app.cineisle.player" />
  <img src=".github/badges/kernel-mpv-gsy.svg" alt="Kernel: mpv + GSYVideoPlayer" />
</p>

<p align="center">
  <a href="#项目背景">项目背景</a> ·
  <a href="#技术栈">技术栈</a> ·
  <a href="#模块">模块</a> ·
  <a href="#功能">功能</a> ·
  <a href="#致谢">致谢</a>
</p>

---

## 项目背景

一开始用的是某款第三方 Emby 播放器。用着还行，但总有些自己想要的功能它没有，于是去提需求——作者不太愿意做，也没怎么回复。

那没辙了，自己做呗。

从零写一个播放器内核不现实，所以找了一个足够扎实的播放器做底座：**[mpvEx](https://github.com/marlboro-advance/mpvEx)**（底层是 mpv-android / libmpv）。mpvEx 本身是一个好用的**本地**播放器，但不带 Emby 客户端。

于是就有了 **影屿 / CineIsle**：在 mpvEx 上补出一整套 Emby 客户端，再把那些「自己用得上」的功能一个个加上去。

名字取自「电影的岛屿」——把自己想看的片子收在一个地方。

---

## 技术栈

| 领域 | 选型 |
| :-- | :-- |
| **语言 / UI** | Kotlin · Jetpack Compose（Material 3 Expressive）· 单 Activity + Navigation 3 |
| **架构** | 分层（`domain` 领域 / `ui` 界面 / `database` 持久化 / `preferences` 设置）· Koin 依赖注入 · 协程 + Flow |
| **持久化** | Room 2.8（KSP 生成、schema 导出、迁移测试）· `me.zhanghai.compose.preference` |
| **网络** | OkHttp 5 + kotlinx.serialization（手写 Emby 客户端）· SMBJ / Sardine / Commons Net（SMB / FTP / WebDAV）· NanoHTTPD（本地 Range 代理） |
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
| `domain/emby` | Emby 客户端核心：手写 OkHttp3 + kotlinx.serialization，覆盖条目 / 媒体库 / 搜索 / 播放列表 / 会话 / 演职员等端点，含分页游标、直链预解析、筛选与排序 |
| `domain/iso` | 远程 ISO 原盘播放：ISO9660 解析、蓝光 `mpls` 解析、段映射、HTTP Range 读取与本地代理 |
| `domain/dislike` | 「标记为不喜欢」的领域逻辑与持久化 |
| `domain/media` · `network` · `thumbnail` · `playbackstate` · `recentlyplayed` · `anime4k` | 媒体信息解析、网络存储、缩略图、播放进度、最近播放、Anime4K |
| `ui/browser/emby` | Emby 界面：首页、媒体库、详情页、搜索、筛选、播放列表、演职员合并、不喜欢管理页 |
| `ui/browser/networkstreaming` · `filesystem` · `folderlist` · `playlist` · `videolist` · `recentlyplayed` | 本地 / 网络文件浏览与各列表页 |
| `ui/player` | 播放页：`controls` 控件簇布局引擎、`engine` 双内核抽象、`gsy` GSY 内核、`feed` 视界流 |
| `ui/preferences` | 设置页（含设置搜索） |
| `database` | Room 实体 / DAO / 仓库，含迁移与 schema 导出 |
| `di` | Koin 依赖注入 |
| `presentation` | 通用组件与崩溃页 |
| `preferences` | 设置项持久化 |
| `dlna` | DLNA 投屏 |
| `widget` | 桌面「继续观看」小组件 |
| `utils` | 通用工具 |
| `com/yinnho/upnpcast` | UPnPCast（源码内置） |
| `app/embyclient-reference` | Emby Java SDK（OpenAPI 生成的参考实现，**不参与编译**，仅供查询端点路径与参数） |

---

## 功能

### Emby 客户端（本项目从零实现）

| 功能区 | 要点 |
| :-- | :-- |
| **服务器与账号** | 多服务器添加 / 编辑 / 删除 / 切换；用户名 + 密码登录；配置存 Room，Token 失效自动重登 |
| **首页** | 媒体库入口卡片、继续观看（带进度）、最新加入、随便看看（可整块关掉）、全局搜索（400ms 防抖 + 类型筛选 + 历史 + 联想建议） |
| **媒体库浏览** | 分类（全部 / 继续播放 / 合集 / 收藏 / 文件夹 / 演员）、多种排序、三种卡片样式（海报 / 背景图 / 横幅）与 1~2 行标题、文件夹 2×2 宫格下钻、按当前屏幕结果随机播放、库内搜索；结果与滚动位置进程内缓存 |
| **筛选** | 类型 / 标签 / 年份 / 官方分级 / 评分 + 播放状态 / 字幕 / 只看收藏 / 中文字幕；面板内改动只是草稿，点「确定」才生效，条件按库各记一份 |
| **片单与推荐** | 播放列表（新建 / 增删 / 拖动排序）、猜你喜欢 · 连播（服务端或本地算法）、合集 |
| **详情页** | 可折叠的封面头部、简介展开 / 收起、演职员与同类型推荐、剧集按季集展开、媒体信息、收藏 / 已看 / 加入下载、更多菜单（刮削 / 刷新元数据 / 删除） |
| **播放与进度同步** | 直链播放 `stream?static=true` 不转码；剧集连播；进度回传 `/Sessions/Playing[/Progress\|/Stopped]`，多端互通 |
| **离线下载** | `Range` 断点续传；Android 10+ 经 `MediaStore` 写公共 `Download/CineIsle`（免存储权限）；最多同时下 2 个，任务持久化 |

### 播放内核与扩展能力

| 能力 | 要点 |
| :-- | :-- |
| **双播放内核** | 默认 mpv，另备 GSYVideoPlayer（IJK / System / ExoPlayer）。设置里选默认内核；播放页一键互切；长按播放键用另一内核起播；GSY 有独立设置页 |
| **视界流** | 竖屏上下滑连播。相邻条目各自预起实例并提前缓冲；左边缘竖滑调亮度、右边缘竖滑调音量、横拖调进度；截图存相册，出错可重试 |
| **演职员合并** | 归并服务器上因名字写法不同（中英文 / 括号 / 全角半角 / 艺名）而裂开的同一个人。扫描疑似重复 / 一键合并 / 手动合并 / 按批次撤销；**会写入服务器元数据**、所有客户端立即生效，需管理员 |
| **中文字幕筛选** | Emby 没有这个维度，按条目**路径**匹配（默认 `-C`，标记表可增删），开启后扫全库边扫边出 |
| **标记为不喜欢** | 详情页菜单、播放页控制栏、视界流工具栏三处入口，长按可写一句原因；设置里分入口开关，另有管理页（左滑「取消标记 / 删除影片」，支持全选与批量） |
| **远程 ISO 原盘播放** | 纯客户端解码、不转码。自己解析 ISO9660 与蓝光 `PLAYLIST/*.mpls`，只把主影片那一段喂给 mpv |
| **DLNA 投屏** | 实验性。自动发现局域网接收设备，详情页与播放页均可遥控播放 / 暂停 / 音量 |
| **安全与隐私** | Emby 与 SMB / FTP / WebDAV 的密码统一用 Android Keystore AES-256-GCM 加密落盘（`enc:v1:`）；导出备份主动丢弃密码；界面不显示直链里的 `api_key` |
| **其它** | 长按复制（片名 / 原名 / 演职员 / 条目 ID）、视频预加载、连播下一集倒计时、记住每部剧的播放速度与音轨、外挂字幕记忆、播放页按钮可自定义、设置可搜索、桌面「继续观看」小组件 |
| **继承自 mpvEx** | 本地文件播放、Material 3 Expressive、SMB / FTP / WebDAV、外部音轨与字幕、手势控制、画中画、后台播放、Anime4K 着色器 |
| **中文本地化** | 滤镜预设、播放配置名、排序与视图弹窗等此前仍是英文的文案已补齐 |

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
| [mpvKt](https://github.com/abdallahmehiz/mpvKt) · [Next player](https://github.com/anilbeesetti/nextplayer) · [Gramophone](https://github.com/FoedusProgramme/Gramophone) | 界面参考 |

> 上游项目的开源许可（见 [LICENSE](LICENSE)）继续适用。
