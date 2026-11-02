# 开发/联调工具

## mock_sharer.py

无需 Android 手机即可验证 PC 端的完整链路：

```powershell
cd tools
python .\mock_sharer.py --file .\test_pattern.h264 --code 123456
```

它实现：

- TCP 47800 的 hello/welcome、ping/pong、keyframe 请求；
- UDP 47801 的 HELLO/ACK；
- 1200 字节 MTU 的 `MSV1` 分片；
- IPv4 发现广播响应；
- 循环播放 Annex-B H.264 测试流。

```powershell
cd ..\pc
dotnet run --project .\MoonView -- --host 127.0.0.1 --code 123456
```

IPv6 测试：

```powershell
python .\mock_sharer.py --file .\test_pattern.h264 --code 123456
# PC 端 --host ::1
```

## test_pattern.h264

1280×720 / 30 fps / 6 秒，含 AUD 与每秒 IDR，用于验证 H.264 解码和丢帧恢复。
可以用 ffmpeg 重新生成：

```powershell
ffmpeg -f lavfi -i "testsrc2=size=1280x720:rate=30" -t 6 `
  -c:v libx264 -preset veryfast -tune zerolatency -profile:v baseline `
  -pix_fmt yuv420p -x264-params "aud=1:keyint=30:min-keyint=30:scenecut=0" `
  -f h264 test_pattern.h264 -y
```

## 协议测试建议

- 错误配对码：改 `--code` 或输入错误数字，PC 应显示 `Pairing code is incorrect`。
- UDP 丢包：在 `mock_sharer.send_unit()` 中随机跳过一帧，观察 PC 端是否请求关键帧并恢复。
- IPv6：`--host ::1`；Windows 链路本地地址需要 `%scope`。
- 单观看者：Android 端第二个连接会返回 `busy`（mock 不模拟该限制）。
