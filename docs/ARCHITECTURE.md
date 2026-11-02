# 架构与线程模型

## 目标

MoonShare 的目标不是做一个通用远程桌面，而是一个**只读、低延迟、局域网优先**的
屏幕观看工具。设计上刻意砍掉输入回传、云中转、多观看者和音频，以换取更小的
攻击面和更低的延迟。

## 总览

```
┌──────────────────────── Android ────────────────────────┐
│ MainActivity                                            │
│   └─ ScreenShareService (foreground, mediaProjection)   │
│        ├─ ScreenCapture                                 │
│        │    ├─ MediaCodec H.264 encoder (Surface input) │
│        │    ├─ VirtualDisplay (AUTO_MIRROR)             │
│        │    └─ encoder thread: dequeue → VideoFrame     │
│        ├─ ControlServer (TCP 47800)                     │
│        ├─ UdpVideoTransport (UDP 47801, v4 + v6)        │
│        └─ DiscoveryResponder (UDP 47802, IPv4)          │
└─────────────────────────────────────────────────────────┘
                    │ TCP control (pairing/ping/keyframe)
                    │ UDP video (fragmented H.264)
                    ▼
┌──────────────────────── Windows ────────────────────────┐
│ MoonView WPF                                            │
│   ├─ ShareClient                                        │
│   │    ├─ TCP reader / ping loop                        │
│   │    ├─ UDP hello + receive loop                      │
│   │    └─ FrameAssembler (fragment reassembly)          │
│   ├─ ViewerServer (Kestrel, 127.0.0.1 random port)      │
│   │    ├─ static web assets                             │
│   │    └─ WebSocket /ws (binary frames + JSON state)    │
│   └─ WebView2                                           │
│        └─ JS: VideoDecoder (WebCodecs) → Canvas         │
└─────────────────────────────────────────────────────────┘
```

## Android 侧

### 采集与编码

`ScreenCapture` 使用 `MediaProjection.createVirtualDisplay()` 得到一个镜像显示，
把它的输出 Surface 直接交给 `MediaCodec` 的 H.264 编码器。编码器配置：

- `COLOR_FormatSurface`，不经过 CPU 读像素；
- Baseline profile，无 B 帧；
- 1 秒 IDR 间隔；
- CBR，码率按分辨率在 2–12 Mbps 之间估算；
- 屏幕长边上限 1920，保持宽高比，宽高取偶数。

`drainEncoder()` 在独立线程里同步 `dequeueOutputBuffer`，把每个输出 access unit 复制
成 `VideoFrame`。`BUFFER_FLAG_CODEC_CONFIG` 的 SPS/PPS 会缓存，并在每个 IDR 前补齐，
保证中途加入的观看者能立即解码。

### 控制与视频

- `ControlServer` 在 `47800` 接受 TCP。第一个 JSON `hello` 携带 6 位配对码；
  验证通过后生成随机会话 id/token，返回 `welcome`。
- `UdpVideoTransport` 根据已配对观看者的 TCP 地址族，绑定 IPv4 `0.0.0.0:47801`
  或 IPv6 `[::]:47801`（单观看者，单地址族）。观看者先发 32 字节 UDP HELLO
  （session + token），手机记录来源地址，之后所有视频发往该地址。这样 PC 无需
  开放固定 UDP 端口，也能穿过有状态防火墙/NAT。
- `GlFrameGate` 位于 `VirtualDisplay` 与 `MediaCodec` 输入 Surface 之间，用 EGL
  外部纹理把最新画面按 30 fps 重绘给编码器。这样静态页面也会持续产生帧，且编码器
  不会按 90/120 Hz 输出。发送端绝不再丢弃已编码的 P 帧（丢 P 帧会破坏参考链并产生
  块状花屏）。
- 发送线程从容量 3 的队列取帧，按 1164 字节分片。编码目标约 0.5 bit/像素/帧，
  864×1920@30 约 25 Mbps。
- 同时在 **TCP 47801** 暴露一个回退视频通道，用于模拟器/ADB 转发或 UDP 被阻断的
  网络；PC 在 3 秒内没收到 UDP 关键帧时才连接它。
- **TCP 47804** 发送 48 kHz 双声道 PCM（20 ms/包）。`AudioStreamTransport` 使用
  Android 10+ 的 `AudioPlaybackCaptureConfiguration` 捕获媒体/游戏音频；PC 通过
  WebView2 的 Web Audio 调度播放。
