# 影屿 CineIsle 版本汇总：1.6.44 – 1.6.49

日期：2026-11-18

这六个版本围绕三件事展开：**新增并稳定 VLC 第三内核**、**VLC 播放页对齐 mpv**、**Emby STRM 直链播放（opt-in + 安全回退）**。

## 版本总览

| 版本 | versionCode | 一句话 | 最终 APK |
| --- | --- | --- | --- |
| 1.6.44 | 2882 | 新增 VLC 独立内核、长按选内核、三列内核设置 | `CineIsle-v1.6.44_2882-arm64-v8a-release-20261118-0013.apk` |
| 1.6.45 | 2892 | VLC PiP / 上报 / 外挂字幕入口收口 | `CineIsle-v1.6.45_2892-arm64-v8a-release-20261118-0025.apk` |
| 1.6.46 | 2902 | VLC 初始化回退、设置扩展、外挂字幕落地 | `CineIsle-v1.6.46_2902-arm64-v8a-release-20261118-0746.apk` |
| 1.6.47 | 2912 | native 预加载诊断、错误复制、字幕复制缓存 | `CineIsle-v1.6.47_2912-arm64-v8a-release-20261118-0802.apk` |
| 1.6.48 | 2922 | Emby STRM 直链播放、VLC 播放页对齐 mpv | `CineIsle-v1.6.48_2922-arm64-v8a-release-20261118-0924.apk` |
| 1.6.49 | 2932 | VLC 手势 / 竖屏标题 / 控件补齐，STRM 直链安全预检 | `CineIsle-v1.6.49_2932-arm64-v8a-release-20261118-1004.apk` |

---

## 1.6.44 — VLC 第三内核

- 新增 **VLC 独立内核**，与 mpv / GSY 完全解耦：
  - 独立 `VlcPlayerActivity`、`VlcPreferences`（`vlc_` 前缀）、`VlcPreferencesScreen`；
  - libVLC 3.7.6（LGPL 2.1），AAR 内含各 ABI native so；
  - 不修改 mpv / GSY 播放页，避免内核问题互相牵连。
- VLC 覆盖：播放/暂停/拖动/倍速、音轨与内嵌字幕轨、外挂字幕选择、画面比例、
  画中画、播放列表自动下一集、Emby 进度回传。
- **详情页长按播放**改为内核选择：`MPV / GSY / VLC`，单击仍用默认内核。
- 设置 → 播放与控件 →「播放内核设置」：顶部三列 MPV / GSY / VLC，各自独立配置。
- 同时包含 1.6.43（mpv 10-bit HEVC 卡死优先零拷贝直出）、1.6.40（Smart Merge 安全闭环 / 服务器测速）、
  1.6.39（媒体库长按合并同番号）。

## 1.6.45 — VLC 收口

- 画中画进入过程中不再被 `onStop` 误暂停；退出 PiP 后恢复。
- Emby 播放上报改为**视频层 attach + 首帧起播后**才开始，避免初始化失败也记成「已开始播放」。
- 外挂字幕文件选择入口：选中后通过 `MediaPlayer.addSlave(...)` 挂载。

## 1.6.46 — 初始化失败修复 + 设置扩展

- `LibVLC(this, options)` 抛异常时回退 `LibVLC(this)` 默认实例，
  个别 ROM / VLC 构建不认某些选项时不再整体失败。
- 初始化失败错误卡片带异常 message。
- VLC 设置补齐：跳帧、后台播放、默认画面比例、字幕底部边距、额外 VLC 参数；
  原有硬解 / 网络与文件缓存 / 去隔行 / 丢晚帧 / 字幕字号 / 音轨与字幕语言。
- native 核对：VLC 所需 C++ 符号在 app 侧 `libc++_shared.so` 全部可解析，
  LOAD 对齐 0x4000（16 KB 页兼容）。

## 1.6.47 — 诊断与外挂字幕可用性

- 启动前手动按 `c++_shared → vlc → vlcjni` 顺序加载 native 库：
  libVLC 加载失败原本会 `System.exit(1)` 直接闪退，现在变成可读错误卡片，
  带 `dlopen failed` 真实原因。
- 初始化失败显示完整异常链，`复制错误` 附带版本 / 设备 / ABI。
- 「重试」会重建内核并重新挂到视频层。
- 外挂字幕**先复制到 cache** 再以 `file://` 挂给 VLC，规避 `content://` 兼容问题；
  支持 srt / ass / ssa / vtt / sub / idx / txt，切集自动重挂，临时文件 6 小时后清理。
- 字幕弹窗新增「关闭字幕」。

## 1.6.48 — STRM 直链 + VLC 播放页重做

### Emby STRM 直链播放（默认关闭）

