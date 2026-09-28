<div align="center">

# 影屿 · CineIsle

**一个打通 Emby 的本地播放器 —— 基于 mpvEx 二次开发**

包名 `app.marlboroadvance.mpvex` · 构建变体 `standard`

</div>

---

## 项目背景

一开始用的是某款第三方 Emby 播放器。用着还行，但总有些自己想要的功能它没有，于是去提需求——作者不太愿意做，也没怎么回复。

那没辙了，自己做呗。

从零写一个播放器内核不现实，所以找了一个足够扎实的播放器做底座：**[mpvEx](https://github.com/marlboro-advance/mpvEx)**（底层是 mpv-android / libmpv）。mpvEx 本身是一个好用的**本地**播放器，但不带 Emby 客户端。

于是就有了 **影屿 / CineIsle**：在 mpvEx 上补出一整套 Emby 客户端，再把那些「自己用得上」的功能一个个加上去。

名字取自「电影的岛屿」——把自己想看的片子收在一个地方。

---

## 功能

### Emby 客户端（本项目从零实现）

mpvEx 原本只做本地文件与网络流播放，没有任何「媒体服务器」概念。本项目在它之上补出了一整套 Emby 客户端，**不只是把一个 URL 丢给 mpv**。

#### 服务器与账号

- 多服务器管理：添加 / 编辑 / 删除 / 切换；地址支持 `http://主机:端口` 与 `https://域名` 两种写法（自动解析协议、主机、端口）
- 用户名 + 密码登录，走 `POST /emby/Users/AuthenticateByName` 拿 `AccessToken`；**登录成功才入库**，避免存下无效配置
- 服务器配置用 Room 持久化（新增 `emby_servers` 表与 DAO），当前选中服务器另记一处，重启后自动恢复
- 登录态自检与自动重登：`ensureLoggedIn` / `relogin`，Token 失效时不至于整个界面变砖
- `GET /System/Info/Public` 探测服务器信息

#### 导航结构

底部五个 Tab，前三个都是 Emby：**首页 / 收藏 / 历史**；后两个保留上游的**文件夹 / 网络流**。

- 收藏：`IsFavorite` 过滤的收藏列表
- 历史：`GET /Users/{UserId}/Items` 取播放历史，行式布局带进度

#### 首页

- **媒体库入口**：横排大卡片，显示库名、条目数、库封面；按 `CollectionType`（电影 / 剧集 / 音乐…）配图标
- **继续观看**：宽幅卡片 + 底部播放进度条，左下角还有「剩余 XX分XX秒」深蓝角标
- **最新加入**：海报卡片，`Primary` 取不到图时自动回退 `Thumb`
- 加载中 / 空状态 / 错误重试三种状态齐全

#### 媒体库浏览

- **分类**：全部 / 继续播放 / 合集 / 收藏 / 文件夹
- **排序**：名称 / 加入时间 / 播放时间 / 发行年份 / 首播日期 / 评分 / 影评评分 / 时长 / 播放次数 / 随机（参数值与 Emby `/Items` 的 `SortBy` 对齐）
- **卡片样式**：海报 / 背景图 / 横幅，比例与取图类型（`Primary` / `Thumb`）联动
- **文件夹下钻**：容器类条目（`Folder` / `CollectionFolder` / `UserView` / `BoxSet`）自身没有 `Primary` 图，用内部子媒体的缩略图拼 2×2 宫格封面
- **搜索**：库内搜索框，输入后 400ms 防抖再发起查询
- **状态保持**：排序方式与卡片样式持久化，重进 App 仍沿用；列表结果 + 滚动位置做进程内缓存，从详情页或播放器返回时不重新发请求、并停回原来那一屏
- 下拉刷新

#### 详情页

- 顶部 Backdrop 剧照 + 标题 / 副标题 / 评分 / 时长等 meta 摘要
- **播放区**：根据 `UserData.PlaybackPositionTicks` 显示上次进度，按钮文案变成「继续播放 · mm:ss」；真看过的片子额外给「从头播放 / 继续上次」两个按钮
- **剧集**：季 / 集两层结构，逐季展开选集
- **媒体信息**：容器、视频编码、分辨率、帧率、码率、音频编码与声道、字幕轨、文件大小、加入时间、首播日期（解析 `MediaStreams`，语言标签本地化）
- **媒体操作**：收藏 / 取消收藏、标记已播放 / 未播放、删除媒体（带二次确认）

#### 播放与进度同步

- **直链播放**：`GET /emby/Videos/{Id}/stream?static=true`，不转码、交给本地 mpv 解码，URL 内自带 `api_key`
- **剧集连播**：把同一季 / 同一剧集的集号拼成播放队列，随 intent 传入 `playlist` 与 `playlist_titles`，因此切集时标题正确，随机与循环也能正常工作
- **进度回传**：`/Sessions/Playing`（开始）、`/Sessions/Playing/Progress`（每 10 秒一次）、`/Sessions/Playing/Stopped`（暂停 / 退出 / 切集），手机、电视、网页之间进度互相同步
- **播放页内的 Emby 操作**：播放器工具栏的 Emby 收藏按钮；切集时自动刷新当前条目的收藏 / 已看状态
- **外挂字幕**：`GET /Videos/{Id}/{MediaSourceId}/Subtitles/{Index}/Stream.srt` 拉下来交给 mpv
- **图片按需取尺寸**：`Primary` / `Backdrop` / `Thumb` 按卡片宽度请求合适尺寸，少下流量

#### 实现分层

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 协议 | `domain/emby/EmbyClient.kt` | 裸 HTTP：鉴权头、各 API 端点、JSON 解析、URL 拼接 |
| 仓库 | `domain/emby/EmbyRepository.kt` | 服务器增删改查、登录态维护、统一错误处理 |
| 模型 | `domain/emby/EmbyServer.kt` | 服务器与 Token 的持久化模型（Room 实体） |
| 界面 | `ui/browser/emby/*Screen.kt` | 首页 / 库 / 详情 / 收藏 / 历史 / 服务器管理 |
| 桥接 | `EmbyPlayerActions` `EmbyPlaybackReporter` | 播放器与 Emby 之间：收藏状态、进度上报 |

### 播放体验

- **视频预加载**：当前视频播放 2 秒后，后台对下一个视频的流地址发一个带 `Range` 头的请求，预取开头约 2MB。切集时首帧来得更快，观感更接近「无缝」。可在「设置 → 播放器」里开关，默认关闭（预加载会额外占一点带宽，弱网 / 流量环境请自行决定）。
- **切集不再重置屏幕方向**：以前横屏看片时切下一集，会被偏好设置（比如竖屏）强行拉回去。现在旋转按钮指定的方向会在本次播放会话内保留。
- **随机播放标题修复**：随机播放进入时，除第一个之外的视频标题都显示成 `stream`。根因是 Emby 的流地址末段固定为 `/stream`，播放器只能从 URL 猜片名。改为由发起播放的一方把真实标题列表随 intent 传进来，切集时优先使用。

### 界面

- **竖屏底部控件精简**：把随机播放、画面缩放、显示比例等不常用的按钮收进「更多」，竖屏下不再一堆按钮抢视觉。
- **首页媒体库标题位置修正**：标题落在封面底部遮罩的正中区域。
- **继续观看剩余时长角标**：首页「继续观看」每张缩略图左下角显示「剩余 XX分XX秒」，深蓝色底。
- **启动页**：冷启动显示 logo + 应用名（影屿 / CineIsle）。
- **关于页**：移除捐赠与更新入口，标题改为项目名，保留对 mpvEx 的致谢。

### 中文本地化

补齐了大量此前仍是英文的界面文案：

- 滤镜预设名与说明（12 个预设）
- 播放配置名（高质量 / GPU 高画质 / 低延迟 / 软件快速等）
- 进度条样式名（标准 / 波浪 / 粗条）
- 播放器控件名（返回 / 视频标题 / 章节 / 播放速度 / 解码器 …）
- 网络页（stream link / local network 等）
- 播放器设置、手势设置、高级设置，以及各类说明文本

下拉枚举以前会直接露出英文常量名，现在统一走 `valueToText` 显示本地化文案。

### 继承自 mpvEx

本地文件播放、Material 3 Expressive 界面、SMB / FTP / WebDAV、外部音轨与字幕、手势控制、画中画、后台播放、Anime4K 着色器等上游能力全部保留。

---

## 构建

### 前置条件

- JDK 17
- Android SDK（build tools 34.0.0+）
- Git

### 打包 arm64-v8a

```bash
./gradlew.bat --no-daemon -Pabi=arm64-v8a --console=plain :app:assembleStandardDebug
```

产物位于 `app/build/outputs/apk/standard/debug/`。

可选 ABI：`arm64-v8a`（现代 64 位 ARM 设备，推荐）、`armeabi-v7a`、`x86`、`x86_64`、`universal`。

> 依赖同时来自 Google Maven、Maven Central 与 JitPack。若本地 Gradle 全局配置里挂了「只走单一镜像」的 init 脚本，会导致仅在 Maven Central 上的依赖（如 `io.github.marlboro-advance:mpv-android`）解析失败，请先移除。

---

## 致谢

本项目站在这些项目的肩膀上：

- [mpvEx](https://github.com/marlboro-advance/mpvEx) —— 直接的上游，本项目在此之上二开
- [mpv-android](https://github.com/mpv-android) —— mpv 绑定与 libmpv 集成
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)

上游项目的开源许可（见 [LICENSE](LICENSE)）继续适用。

---

## 说明

这是个人自用向的二次开发分支，功能以「自己用得上」为准，不保证与上游同步，也不承诺兼容性或长期维护。
