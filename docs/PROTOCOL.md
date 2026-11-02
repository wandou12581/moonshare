# MoonShare Wire Protocol v1

MoonShare is a **one-way screen-viewing** protocol. There is deliberately no input,
pointer, keyboard, or control message: a viewer can only receive video.

All multi-byte integers are **big-endian**. Text messages are UTF-8 JSON, one object
per line (`\n`), maximum 8 KiB per line.

## Ports

| Transport | Default port | Direction | Purpose |
|---|---:|---|---|
| TCP | 47800 | viewer → phone | pairing, control, keepalive |
| UDP | 47801 | viewer ↔ phone | UDP hello, H.264 video |
| UDP | 47802 | viewer → phone | LAN discovery (IPv4 broadcast, IPv6 multicast) |

The Android app listens for control connections on a dual-stack TCP socket. After
pairing it binds the video UDP port in the same address family as the viewer's TCP
connection (IPv4 or IPv6), so UDP does not need platform-specific dual-stack options.

## Discovery

IPv4: send the ASCII datagram `MOONSHARE/1 DISCOVER` to
`255.255.255.255:47802` and to each interface's directed broadcast address.

Automatic discovery is currently IPv4-only. IPv6 view sessions are fully supported by
the TCP and UDP sockets; enter the phone's IPv6 address manually in MoonView. For a
link-local address, Windows needs the interface scope id (for example
`fe80::1234:5678:9abc:def0%12`). A global IPv6 address needs no scope.

The sharer replies with one unicast JSON datagram:

```json
{
  "app": "MoonShare",
  "v": 1,
  "name": "Pixel 8",
  "port": 47800,
  "videoPort": 47801,
  "sharing": true,
  "ips": ["192.168.1.23", "fe80::1234:5678:9abc:def0"]
}
```

Discovery is unauthenticated and reveals only the device name and the fact that the
app is running. No video is sent before pairing.

## Control channel (TCP 47800)

The viewer opens a TCP connection, sets `TCP_NODELAY`, and sends `hello`:

```json
{"t":"hello","v":1,"app":"MoonView/1.0","viewer":"DESKTOP-ABC","code":"123456"}
```

The sharer atomically validates the pairing code and whether another viewer is already
attached. A successful reply is `welcome`:

```json
{
  "t": "welcome",
  "v": 1,
  "session": "8f14e45fceea167a",
  "token": "0123456789abcdef0123456789abcdef",
  "name": "Pixel 8",
  "width": 1280,
  "height": 720,
  "fps": 30,
  "codec": "h264",
  "profile": "baseline",
  "bitrate": 8000000,
  "videoPort": 47801,
  "tcpPort": 47800
}
```

`session` is 8 bytes of random data printed as 16 lowercase hex characters. `token` is
16 bytes printed as 32 lowercase hex characters. Both are regenerated for each viewer.

Errors close the connection after the error object:

```json
{"t":"error","code":"bad_code","message":"Pairing code is incorrect"}
```

Error codes: `bad_request`, `unsupported_version`, `bad_code`, `busy`, `rate_limited`,
`server_error`.

After `welcome` the viewer sends a UDP hello (below). It may then send:

```json
{"t":"ping","ts":1720000000000}
{"t":"keyframe"}
```

The sharer replies to `ping` with `{"t":"pong","ts":1720000000000}` and may push:

```json
{"t":"stats","fps":29.9,"kbps":5840,"viewers":1}
{"t":"stream","width":1280,"height":720}
```

The viewer sends `ping` every 2 seconds. Six seconds without a `pong` means the link is
dead. The sharer times out idle control connections after 15 seconds.

## UDP hello

The viewer must send the first UDP packet. This creates the NAT/firewall state so the
phone's video can return to the viewer's ephemeral UDP port. Send this 32-byte datagram
to the sharer's `videoPort`:

