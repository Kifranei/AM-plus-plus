# 媒体控制与词幕集成

AM++ 设置的「功能」中提供两个独立开关，均默认关闭，完全停止并重新打开 Apple Music 后生效。关闭时不安装对应功能的 hooks，也不注册词幕提供器。

## 使用 iOS 媒体控制按钮

开启后，播放页的 Chromecast 按钮使用 iOS 媒体控制按钮图标，点击打开系统音频输出面板。保留原有 View 和 DataBinding，并保持宿主的主题着色。

Xiaomi / Redmi / POCO 优先尝试 MIUI 妙播中心；其他设备及妙播不可用时，Android 14 起尝试 AOSP 系统输出面板；不可用时打开蓝牙设置。

## 词幕集成

开启后，通过 LyricProvider 0.1.70 向词幕提供 Apple Music 原生歌词及 AM++ 替换歌词，包含逐词时间轴、可用翻译、背景人声、演唱者对齐与系统播放进度。需要已安装并启用词幕的中央服务。

翻译使用 Apple Music 系统歌词语言；无翻译的歌词不会自动生成翻译。逐词文本按整行歌词恢复空白与标点，时间轴保持不变；无法对齐时回退到整行文本。保留 v1/v2 和 x-bg 映射。

原生歌词指针在宿主回调中同步复制，只将歌词数据交给异步发送。缓存最多 16 首歌曲，迟到的上一首歌词不会覆盖当前歌曲。进度定时器仅在播放且屏幕开启时运行。

## 支持范围与验证

两项功能仅为 Apple Music `7.0.0-beta / 1606` 配置了精确适配合约。其他版本开启后报告 Unsupported。

2026-10-05 在 Xiaomi 设备、词幕 fork `1.0.40-rc2 / 53` 上验证了图标替换、MIUI 妙播中心打开和词幕歌词显示。使用 `ME!`（Taylor Swift，ID `1468058706`）验证：74 行带翻译和多词时间轴，逐词拼接与整行文本一致，状态栏可见中文翻译及正常英文空格。v1/v2 和 x-bg 显示已通过使用者验收。AOSP 面板与外部设备输出切换尚未实机验证。

自动测试覆盖配置默认值、序列化、开关独立性、关闭时不安装功能、设置保存失败重试、原生向量解析、翻译与背景人声映射、逐词空格恢复及歌曲缓存状态。

构建与验证入口：

```powershell
.\gradlew.bat :app:assembleRelease :core:jvmTest :host-applemusic:testDebugUnitTest :app:testDebugUnitTest
python scripts/verify-architecture.py
python scripts/verify-profile-data.py
python scripts/verify-host-profile.py <apple-music-1606-base.apk> --glass
git diff --check
```

SDK 来源及许可证见 [第三方声明](../THIRD_PARTY_NOTICES.md)。
