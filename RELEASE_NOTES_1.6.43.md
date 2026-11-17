# 影屿 CineIsle 1.6.43

版本 1.6.43（versionCode 287）　发布日期 2026-11-17

## HEVC Main10 卡死：优先尝试 mpv 零拷贝直出

上一版卡死后直接降软件解码；但如果 GSY / 系统播放器能正常播，说明设备的
MediaCodec 解码器本身是好的，问题更可能出在 mpv 的 **copyback 回拷路径**。

现在卡死恢复顺序改为：

1. **10-bit HEVC + copyback 硬解** → 先切 mpv 零拷贝直出：
   - `vo=mediacodec_embed`
   - `hwdec=mediacodec`
   - 这与 GSY / 系统播放器一样走 Surface 输出，不再整帧回拷；
   - 代价：mpv 侧字幕 / OSD / 截图不可用（Compose 播放器界面不受影响）。
2. 零拷贝仍卡 / 其它硬解路径 → 切软件解码 + 关闭 `vd-lavc-dr`。
3. 软解仍卡 → 播放错误卡片，提示手动切「软件解码」。

## 看门狗进度判断增强

- 仍然同时看 `time-pos` 与 `estimated-frame-number`；
- 新增 `video-pts` 作为帧号的兜底信号（部分容器/构建读不到帧号）；
- 暂停、缓冲、拖动、core-idle、EOF、idle 时都不判定；
- 直出 `vo=mediacodec_embed` 不依赖帧号 / video-pts，只看 time-pos；
- 4 秒无进展才触发；换片重置。

## 同时包含

- 1.6.40：Smart Merge 安全闭环、服务器测速、快速合并历史入口。
- 1.6.39：媒体库长按合并同番号，合并后就地更新列表。
- 1.6.23–1.6.38：Smart Merge、Classic UI、字幕归档、小窗模糊等。

## 安全边界（未变）

- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`，按钮禁用。
- 不做自动合并；非管理员只读。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.43_2872-arm64-v8a-release-20261117-2243.apk`
- 大小：36,293,955 字节（约 34.6 MB）
- SHA-256：`5DB355D57FA2D030F7698BB899A3B1D58047FD1B25BF22BDDB8D3A0A3EA136ED`
- 包信息：`app.cineisle.player`，versionCode 2872，versionName 1.6.43，
  minSdk 26，targetSdk 36，arm64-v8a
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- 单元测试：`:app:testStandardDebugUnitTest`、`:nextlib-patched:testDebugUnitTest` 全部通过
