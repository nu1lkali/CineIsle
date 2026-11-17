# 影屿 CineIsle 1.6.45

版本 1.6.45（versionCode 289）　发布日期 2026-11-18

本版是 VLC 第三内核的收口版。

## VLC 修复

- 画中画进入过程中不再被 `onStop` 误暂停；退出 PiP 后恢复播放。
- Emby 播放上报改为**视频层 attach + 首帧起播后**才开始，避免 VLC 初始化失败也记成
  「已开始播放」。
- 外挂字幕文件选择：VLC 播放页底部新增「外挂字幕」，选中的文件通过
  `MediaPlayer.addSlave(...)` 挂载。
- 播放列表 / 下一集、音轨 / 字幕轨、倍速、画面比例、PiP、进度回传保持。

## 同时包含 1.6.44

- VLC 独立内核（libVLC 3.7.6，LGPL 2.1），独立 `VlcPlayerActivity` 与
  `VlcPreferences`，不改 mpv / GSY 播放页。
- 详情页长按播放弹出「MPV / GSY / VLC」选择。
- 设置 → 播放与控件 →「播放内核设置」：顶部三列 MPV / GSY / VLC，
  分别进入各自设置页。
- VLC 设置：硬解、网络/文件缓存、去隔行、丢晚帧、字幕字号、音轨/字幕语言。

## 已知边界

- VLC AAR 使 APK 增大到约 55.6 MB（arm64-v8a）。
- VLC 暂未覆盖 mpv 的滤镜 / 章节 / 第二字幕 / 弹幕 / 投屏 / 后台播放。
- 无设备/模拟器：VLC 硬解、字幕、PiP、播放列表需真机回归。
- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.45_2892-arm64-v8a-release-20261118-0025.apk`
- 大小：58,320,891 字节（约 55.6 MB，含 VLC native 库）
- SHA-256：`4BE388FBE946A78E36D763EF1E037F7E2F59C566FD013BE7BB153104A833DA46`
- 包信息：`app.cineisle.player`，versionCode 2892，versionName 1.6.45，
  minSdk 26，targetSdk 36，arm64-v8a
- 已核验包含：`lib/arm64-v8a/libvlc.so`、`lib/arm64-v8a/libvlcjni.so`、
  `lib/arm64-v8a/libc++_shared.so`
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- 单元测试：`:app:testStandardDebugUnitTest`、`:nextlib-patched:testDebugUnitTest` 全部通过
