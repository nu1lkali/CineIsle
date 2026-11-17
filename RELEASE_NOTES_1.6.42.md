# 影屿 CineIsle 1.6.42

版本 1.6.42（versionCode 286）　发布日期 2026-11-17

本版是 1.6.41 的播放看门狗修正版。

## 解码卡死看门狗

针对 HEVC Main10 / 10-bit 等「先出 1～2 秒画面、随后冻住」的问题：

- 同时观察 `time-pos` 与 `estimated-frame-number`，4 秒没有进展才判定卡死；
- MediaCodec 卡住 → 自动切 GPU 渲染 + `hwdec=no` + 关闭 `vd-lavc-dr`；
- 已是软解仍卡 → 先关 `vd-lavc-dr`，再卡则显示播放错误并提示手动切软解；
- 暂停、缓冲、拖动进度、**片尾 EOF / idle** 都不会触发误判；
- 直出 `vo=mediacodec_embed` 不依赖帧号；
- 降级细节写入 `PlayerDiagLog` 的 `stall-watchdog`。

## 同时包含

- 1.6.40：Smart Merge 安全闭环、Emby 服务器测速、快速合并历史入口。
- 1.6.39：媒体库长按合并同番号，合并后就地更新列表。
- 1.6.23–1.6.38：Smart Merge、Classic UI、字幕归档、小窗模糊等。

## 安全边界（未变）

- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`，按钮禁用。
- 不做自动合并；非管理员只读。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.42_2862-arm64-v8a-release-20261117-2223.apk`
- 大小：36,293,787 字节（约 34.6 MB）
- SHA-256：`D6C1AA7EE793E15FFE9C9EAFABB71F464DD63B7B13F4EBACEC04587D5148BC2B`
- 包信息：`app.cineisle.player`，versionCode 2862，versionName 1.6.42，
  minSdk 26，targetSdk 36，arm64-v8a
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- 单元测试：`:app:testStandardDebugUnitTest`、`:nextlib-patched:testDebugUnitTest` 全部通过
