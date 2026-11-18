# 影屿 CineIsle 1.6.47

版本 1.6.47（versionCode 291）　发布日期 2026-11-18

本版继续收口 VLC 第三内核，重点是「初始化失败」和外挂字幕可用性。

## VLC 初始化

- 启动前手动按 `c++_shared → vlc → vlcjni` 顺序加载 native 库：
  - libVLC 自己在加载失败时会直接 `System.exit(1)`（App 闪退、没有提示）；
  - 现在失败会变成可读的错误卡片，带 `dlopen failed` 的真实原因。
- `LibVLC(this, options)` 抛异常时自动回退 `LibVLC(this)` 默认实例，
  单个命令行选项不被 ROM / VLC 构建接受时不再整体失败。
- 初始化失败卡片现在显示**完整异常链**（外层类名 → cause → …），
  并保留「复制错误」按钮，方便直接发回完整现场。
  - 典型 native 失败会显示为
    `ExceptionInInitializerError ← UnsatisfiedLinkError: dlopen failed: ...`。
- 「重试」会重建内核并把新播放器重新挂到视频层，不再对空 player 重试。

## VLC 外挂字幕

- 选择外挂字幕后**先复制到应用 cache**，再通过
  `MediaPlayer.addSlave(Subtitle, file://, true)` 挂载：
  - 规避 libVLC 对 SAF `content://` 支持不稳定的问题；
  - 支持 srt / ass / ssa / vtt / sub / idx / txt；
  - 复制失败、VLC 拒收时都有明确 toast。
- 字幕弹窗新增「关闭字幕」。
- 切集 / 自动下一集后自动重新挂载同一个外挂字幕。
- 临时字幕文件 6 小时后自动清理。

## VLC 设置（1.6.46 起）

- 解码：硬件解码、丢晚帧、跳帧；
- 缓存：网络缓存、文件缓存；
- 画面：去隔行、默认画面比例（自动 / 16:9 / 4:3 / 填充）；
- 字幕：字号、底部边距；
- 音频/字幕延迟（±5000 ms）；
- 首选音轨 / 字幕语言；
- 后台播放；
- 额外 VLC 参数（每行一个）。

## 同时包含

- 1.6.46：VLC 初始化回退、设置扩展、错误复制。
- 1.6.45：VLC PiP 不暂停、首帧后上报、外挂字幕入口。
- 1.6.44：VLC 独立内核、详情页长按选 MPV/GSY/VLC、设置三列入口。
- 1.6.43：mpv 10-bit HEVC 卡死优先切零拷贝直出。

## 已知边界

- VLC 暂未覆盖 mpv 的滤镜 / 章节 / 第二字幕 / 弹幕 / 投屏 / 完整手势。
- 若回退后仍提示初始化失败，说明 native 库加载本身有问题：
  请点「复制错误」把完整异常发回（优先看 `dlopen failed` 后面的原因）。
- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.47_2912-arm64-v8a-release-20261118-0802.apk`
- 大小：58,324,823 字节（约 55.6 MB，含 VLC native 库）
- SHA-256：`7E893258F71707B2E92E5EAD3D5E107D3D7528CE02AB08A58563EA259D8A11A1`
- 包信息：`app.cineisle.player`，versionCode 2912，versionName 1.6.47，
  minSdk 26，targetSdk 36，arm64-v8a
- 已核验包含：`lib/arm64-v8a/libvlc.so`、`lib/arm64-v8a/libvlcjni.so`、
  `lib/arm64-v8a/libc++_shared.so`；dex 中已包含外挂字幕 / 设置项 / 错误报告文案。
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- 单元测试：`:app:testStandardDebugUnitTest`、`:nextlib-patched:testDebugUnitTest` 全部通过。
