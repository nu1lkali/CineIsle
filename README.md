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
  <a href="https://github.com/nu1lkali/CineIsle/releases"><img src=".github/badges/release-1.6.80.svg" alt="Release: v1.6.80" /></a>
  <img src=".github/badges/flavor-standard.svg" alt="Flavor: standard" />
  <img src=".github/badges/package-cineisle.svg" alt="Package: app.cineisle.player" />
  <img src=".github/badges/kernel-mpv-gsy-vlc.svg" alt="Kernel: mpv / GSY / VLC" />
</p>

<p align="center">
  <a href="#项目背景">项目背景</a> ·
  <a href="#功能">功能</a> ·
  <a href="#技术栈">技术栈</a> ·
  <a href="#自行编译">自行编译</a> ·
  <a href="#项目结构">项目结构</a> ·
  <a href="#更新记录">更新记录</a> ·
  <a href="#致谢">致谢</a>
</p>

<hr />

<p align="center">
  直链播放不转码 · mpv / GSY / VLC 三内核 · 两套界面风格 · 网络字幕与字幕时间线 ·
  多服务器聚合检索 · SMB / FTP / WebDAV / AList · 离线下载 · 弹幕 · DLNA 投屏
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

## 功能

### 播放

- **三内核**：mpv（默认）、GSYVideoPlayer、VLC。设置里选默认内核，播放页可一键互切，长按播放键可用另一内核起播
- 直链播放不转码；多版本连播、剧集连播、播放进度回传 Emby，多端互通
- **字幕**：服务端字幕自动挂载、外挂字幕智能匹配与绑定记忆、网络字幕搜索下载、**字幕时间线**（跟随高亮、点句精确跳转、台词检索、主副轨切换）、字幕编码统一归一
- 弹幕（自动匹配、按剧记忆偏移）、视界流（竖屏上下滑连播）、远程 ISO 原盘、DLNA 投屏
- 解码卡死自动恢复、HDR / 杜比视界、Anime4K 着色器、画中画与后台播放

### Emby 客户端

- 多服务器管理、主备自动接管、同机多入口自动择优
- 媒体库浏览 / 筛选 / 排序、聚合库、随机抽卡、全局搜索
- 详情页、演职员合并、智能媒体合并（实验性）、标记为不喜欢
- 离线下载（断点续传）、设置搜索、桌面「继续观看」小组件
- **聚合检索**：多台服务器一次并发检索，先选参与范围再输入关键词；跨服重复条目归并为一条，可查逐台副本、切换分组视图与五种排序。与首页搜索完全独立

### 网络存储

- SMB / FTP / WebDAV，以及 AList 原生 API
- 目录浏览、文件夹封面、照片查看器、随机播放与视界流
- AList 上传（相册 / 文件 / 文件夹三种选择器，关掉面板不中断）、复制 / 移动 / 批量整理工具箱
- 属性与目录统计、下载到本地、平板双栏布局

### 界面与其它

- **Classic / Cine 两套界面风格**，共用同一份数据层；切换后重启生效，未迁移页面自动回落经典实现
- 应用锁（PIN / 图案，进程级重锁）、密码 Keystore 加密落盘、中文本地化

---

## 技术栈

| 领域 | 选型 |
| :-- | :-- |
| 语言 / UI | Kotlin · Jetpack Compose（Material 3）· 单 Activity + Navigation 3 |
| 架构 | `domain` / `ui` / `database` / `preferences` 分层 · Koin · 协程 + Flow |
| 持久化 | Room 2.8（KSP）· `me.zhanghai.compose.preference` |
| 网络 | OkHttp 5 + kotlinx.serialization · SMBJ / Sardine / Commons Net · NanoHTTPD |
| 播放 | libmpv · GSYVideoPlayer · libVLC · Media3 ExoPlayer + NextLib FFmpeg 扩展 |
| 图像 | Coil 3（按显示尺寸分桶缓存）· haze（实时背景模糊） |
| 构建 | AGP 9.4.0 · Kotlin 2.3.20 · Compose BOM 2026.03.00 · JDK 17 · R8 混淆与资源裁剪 |
| 设备 | minSdk 26 · targetSdk 36 · compileSdk 37 · flavor `standard` / `playstore` / `fdroid` |
| 测试 | JUnit4 + Robolectric + Truth · Room `MigrationTestHelper` |

---

## 自行编译

需要 JDK 17 与 Android SDK（compileSdk 37）。