- 设置 → Emby 播放与数据同步 →「STRM 直链播放」。
- 只对 `.strm` 条目生效：起播前请求一次 `PlaybackInfo`，取
  `MediaSources[].Path` 中 strm 文件指向的远端 http(s) 地址，直接交给播放器；
  不再经 `/Videos/{id}/stream` 中转。
- 拿不到直链 / 超时 / 非 http(s) ⇒ 自动回退原流地址。
- 服务器声明的 `RequiredHttpHeaders`（UA / Referer）一并下发。
- **mpv / GSY / VLC 三内核共用同一条起播地址**；详情页大按钮、分集列表、
  Emby 播放列表、随机播放入口都接入；只解析首条，自动连播后续仍走原流。

### VLC 播放页对齐 mpv

- 控件显隐改为纯淡入淡出，不再「往左上角收缩」。
- 复用 mpv 玻璃按钮与 `SeekbarWithTimers`：顶栏返回 + 标题胶囊 + 横竖屏 / 画中画；
  中央快退 / 播放 / 快进；底部进度条 + 倍速 / 音轨 / 字幕 / 比例。
- 字幕改为 **mpv 同款字幕面板**：列出内嵌轨、筛选、「添加外挂字幕」才开系统选择器；
  支持关闭字幕、字幕延迟面板。
- 音轨同款面板 + 添加外部音轨；比例面板覆盖 9 种比例；倍速 0.25x–3x。
- 从文件选择器返回不会因 `onStop` 被误暂停。

## 1.6.49 — 手势与 STRM 安全

### VLC

- 手势：**横滑调进度**（带目标时间与 `+/-` HUD，松手才 seek）、**左侧竖滑亮度**、
  **右侧竖滑音量**、**单击显隐控件**；灵敏度与左右交换沿用 mpv 设置。
- 竖屏标题移到进度条上方、整行跑马灯，长片名可完整显示；有队列时显示 `1/12`。
- 新增**播放列表**按钮与**音频延迟**面板。

### STRM 直链安全修复

- 直链交给播放器前做 **1.5 秒可达性预检**（`Range: bytes=0-0`，带服务器声明的 UA / Referer）：
  401 / 403 / 404 / 410 或连不上 ⇒ 放弃直链，回退正常 Emby 流。
  内网 / 失效地址不会再拖死播放。
- 设置新增**「关闭所有直链优化」**：一键把 STRM 直链与 302 预解析都写回关闭。
- 两个开关都关仍失败时，通常是 Emby `/Videos/{id}/stream` 自身 302 到手机不可达的内网地址；
  使用详情页「服务器转码播放」或修改 strm 地址。

---

## 构建归档

| 版本 | versionCode | 大小（字节） | SHA-256 |
| --- | --- | --- | --- |
| 1.6.44 | 2882 | 58,320,735 | `A82EE42D1F2B91443C63EC727A65CB212AD0F849133AED15F29A14B2001C0241` |
| 1.6.45 | 2892 | 58,320,891 | `4BE388FBE946A78E36D763EF1E037F7E2F59C566FD013BE7BB153104A833DA46` |
| 1.6.46 | 2902 | 58,323,899 | `1FF461342CD4A201FC9FC5790DCC5104D49A7392770D09D77A642CB39F45B4B5` |
| 1.6.47 | 2912 | 58,324,823 | `7E893258F71707B2E92E5EAD3D5E107D3D7528CE02AB08A58563EA259D8A11A1` |
| 1.6.48 | 2922 | 58,334,307 | `22DC69AAB53CA3A8DB16F1A9EB067358EF3D4A642ECD2E0C6A8B7D1EB74A9936` |
| 1.6.49 | 2932 | 58,342,019 | `57164C1C6270ED344259C11CFB61B9DC9DD795A479C8E11C19CB7F50E9983060` |

- 包信息：`app.cineisle.player`，minSdk 26，targetSdk 36，arm64-v8a。
- 签名：apksigner 验证通过，证书 DN `CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`，
  SHA-256 `657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`。
- 单元测试：`:app:testStandardDebugUnitTest` 全部通过（1.6.48 起含 `StrmDirectLinkTest`）。

## 已知边界（截至 1.6.49）

- VLC 暂未覆盖 mpv 的滤镜 / GLSL / 章节 / 第二字幕 / 弹幕 / 投屏。
- STRM 直链只解析当前起播条目；外部播放器不受直链开关影响。
- Smart Merge 拆分接口未验证：`SMART_MERGE_SPLIT_VERIFIED = false`；不做自动合并，非管理员只读。
- 无设备 / 模拟器：VLC 手势、硬解、字幕、PiP、播放列表、STRM 直链需真机验证。
