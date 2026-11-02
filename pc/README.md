# MoonView（Windows 查看端）

WPF 原生窗口 + WebView2/WebCodecs 解码。只接收画面，不发送任何输入。

## 构建/运行

```powershell
cd pc
dotnet build .\MoonView.sln -c Release
.\MoonView\bin\Release\net7.0-windows\MoonView.exe
```

依赖 .NET 7 SDK（或 Desktop + ASP.NET Core Runtime 7.0）、WebView2 Runtime。
详见 [../docs/BUILD.md](../docs/BUILD.md)。

## 结构

| 文件 | 作用 |
|---|---|
| `MainWindow.xaml(.cs)` | 窗口、设备发现、连接状态、统计、全屏 |
| `Services/ShareClient.cs` | TCP 配对、UDP HELLO、视频接收、心跳 |
| `Services/FrameAssembler.cs` | `MSV1` 分片重组与丢帧恢复 |
| `Services/ViewerServer.cs` | 127.0.0.1 HTTP + WebSocket，把帧交给页面 |
| `web/app.js` | WebCodecs `VideoDecoder` + Canvas |
| `Services/DiscoveryService.cs` | IPv4 广播发现 |
| `lib/webview2/` | 可离线构建的 WebView2 SDK 组件 |

## 命令行联调

```powershell
MoonView.exe --host 127.0.0.1 --port 47800 --code 123456
```

## 已知限制

- 单观看者
- IPv6 需手动输入地址（链路本地需 scope id）
- 依赖 WebView2 的 H.264 解码能力
- v1 不加密，只在可信局域网使用