```bash
# 只出 arm64-v8a 的 release 包
./gradlew assembleStandardRelease -Pabi=arm64-v8a

# 不带参数 = 按 4 个 ABI 拆包 + universal
./gradlew assembleStandardRelease
```

- 产物在 `app/build/outputs/apk/standard/release/`；debug 用 `./gradlew assembleStandardDebug`
- APK 里的 versionCode = gradle 中的 versionCode × 10 + ABI 码（arm64-v8a 为 2）
- 签名读仓库根的 `keystore.properties`（已被 `.gitignore` 屏蔽）。没有该文件时 release 仍会出包，但未签名、无法安装

---

## 项目结构

| 路径 | 职责 |
| :-- | :-- |
| `domain/emby` | Emby 客户端：条目 / 媒体库 / 搜索 / 播放列表 / 会话端点，主备接管、双入口择优、聚合检索 |
| `domain/subtitle` · `online` | 字幕编码归一、时间线解析、网络字幕检索与下载 |
| `domain/iso` · `dislike` · `media` · `network` | 远程 ISO、标记不喜欢、媒体信息、网络存储等 |
| `ui/browser/emby` | Emby 页面：首页、媒体库、详情、搜索、聚合检索、演职员合并 |
| `ui/browser/networkstreaming` | 网络存储四套客户端、上传引擎、整理工具箱、照片查看器 |
| `ui/player` | 播放页：三内核抽象、视界流、字幕时间线、弹幕、控件布局 |
| `ui/cine` | Cine 界面：设计令牌、玻璃组件、各页面与路由回落 |
| `ui/preferences` · `ui/lock` | 设置页（含搜索）、应用锁 |
| `database` · `di` · `dlna` · `widget` | Room 持久化、Koin、DLNA 投屏、桌面小组件 |
| `nextlib-patched` | Media3 FFmpeg 本地补丁（GPL-3.0），扩展冷门编码映射 |

---

## 更新记录

