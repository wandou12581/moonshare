# MoonShare（Android 分享端）

Kotlin + 平台 API，无第三方运行时依赖（不需要 AndroidX）。采集屏幕并通过 H.264/UDP
发送给 MoonView。

## 构建

用 Android Studio 打开本目录，或：

```powershell
gradle :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

要求：SDK 35、JDK 17、Gradle 8.9+。详见 [../docs/BUILD.md](../docs/BUILD.md)。

## 结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | 配对码/地址 UI、录屏授权、前台服务控制 |
| `ScreenShareService.kt` | 前台服务、会话授权、统计、通知 |
| `capture/ScreenCapture.kt` | VirtualDisplay + MediaCodec H.264 |
| `net/ControlServer.kt` | TCP 47800 配对/心跳 |
| `net/UdpVideoTransport.kt` | UDP 47801 双栈接收 HELLO + 分片发送 |
| `net/DiscoveryResponder.kt` | UDP 47802 IPv4 发现响应 |
| `protocol/Protocol.kt` | 报文常量、分片头、大小端工具 |
| `util/NetworkUtils.kt` | 本机 IPv4/IPv6 地址枚举 |

## 权限/兼容性

- Android 8.0+（minSdk 26）
- Android 14+ 使用 `mediaProjection` 前台服务类型
- 每次开始共享需系统录屏授权
- 分享期间保持屏幕常亮
- 旋转后建议重新开始共享（v1 固定虚拟显示尺寸）
