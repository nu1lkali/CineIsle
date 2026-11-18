# 影屿 CineIsle 1.6.48

版本 1.6.48（versionCode 292）　发布日期 2026-11-18

本版包含 **Emby STRM 直链播放** 与 **VLC 播放页重做**。

## Emby STRM 直链播放（默认关闭）

设置 → Emby 播放与数据同步 →「STRM 直链播放」。

- 只对 `.strm` 条目生效：起播前向服务器要一次 `PlaybackInfo`，
  取 `MediaSources[].Path` 里 strm 文件指向的远端 `http(s)` 地址，直接交给播放器；
- 不再经 `/Videos/{id}/stream` 中转，适合服务器 / 兼容层在 stream 端点不兼容的源；
- 拿不到直链、超时、不是 http(s) 地址 ⇒ **自动回退**原来的流地址，不会因此打不开；
- 服务器在 `RequiredHttpHeaders` 里声明了 UA / Referer 时一并下发给播放器；
- **mpv / GSY / VLC 三个内核共用同一条起播地址**，都支持；
- 详情页大按钮、分集列表、Emby 播放列表、随机播放入口都走同一条解析；
- 剧集连播 / 随机播放只解析**首条**，后续自动连播仍走原流地址（避免逐条请求和 CDN 风控）。

⚠️ 部分服务器不按这个口径返回 `Path`，或直链需要服务器侧签名 / 转码：
开启后可能无法播放。**仅在明确需要时开启**，平时保持关闭。

## VLC 播放页对齐 mpv

- 控件显隐改为**纯淡入淡出**，不再出现「往左上角收缩」的动画。
- 顶栏：返回键 + 标题胶囊 + 横竖屏 / 画中画，使用与 mpv 同一套玻璃按钮样式。
- 正中央：快退 / 播放暂停 / 快进；步长跟随 mpv 的「快进步长 / 快退步长」设置。
- 底部：mpv 同款进度条（含左右计时器、样式跟随设置）+ 倍速 / 音轨 / 字幕 / 画面比例快捷条。
- 新增 **横竖屏切换** 按钮：系统自动旋转关闭时也能强制横屏播放。
- 字幕改为 **mpv 同款字幕面板**：
  - 不再直接弹系统选择器；
  - 面板内列出内嵌字幕轨、当前选中状态；
  - 「添加外挂字幕」才打开文件选择器；
  - 支持关闭字幕、字幕延迟面板。
- 音轨改为同款面板，并新增「添加外部音轨」。
- 画面比例面板：自动 / 适应屏幕 / 填充 / 16:9 / 4:3 / 16:10 / 2.35:1 / 2.39:1 / 原始。
- 倍速面板支持 0.25x–3.00x + 常用档位。
- 从文件选择器返回时不会因 `onStop` 被误暂停。

## 同时包含

- 1.6.47：VLC 外挂字幕复制到 cache、native 预加载诊断、初始化错误复制。
- 1.6.46：VLC 初始化回退、设置项扩展。
- 1.6.45 / 1.6.44：VLC 独立内核、详情页长按选择 MPV/GSY/VLC、三列内核设置。

## 已知边界

- STRM 直链只解析当前起播条目；自动连播的后续集数仍走 Emby 流地址。
- 外部播放器入口仍使用 Emby 直连地址，不受本开关影响。
- VLC 暂未覆盖 mpv 的滤镜 / GLSL / 章节 / 第二字幕 / 弹幕 / 投屏。
- 无设备 / 模拟器：VLC 新控件布局、横屏、外挂音轨与 STRM 直链需要真机验证。
- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.48_2922-arm64-v8a-release-20261118-0924.apk`
- 大小：58,334,307 字节（约 55.6 MB，含 VLC native 库）
- SHA-256：`22DC69AAB53CA3A8DB16F1A9EB067358EF3D4A642ECD2E0C6A8B7D1EB74A9936`
- 包信息：`app.cineisle.player`，versionCode 2922，versionName 1.6.48，
  minSdk 26，targetSdk 36，arm64-v8a
- 已核验包含：`libvlc.so` / `libvlcjni.so` / `libc++_shared.so`；dex 中已包含
  STRM 直链、外挂音轨、字幕延迟等新增文案。
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- 单元测试：`:app:testStandardDebugUnitTest` 全部通过（含 `StrmDirectLinkTest`）。