> 按版本阶段倒序，当前版本 **v1.6.80**（versionCode 332）。完整逐版说明见仓库内 `RELEASE_NOTES_*.md`，历史安装包见 [Releases](https://github.com/nu1lkali/CineIsle/releases)。

### v1.6.80 · 聚合检索

- 新增多服务器聚合检索：并发查询、可选参与范围（空词态即可选定，未参与的服务器不发请求）、跨服重复条目归并、逐台副本抽屉、分组视图
- 五种客户端排序（匹配优先 / 名称 / 发行年份 / 文件体积 / 加入时间）；排序只改行顺序，不改变默认打开项
- 与现有搜索链路完全独立：不改动首页搜索状态，检索历史单独分区；打开别家服务器结果时切换当前服务器并给出提示

### v1.6.73 – v1.6.79 · 字幕时间线、编码归一与重装恢复

- 播放页新增**字幕时间线**：列出当前字幕的时间轴，跟随播放高亮、点击台词精确跳转、按文本检索，支持主 / 副字幕轨切换
- 容器按屏幕方向区分：横屏右侧悬浮面板、竖屏底部抽屉；竖屏改为停靠画面黑边，少遮挡画面；点画面可收回并带进出场动画
- 字幕编码改为**只判定一次并统一归一为 UTF-8**，修复「时间线预览正常、mpv 挂载乱码」以及特殊编码字幕乱码；对已损坏文件加保护闸门
- 补回「字幕保存目录」设置入口：下载时另存副本并写清单，卸载重装后可从该目录恢复本地字幕库与索引
- 多版本影片版本名补全番号前缀；详情页新增字幕卡与「全部字幕」抽屉

### v1.6.63 – v1.6.72 · 网络字幕与字幕实验室

- 接入迅雷与 SubtitleCat 两个来源，播放页、详情页、字幕实验室三个入口共用同一份本地字幕库
- **字幕实验室**：选片、检索、试看预览、时间轴校准、设为默认、管理已下载字幕
- 字幕智能匹配改为按番号 + 多证据交叉判定；外挂字幕手动绑定与重装恢复
- 字幕面板主副轨可辨识、按语言 / 文件名筛选；字幕下载后在播放时自动挂载

### v1.6.49 – v1.6.62 · 目录导航、连播与详情页字幕

- Emby 目录导航：详情页「打开所在文件夹」、顶栏「跳转到」层级导航、返回按目录层级生效、库根名以媒体库名称为准
- 多版本自动连播（各版本独立进度）、剧集连播顺序修正、播放器电量指示
- 详情页字幕卡：播放前即可查看字幕构成、格式与来源
- 媒体库演员列表排序、字幕链路文案统一、超长字幕不再静默截断

### v1.6.44 – v1.6.48 · 第三内核与 STRM 直链

- 新增 **VLC 第三内核**，播放页手势与控件对齐 mpv；新增解码卡死看门狗与 HEVC 10-bit 恢复
- Emby **STRM 直链播放**（默认关闭，带可达性预检与安全回退）
- 智能媒体合并（实验性）：扫描疑似重复条目、手动组合、待处理清单、小窗预览与合并历史，带同服务器 / 同媒体库安全校验

### v1.6.23 – v1.6.43 · 智能合并与播放可靠性

- **Emby 服务端字幕自动挂载**：按语言偏好选择，区分内嵌 / 外挂与简繁，同语言优先文本字幕
- Classic 界面内部状态与页面外壳统一收口；首页分区失败不再整块消失
- 视界流 mpv 兜底可连续使用；`idle=once` 导致的后续条目黑屏、错误卡片叠在画面上等问题修复
- 冷门编码（MPEG-4 Part 2 / VC-1 / WMV / RealVideo / VP6）经 NextLib FFmpeg 扩展后可播

### v1.6.11 – v1.6.22 · 上传、搜索与字幕匹配

- AList 复制 / 移动 / 批量操作；上传支持后台通知、取消确认、失败清理、队列恢复，上传完成可删除本机文件
- 搜索页与媒体库内搜索重做：历史胶囊、推荐、状态占位；演员 / 收藏页排序与随机换一批
- 外挂字幕绑定记忆与按番号智能匹配；字幕 / 音轨面板支持筛选
- 媒体库随机抽卡、网络位置批量重命名、默认解码器选项、设置页搜索

### v1.6.3 – v1.6.10 · Cine 界面、网盘文件管理器与主备接管

- **Cine 影院界面**从首版到六个页面迁移完成，与经典界面共用数据层，未迁移页面自动回落
- 网络位置补齐整理工具箱、属性 / 目录统计、下载到本地、平板双栏，从「只能看」变成可用的文件管理器
- 主备服务器自动接管、同机双入口自动择优；网盘视界流、穿透子文件夹
- 弹幕渲染链路、聚合库、分集批量下载与多项播放稳定性修复

### v1.6.0 – v1.6.2 · 弹幕、排序与 Cine 起步

- 弹幕自动匹配、按剧记忆偏移；竖屏弹幕按真实画面矩形排布
- 演员 / 导演作品页排序与页内搜索、收藏页同款排序、搜索封面闪烁修复
- Cine 界面第一阶段：设计令牌、双轨切换、悬浮玻璃 Dock 与首页

### v1.5.x · 早期版本

- 网络存储（SMB / FTP / WebDAV / AList）与照片浏览器成型；设置页按模块重排、图标与中文本地化补齐
- 聚合库、弹幕链路、崩溃与性能修复

---

## 致谢

| 项目 | 用途 |
| :-- | :-- |
| [mpvEx](https://github.com/marlboro-advance/mpvEx) | 直接的上游 |
| [mpv-android](https://github.com/mpv-android) | mpv 绑定与 libmpv 集成（主播放内核） |
| [GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer) · [libVLC](https://code.videolan.org/videolan/vlc) | 第二、第三播放内核 |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | 视界流的多实例播放 |
| [Emby Java SDK](https://github.com/MediaBrowser/Emby.SDK) | 客户端接口层参考 |
| [UPnPCast](https://github.com/yinnho/UPnPCast) · [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | DLNA 投屏、开源许可清单 |
| [SMBJ](https://github.com/hierynomus/smbj) · [Sardine](https://github.com/lookfirst/sardine) · [Commons Net](https://commons.apache.org/proper/commons-net/) · [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 网络存储与本地服务 |
| [AList](https://github.com/AlistGo/alist) | 网盘聚合服务端；客户端按其公开 API 独立实现 |
| [mpvKt](https://github.com/abdallahmehiz/mpvKt) · [Next player](https://github.com/anilbeesetti/nextplayer) · [Gramophone](https://github.com/FoedusProgramme/Gramophone) | 界面参考 |

> 上游项目的开源许可（见 [LICENSE](LICENSE)）继续适用。

---

## 支持

影屿是业余时间维护的独立项目。如果它对你有所帮助，欢迎扫码赞赏 —— 完全自愿，不影响任何功能。

<p align="center">
  <img src=".github/assets/wechat-reward.png" width="320" alt="微信赞赏码" />
</p>