- 会话只允许一个观看者；TCP 控制断开时立即停止 UDP/TCP 视频和音频。

### Android 线程

| 线程 | 职责 |
|---|---|
| main | UI、通知、MediaProjection 回调、统计广播 |
| `MoonShare-Control` | accept TCP |
| `MoonShare-Control-Client` | 每个观看者的 handshake/命令读取 |
| `MoonShare-GlGate` | SurfaceTexture→EGL→编码器，固定 30 fps |
| `MoonShare-Encoder` | MediaCodec 输出循环 |
| `MoonShare-UdpRx` | 接收 HELLO，记录观看者端点 |
| `MoonShare-UdpTx` | 分片发送，实时优先级 |
| `MoonShare-AudioRx` | 等待音频 TCP 观看者 |
| `MoonShare-AudioTx` | AudioRecord 播放捕获并发送 PCM |
| `MoonShare-Discovery` | 响应 IPv4 广播探测 |

## Windows 侧

### 为什么用 WebView2 + WebCodecs

Moonlight 的 PC 端通常自带 FFmpeg/硬件解码栈。MoonShare 选择：

- WPF 负责原生窗口、设备发现、TCP/UDP 和状态；
- 本地回环 Kestrel 提供静态页 + WebSocket；
- WebView2 里的 Chromium `VideoDecoder` 做 H.264 硬解，Canvas 绘制。

优点：不随包分发 ffmpeg，不依赖系统安装的播放器；Windows 10/11 的 WebView2 运行时
通常已经存在。代价：PC 端必须安装 WebView2 Runtime，且 H.264 解码能力取决于 Chromium。

### 数据流

1. `ShareClient` 连接 TCP，发送 `hello`，校验 `welcome`。
2. WebView2 页面通过 WebSocket 连上本地 `ViewerServer`，发送 `ready`。
3. `ShareClient.StartVideo()` 创建与手机同地址族的 UDP socket，发送 HELLO。
4. 收到 `MSV1` 分片后 `FrameAssembler` 按 frame id 重组；缺口超过 90 ms 就丢弃并
   通过 TCP 请求关键帧。
5. 完整 access unit 以二进制 WebSocket 消息发给页面：16 字节 `MSF1` 头 + Annex-B。
6. 页面解析 SPS 得到 `avc1.PPCCLL`，创建 `VideoDecoder`，输出 `VideoFrame` 画到 Canvas。

### 延迟预算（典型 720p/30，LAN）

| 阶段 | 量级 |
|---|---|
| 采集 + 硬件编码 | 10–25 ms |
| UDP 分片/排队 | 1–5 ms |
| 重组 + WebSocket | 1–3 ms |
| WebCodecs 解码 | 5–15 ms |
| 合成/显示 | 5–15 ms |
| 合计 | 约 25–60 ms + 网络 |

30 fps 下每帧 33 ms，因此端到端通常在一到两帧内。没有 jitter buffer，代价是丢包时
会短暂黑屏/卡顿，直到下一个 IDR（最多 1 秒）。

## 关键取舍

- **UDP 不重传**：实时流重传旧帧只会增加延迟；用 1 秒 IDR + 关键帧请求恢复。
- **单观看者**：避免手机上行带宽和编码器被多路会话拖垮；也简化配对状态。
- **固定端口**：方便发现和防火墙配置；冲突时启动失败并提示。
- **IPv4 发现 + IPv6 手动**：IPv6 链路本地发现需要接口 scope、组播加入和防火墙策略，
  跨 Windows/Android 的可靠性差；直连本身完全支持 IPv6。
- **不加密**：v1 目标是可信局域网。配对码和 token 防误连/简单冒连，不防抓包。
  若需要跨不可信网络，套 WireGuard/Tailscale 等 VPN。

## 可扩展方向

- 多观看者（每会话独立 token + 码率自适应）
- IPv6 组播发现
- AES-GCM 媒体加密或 TLS/DTLS
- 旋转时 `VirtualDisplay.resize()` + 编码器热切换
- 音频（MediaProjection AudioPlaybackCapture）
- H.265/AV1 协商
- Linux/macOS 查看端（协议不变，换解码栈）
