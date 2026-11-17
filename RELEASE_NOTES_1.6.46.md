# 影屿 CineIsle 1.6.46

版本 1.6.46（versionCode 290）　发布日期 2026-11-18

## VLC 初始化失败修复

- `LibVLC(this, options)` 抛异常时自动回退 `LibVLC(this)` 默认实例：
  - 个别 ROM / VLC 构建对某个命令行选项不认，不再直接显示「VLC 内核初始化失败」；
  - 回退只影响 VLC 参数，不影响 mpv / GSY。
- 初始化失败时错误卡片会带上异常 message，方便继续定位 native 加载问题。

## VLC 设置扩展

新增/补齐：

- 跳帧（与丢晚帧分开）；
- 后台播放（切后台不自动暂停；当前没有前台服务，系统仍可能杀进程）；
- 默认画面比例（自动 / 16:9 / 4:3 / 填充）；
- 字幕底部边距；
- 额外 VLC 参数（每行一个，可填 VLC 原生选项）；
- 原有：硬解、网络/文件缓存、去隔行、丢晚帧、字幕字号、音轨/字幕语言。

## VLC 外挂字幕

- 播放页底部「外挂字幕」按钮：打开系统文件选择器，选中后通过
  `MediaPlayer.addSlave(Subtitle, uri, true)` 挂载；
- 内嵌字幕轨仍在「字幕」按钮里切换；
- 选中的文件会尝试保留持久读取权限。

## 同时包含

- 1.6.45：VLC PiP 不暂停、首帧后上报、外挂字幕入口。
- 1.6.44：VLC 独立内核、详情页长按选 MPV/GSY/VLC、设置三列入口。
- 1.6.43：mpv 10-bit HEVC 卡死优先切零拷贝直出。

## 已知边界

- VLC 暂未覆盖 mpv 的滤镜 / 章节 / 第二字幕 / 弹幕 / 投屏；
- 如果回退后仍提示初始化失败，说明 native 库加载本身有问题，请把错误卡片里的完整
  message 发出来（通常是 `dlopen failed: ...`）。
- 拆分接口仍未验证：`SMART_MERGE_SPLIT_VERIFIED = false`。

## 构建归档

- 文件：`G:\apk_release\CineIsle-v1.6.46_2902-arm64-v8a-release-20261118-0746.apk`
- 大小：58,323,899 字节（约 55.6 MB，含 VLC native 库）
- SHA-256：`1FF461342CD4A201FC9FC5790DCC5104D49A7392770D09D77A642CB39F45B4B5`
- 包信息：`app.cineisle.player`，versionCode 2902，versionName 1.6.46，
  minSdk 26，targetSdk 36，arm64-v8a
- 签名：apksigner 验证通过
  - 证书 DN：`CN=CineIsle, OU=CineIsle Mobile, O=CineIsle, L=Shenzhen, ST=Guangdong, C=CN`
  - 证书 SHA-256：`657c794a75415cded68096c5e67e4397db07f876179219badae5b5a2481f7f3d`
  - 证书 SHA-1：`491b620c7db626899a7b55974c89688f3776cdc6`
- native 库依赖核对：`libvlc.so` / `libvlcjni.so` 的 C++ 未定义符号在 app 侧
  `libc++_shared.so` 中全部可解析；两者 SONAME 均为 `libc++_shared.so`、
  LOAD 对齐均为 0x4000（16 KB 页兼容）。
- 单元测试：`:app:testStandardDebugUnitTest`、`:nextlib-patched:testDebugUnitTest` 全部通过。
