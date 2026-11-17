# 影屿 CineIsle 1.6.40

版本 1.6.40（versionCode 284）　发布日期 2026-11-17

## Smart Merge 安全闭环

- **同服务器校验**：候选的 `serverId` 与当前服务器不一致时直接拒绝，切换服务器后
  不会拿旧候选误操作。
- **同媒体库校验**：扫描/合并时识别条目的媒体库 Id；跨库候选会标冲突，合并阶段
  直接拒绝。Emby 的跨库多版本语义尚未验证，宁可先挡住。
- **后缀变体冲突**：`ABC-123` / `ABC-123B` 这类主体相同、后缀不同的条目，现在会
  明确标注「番号后缀不同」并封顶 69 分，不再静默同组。
- **扫描 / 合并互斥**：扫描进行中拒绝合并，合并进行中拒绝扫描；同时只允许一个
  合并任务，避免两边同时改本地账本。
- **JSON 账本加锁**：`SmartMergeJournal` 的 load / save / prune / 快照读写统一
  串行化，扫描、合并、历史更新并发时不会互相覆盖。
- **「查看智能合并历史」入口**：媒体库完成一次快速合并后，顶栏出现历史图标，
  可直接进入「智能媒体合并」查看结果。

## Emby 服务器测速

服务器管理页每台已登录服务器新增「测速」按钮：

- 先做一次不计时的预热（DNS / TLS / 连接池）；
- 再连续请求当前用户资料接口，取平均 / 最低 / 最高延迟；
- 结果直接显示在服务器卡片上，失败时显示具体原因；
- 用 `/Users/{id}` 而不是 `/System/Info`，普通账号也能测，不会被 403 误判。

## 同时包含

- 1.6.39：媒体库长按「合并同番号」，合并后不刷新、就地移除被合并卡片。
- 1.6.38：小窗自绘模糊背景、详情页/小窗版本选择底部抽屉。
- 1.6.23–1.6.37：Smart Merge、Classic UI、字幕归档、视界流 mpv 修复等
  （详见 `RELEASE_NOTES_1.6.23-1.6.39.md`）。

## 安全边界（未变）

- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`，按钮禁用。
- 不做自动合并；所有组合必须人工确认。
- 非管理员只读。
- 合并后的 ItemId / 元数据 / 收藏 / 播放进度归属由 Emby 决定；建议先在测试库验证。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.40_2842-arm64-v8a-release-20261117-2147.apk`
- 大小：36,292,491 字节（约 34.6 MB）
- SHA-256：`F359B69FDE0FD35FD72B5B4E8D3834ACD17263DA99BF36919AE1D9AB97EB9E1E`
- 包信息：`app.cineisle.player`，versionCode 2842，versionName 1.6.40，
  minSdk 26，targetSdk 36，arm64-v8a
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- 单元测试：`:app:testStandardDebugUnitTest`、`:nextlib-patched:testDebugUnitTest` 全部通过