| Offset | Size | Value |
|---:|---:|---|
| 0 | 4 | ASCII `MSU1` |
| 4 | 1 | version `1` |
| 5 | 1 | type `1` (HELLO) |
| 6 | 2 | reserved, zero |
| 8 | 8 | session id from `welcome` |
| 16 | 16 | token bytes from `welcome` |

The sharer validates the session and token, records the packet's source IP and port, and
replies with the same header using type `2` (HELLO_ACK). Video is then sent to that source
endpoint. The viewer should resend HELLO every 3 seconds until the first keyframe arrives,
then every 5 seconds as a keepalive.

## Video datagrams

Each H.264 access unit is one frame and is fragmented into datagrams. The default UDP
payload is 1200 bytes (including the 36-byte header).

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII `MSV1` |
| 4 | 1 | version `1` |
| 5 | 1 | flags: bit 0 = key frame (IDR) |
| 6 | 2 | header length = 36 |
| 8 | 8 | session id |
| 16 | 4 | frame id (increments per access unit, wraps) |
| 20 | 2 | fragment index (0-based) |
| 22 | 2 | fragment count |
| 24 | 2 | payload length |
| 26 | 2 | reserved, zero |
| 28 | 8 | presentation timestamp in microseconds |
| 36 | n | Annex-B H.264 bytes |

Rules:

- Every key frame is preceded by SPS and PPS inside the same access unit, so a viewer that
  joins later can start decoding at the next IDR.
- A frame is emitted only after every fragment has arrived. A gap triggers a `keyframe`
  request and the receiver waits for the next IDR.
- Receivers must validate `session`, `fragmentCount > 0`, `fragmentIndex < fragmentCount`,
  `payloadLength <= 1164`, and a total frame size of at most 4 MiB.
- UDP is intentionally not retransmitted. Retransmitting whole frames behind a live stream
  adds latency; the one-second IDR interval handles recovery.

## TCP video fallback (optional)

The normal stream is UDP. The sharer also listens on **TCP 47801** for a viewer that
cannot use UDP (notably an Android emulator reached through `adb forward`, or a network
that drops the UDP return path). The PC viewer waits 3 seconds for a UDP key frame, then
opens this TCP connection.

1. Connect TCP 47801.
2. Send the same 32-byte `MSU1` HELLO (session + token) used by UDP.
3. The sharer sends a sequence of frames:

| Size | Field |
|---:|---|
| 4 | big-endian payload length |
| 1 | flags: bit 0 = key frame |
| 8 | presentation timestamp, microseconds, big-endian |
| n | Annex-B H.264 access unit |

There is no retransmission or framing beyond TCP itself. The path is intended for
debugging and fallback, and uses the same session/token and view-only rules.

For an emulator:

```powershell
adb -s emulator-5554 forward tcp:47800 tcp:47800
adb -s emulator-5554 forward tcp:47801 tcp:47801
adb -s emulator-5554 forward tcp:47804 tcp:47804
MoonView.exe --host 127.0.0.1 --code <pairing-code>
```

## Audio stream (TCP 47804)

Android 10+ captures playback audio with `AudioPlaybackCaptureConfiguration` and sends
48 kHz stereo PCM16 over a dedicated TCP connection. The same 32-byte `MSU1` HELLO
(session + token) authenticates the stream.

| Size | Field |
|---:|---|
| 4 | big-endian PCM payload length |
| 8 | presentation timestamp, microseconds, big-endian |
| n | interleaved little-endian PCM16, 48 kHz stereo |

20 ms frames are 3840 bytes. TCP is used because a lost PCM datagram is an audible pop;
LAN retransmission latency is small. Only apps that allow playback capture are heard
(media/game/unknown); calls and protected audio are never captured.

## View-only guarantee

There is no message in either direction that injects input into the phone. The Android
service never enables an accessibility input path, never requests input injection
permissions, and only creates a display output surface. A malicious viewer cannot control
the device through this protocol.

Pairing protects against accidental connections on a shared LAN, not against a hostile
network. Video and control traffic are not encrypted in v1; use MoonShare only on a LAN
you trust, or tunnel it over a VPN. See `SECURITY.md`.
