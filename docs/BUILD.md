# 构建与运行

## PC 查看端（Windows）

### 依赖

- Windows 10 1903+ / Windows 11
- [.NET 7 SDK](https://dotnet.microsoft.com/download/dotnet/7.0)（构建）
- .NET 7 **Desktop Runtime** 和 **ASP.NET Core Runtime 7.0**（运行构建产物）。
  安装 .NET 7 SDK 会同时包含两者；只装 Desktop Runtime 时 `MoonView.exe` 会提示缺少
  `Microsoft.AspNetCore.App`。
- Microsoft Edge WebView2 Runtime（Windows 11 通常自带；Windows 10 可从 Microsoft 官网安装）

仓库的 `pc/global.json` 固定使用 .NET SDK `7.0.410`。若你只安装了更高版本 SDK，
可以删除/修改 `global.json`，但本项目按 `net7.0-windows` 编译。

### 构建

```powershell
cd pc
dotnet restore .\MoonView.sln
dotnet build .\MoonView.sln -c Release
```

产物：

```
pc/MoonView/bin/Release/net7.0-windows/MoonView.exe
```

运行：

```powershell
.\MoonView\bin\Release\net7.0-windows\MoonView.exe
```

### WebView2 依赖的两种模式

`MoonView.csproj` 优先使用仓库内 `pc/MoonView/lib/webview2/` 的 SDK 组件；
如果该目录不存在，则回退到 NuGet 的 `Microsoft.Web.WebView2` 包。两种方式都不影响
最终用户机器上的 WebView2 **运行时**，运行时由系统/Edge 提供。

如果需要重新获取 SDK：

```powershell
dotnet add .\MoonView\MoonView.csproj package Microsoft.Web.WebView2 --version 1.0.3179.45
```

然后删除 `lib/webview2`（或保留，优先使用本地副本）。

### 发布

框架依赖发布（推荐，体积小）：

```powershell
dotnet publish .\MoonView\MoonView.csproj -c Release -r win-x64 --self-contained false -o .\dist
```

把 `dist/` 整个目录复制到目标电脑即可。`web/`、`Assets/` 和 `WebView2Loader.dll` 必须与
`MoonView.exe` 保持相对位置。

### Windows 防火墙

首次连接时允许 MoonView 访问**专用网络**即可。也可以手动放行：

```powershell
New-NetFirewallRule -DisplayName "MoonShare TCP 47800" -Direction Inbound -Protocol TCP -LocalPort 47800 -Action Allow -Profile Private
New-NetFirewallRule -DisplayName "MoonShare UDP 47801" -Direction Inbound -Protocol UDP -LocalPort 47801 -Action Allow -Profile Private
New-NetFirewallRule -DisplayName "MoonShare Discovery 47802" -Direction Inbound -Protocol UDP -LocalPort 47802 -Action Allow -Profile Private
```

安卓分享端使用的是**手机主动发起的 TCP 连接**和**PC 先发起的 UDP HELLO**，因此 PC 侧的
出站规则通常不需要额外配置；入站 UDP 47801 仍需放行，否则收不到视频。

### 运行日志与配置

正常路径：

```
%LOCALAPPDATA%\MoonShare\logs\moonview.log
```

如果 Windows 策略拒绝该目录写入（受控文件夹/受限令牌环境），程序会自动回退到：

```
%TEMP%\MoonShare\logs\moonview.log
```

再不行则回退到 `MoonView.exe` 同目录的 `MoonShareData\`。设置文件 `settings.json`
和 WebView2 用户数据目录使用同一根目录。

### 命令行（联调）

```powershell
MoonView.exe --host 127.0.0.1 --port 47800 --code 123456
```

`--host` + `--code` 会启动后自动连接，主要用于 `tools/mock_sharer.py` 联调。

## Android 分享端

### 依赖

- Android Studio Ladybug 或更新版本
- Android SDK Platform 35、Build-Tools 35.x
- JDK 17（Android Studio 自带 JBR 即可）
- Gradle 8.9+（Android Studio 会按 AGP 版本下载）

### 构建

用 Android Studio 打开 `android/`，等待同步，然后 Run。或在本机安装 Gradle 后：

```powershell
cd android
gradle :app:assembleDebug
```

APK：

```
android/app/build/outputs/apk/debug/app-debug.apk
```

安装：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

### 权限

- `MediaProjection`：系统录屏授权，每次开始共享由系统弹窗确认。
- `POST_NOTIFICATIONS`（Android 13+）：前台服务通知；拒绝后仍可共享，但通知可能不显示。
- 网络权限：普通权限，安装时自动授予。

### Android 14+ 注意事项

- 服务使用 `foregroundServiceType="mediaProjection"` 并声明
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`。
- 必须先 `startForeground`，再调用 `getMediaProjection`，代码已按此顺序。
- 每次开始共享都要重新获取系统录屏 token；这是 Android 的隐私限制。

## 联调工具

仓库自带：

```powershell
cd tools
python .\mock_sharer.py --file .\test_pattern.h264 --code 123456
```

另开终端：

```powershell
cd pc
dotnet run --project .\MoonView -- --host 127.0.0.1 --code 123456
```

模拟器会：
- 监听 TCP 47800 / UDP 47801；
- 响应 IPv4 发现广播（PC 列表显示 `Mock Pixel`）；
- 以 30 fps 循环发送测试 H.264 码流；
- 支持 `ping`/`pong` 和 `keyframe` 请求。

测试码流由 ffmpeg 生成（AUD + 每秒 IDR）：

```powershell
ffmpeg -f lavfi -i "testsrc2=size=1280x720:rate=30" -t 6 `
  -c:v libx264 -preset veryfast -tune zerolatency -profile:v baseline `
  -pix_fmt yuv420p -x264-params "aud=1:keyint=30:min-keyint=30:scenecut=0" `
  -f h264 test_pattern.h264 -y
```

## 常见问题

### PC 显示「已配对」，但一直 0 fps

1. 防火墙是否放行 UDP 47801（专用网络）。
2. 手机和 PC 是否在同一广播域；访客 Wi-Fi/AP 隔离会阻断。
3. 如果手动输入了 IPv6 链路本地地址，检查 scope id。
4. 看 `%LOCALAPPDATA%\MoonShare\logs\moonview.log` 是否有 `UDP hello failed` 或超时。

### `Another viewer is already connected`

v1 只允许一个观看者。关闭旧观看端窗口，等 1–2 秒 TCP 超时后重试。

### `No H.264 decoder` / 黑屏

安装/更新 Microsoft Edge WebView2 Runtime。可在 PowerShell 中查看：

```powershell
Get-AppxPackage *WebView2* | Select Name, Version
```

### 手机端提示端口被占用

确认没有另一个 MoonShare 实例在运行，或重启手机后重试。TCP 47800、UDP 47801/47802
是固定端口。

### 旋转手机后画面方向不对

v1 在开始共享时锁定虚拟显示尺寸。旋转后请停止共享并重新开始。
