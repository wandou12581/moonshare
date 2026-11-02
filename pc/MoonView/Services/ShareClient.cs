using System.Buffers.Binary;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace MoonView.Services;

public sealed record WelcomeInfo(
    string SessionHex,
    string TokenHex,
    string Name,
    int Width,
    int Height,
    int Fps,
    int Bitrate,
    int VideoPort,
    int AudioPort);

public sealed record StreamInfo(int Width, int Height, int Fps, int Bitrate);

public sealed record NetworkStats(double Fps, int Kbps, long DroppedFrames);

public sealed class ShareException : Exception
{
    public ShareException(string code, string message) : base(message) => Code = code;
    public string Code { get; }
}

/// <summary>
/// TCP control + UDP video client for one MoonShare phone.
/// There is no input path in this class, by design.
/// </summary>
public sealed class ShareClient : IAsyncDisposable
{
    private readonly FrameAssembler _assembler = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly List<Task> _tasks = new();
    private readonly object _tasksGate = new();
    private readonly object _gate = new();

    private TcpClient? _tcp;
    private NetworkStream? _tcpStream;
    private StreamReader? _reader;
    private StreamWriter? _writer;
    private UdpClient? _udp;
    private TcpClient? _tcpVideo;
    private TcpClient? _tcpAudio;
    private volatile bool _usingTcpVideo;
    private IPEndPoint? _remote;
    private IPAddress? _localAddress;
    private WelcomeInfo? _welcome;
    private long _sessionId;
    private byte[]? _token;
    private bool _videoStarted;
    private bool _seenKeyFrame;
    private int _closed;
    private int _disconnectRaised;
    private long _lastPongMs;
    private long _lastKeyRequestMs;
    private int _udpPacketLogged;
    private int _udpAckLogged;
    private int _firstVideoPacketLogged;
    private long _framesThisSecond;
    private long _bytesThisSecond;
    private long _droppedFrames;
    private long _lastStatsMs;

    public event Action<WelcomeInfo>? Welcomed;
    public event Action<StreamInfo>? StreamChanged;
    public event Action<AssembledFrame>? FrameReady;
    public event Action<byte[]>? AudioFrameReady;
    public event Action<NetworkStats>? StatsUpdated;
    public event Action<string>? Disconnected;

    public WelcomeInfo? Welcome => _welcome;
    public bool IsVideoStarted => _videoStarted;

    public ShareClient()
    {
        _assembler.FrameReady += OnFrameReady;
        _assembler.LossDetected += OnLossDetected;
    }

    public async Task<WelcomeInfo> ConnectAsync(
        string host,
        int port,
        string code,
        string viewerName,
        CancellationToken cancellationToken)
    {
        var addresses = await ResolveAsync(host, cancellationToken).ConfigureAwait(false);
        Exception? lastError = null;
        foreach (var address in addresses)
        {
            TcpClient? tcp = null;
            try
            {
                tcp = new TcpClient(address.AddressFamily);
                using var attempt = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                attempt.CancelAfter(TimeSpan.FromSeconds(4));
                await tcp.ConnectAsync(address, port, attempt.Token).ConfigureAwait(false);
                tcp.NoDelay = true;
                tcp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
                _tcp = tcp;
                _remote = (IPEndPoint)tcp.Client.RemoteEndPoint!;
                _localAddress = ((IPEndPoint)tcp.Client.LocalEndPoint!).Address;
                break;
            }
            catch (Exception ex)
            {
                lastError = ex;
                tcp?.Dispose();
                if (cancellationToken.IsCancellationRequested) throw;
            }
        }

        if (_tcp is null || _remote is null)
        {
            throw new ShareException(
                "connect_failed",
                $"无法连接到 {HostParser.Normalize(host)}:{port}" +
                (lastError is null ? "" : $" ({lastError.Message})"));
        }

        _tcpStream = _tcp.GetStream();
        _reader = new StreamReader(_tcpStream, new UTF8Encoding(false), false, 8192, leaveOpen: true);
        _writer = new StreamWriter(_tcpStream, new UTF8Encoding(false), 8192, leaveOpen: true)
        {
            NewLine = "\n",
            AutoFlush = true
        };

        var hello = JsonSerializer.Serialize(new
        {
            t = "hello",
            v = 1,
            app = "MoonView/1.0",
            viewer = string.IsNullOrWhiteSpace(viewerName) ? "MoonView" : viewerName,
            code
        });
        await SendLineAsync(hello, cancellationToken).ConfigureAwait(false);

        using var handshake = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        handshake.CancelAfter(TimeSpan.FromSeconds(7));
        string? line;
        try
        {
            line = await _reader.ReadLineAsync(handshake.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new ShareException("timeout", "手机没有在 7 秒内响应配对请求");
        }
        catch (IOException ex)
        {
            throw new ShareException("connection_closed", $"手机断开了连接: {ex.Message}");
        }

        if (string.IsNullOrWhiteSpace(line))
        {
            throw new ShareException("closed", "手机关闭了连接");
        }

        using var document = JsonDocument.Parse(line);
        var root = document.RootElement;
        var type = root.TryGetProperty("t", out var typeElement) ? typeElement.GetString() : null;
        if (type == "error")
        {
            var codeText = root.TryGetProperty("code", out var codeElement) ? codeElement.GetString() : "error";
            var message = root.TryGetProperty("message", out var messageElement)
                ? messageElement.GetString()
                : "配对失败";
            throw new ShareException(codeText ?? "error", message ?? "配对失败");
        }
        if (type != "welcome")
        {
            throw new ShareException("bad_response", "手机返回了无法识别的响应");
        }

        var sessionHex = root.GetProperty("session").GetString() ?? "";
        var tokenHex = root.GetProperty("token").GetString() ?? "";
        if (sessionHex.Length != 16 || tokenHex.Length != 32)
        {
            throw new ShareException("bad_response", "手机返回的会话参数无效");
        }
        _sessionId = unchecked((long)Convert.ToUInt64(sessionHex, 16));
        _token = Convert.FromHexString(tokenHex);

        _welcome = new WelcomeInfo(
            sessionHex,
            tokenHex,
            root.TryGetProperty("name", out var nameElement) ? nameElement.GetString() ?? "Android" : "Android",
            root.TryGetProperty("width", out var widthElement) ? widthElement.GetInt32() : 0,
            root.TryGetProperty("height", out var heightElement) ? heightElement.GetInt32() : 0,
            root.TryGetProperty("fps", out var fpsElement) ? fpsElement.GetInt32() : 30,
            root.TryGetProperty("bitrate", out var bitrateElement) ? bitrateElement.GetInt32() : 0,
            root.TryGetProperty("videoPort", out var videoElement) ? videoElement.GetInt32() : 47801,
            root.TryGetProperty("audioPort", out var audioElement) ? audioElement.GetInt32() : 47804);
        if (_welcome.VideoPort is < 1 or > 65535)
        {
            throw new ShareException("bad_response", "手机返回的视频端口无效");
        }

        _lastPongMs = Environment.TickCount64;
        _lastStatsMs = Environment.TickCount64;
        Welcomed?.Invoke(_welcome);

        AddTask(Task.Run(() => ReadLoopAsync(_cts.Token)));
        AddTask(Task.Run(() => PingLoopAsync(_cts.Token)));
        return _welcome;
    }

    /// <summary>Called once the WebView is ready; sends the first UDP hello.</summary>
    public void StartVideo()
    {
        if (_videoStarted || _welcome is null || _remote is null || _closed != 0) return;
        lock (_gate)
        {
            if (_videoStarted || _welcome is null || _remote is null || _closed != 0) return;
            _videoStarted = true;
            _udp = new UdpClient(_remote.AddressFamily);
            // Bind the local address used by the TCP session so video doesn't take a
            // different NIC/VPN route on a multi-homed Windows machine.
            var bindAddress = _localAddress;
            if (bindAddress is null ||
                (bindAddress.AddressFamily == AddressFamily.InterNetworkV6 &&
                 bindAddress.Equals(IPAddress.IPv6Any)) ||
                bindAddress.Equals(IPAddress.Any))
            {
                bindAddress = _remote.AddressFamily == AddressFamily.InterNetworkV6
                    ? IPAddress.IPv6Any
                    : IPAddress.Any;
            }
            _udp.Client.Bind(new IPEndPoint(bindAddress, 0));
            _udp.Client.ReceiveBufferSize = 4 * 1024 * 1024;
            _udp.Client.SendBufferSize = 256 * 1024;
            AddTask(Task.Run(() => UdpLoopAsync(_cts.Token)));
            AddTask(Task.Run(() => HelloLoopAsync(_cts.Token)));
            AddTask(Task.Run(() => StatsLoopAsync(_cts.Token)));
            AddTask(Task.Run(() => TcpFallbackLoopAsync(_cts.Token)));
            AddTask(Task.Run(() => AudioLoopAsync(_cts.Token)));
            AddTask(Task.Run(() => VideoWatchdogAsync(_cts.Token)));
        }
        AppLog.Info($"Video started for {_welcome.Name} at {_remote}, udp local {_udp.Client.LocalEndPoint}");
    }

    public void RequestKeyFrame()
    {
        if (_closed != 0) return;
        var now = Environment.TickCount64;
        if (now - _lastKeyRequestMs < 700) return;
        _lastKeyRequestMs = now;
        _ = Task.Run(async () =>
        {
            try
            {
                await SendLineAsync("{\"t\":\"keyframe\"}", _cts.Token).ConfigureAwait(false);
            }
            catch
            {
            }
        });
    }

    private async Task ReadLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested && _reader is not null)
            {
                var line = await _reader.ReadLineAsync(token).ConfigureAwait(false);
                if (line is null) break;
                if (string.IsNullOrWhiteSpace(line)) continue;
                using var document = JsonDocument.Parse(line);
                var root = document.RootElement;
                var type = root.TryGetProperty("t", out var typeElement) ? typeElement.GetString() : "";
                switch (type)
                {
                    case "pong":
                        _lastPongMs = Environment.TickCount64;
                        break;
                    case "stats":
                    {
                        // Android's stats count UDP frames only; on the TCP fallback they
                        // read 0 and would overwrite the viewer's real byte counter.
                        if (_usingTcpVideo) break;
                        var fps = root.TryGetProperty("fps", out var fpsElement) ? fpsElement.GetDouble() : 0;
                        var kbps = root.TryGetProperty("kbps", out var kbpsElement) ? kbpsElement.GetInt32() : 0;
                        StatsUpdated?.Invoke(new NetworkStats(fps, kbps, Interlocked.Read(ref _droppedFrames)));
                        break;
                    }
                    case "stream":
                    {
                        var width = root.TryGetProperty("width", out var w) ? w.GetInt32() : 0;
                        var height = root.TryGetProperty("height", out var h) ? h.GetInt32() : 0;
                        var fps = root.TryGetProperty("fps", out var f) ? f.GetInt32() : 30;
                        var bitrate = root.TryGetProperty("bitrate", out var b) ? b.GetInt32() : 0;
                        if (width > 0 && height > 0)
                        {
                            StreamChanged?.Invoke(new StreamInfo(width, height, fps, bitrate));
                        }
                        break;
                    }
                    case "bye":
                    case "error":
                    {
                        var message = root.TryGetProperty("message", out var m)
                            ? m.GetString()
                            : "sharing stopped";
                        AppLog.Warn($"Phone control message '{type}': {message}");
                        CloseInternal($"手机结束共享: {message}");
                        return;
                    }
                }
            }
            CloseInternal("手机已结束共享");
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            CloseInternal($"控制连接断开: {ex.Message}");
        }
    }

    private async Task PingLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(2000, token).ConfigureAwait(false);
                if (Environment.TickCount64 - _lastPongMs > 6500)
                {
                    CloseInternal("控制连接超时");
                    return;
                }
                await SendLineAsync($"{{\"t\":\"ping\",\"ts\":{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}}}", token)
                    .ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            CloseInternal($"心跳失败: {ex.Message}");
        }
    }

    private async Task HelloLoopAsync(CancellationToken token)
    {
        if (_udp is null || _remote is null || _welcome is null || _token is null) return;
        var hello = BuildUdpHello(_sessionId, _token);
        var videoEndPoint = new IPEndPoint(_remote.Address, _welcome.VideoPort);
        AppLog.Info($"Sending UDP hello to {videoEndPoint} from {_udp.Client.LocalEndPoint}");
        try
        {
            while (!token.IsCancellationRequested)
            {
                if (_usingTcpVideo) return;
                try
                {
                    var sent = await _udp.SendAsync(hello, hello.Length, videoEndPoint).ConfigureAwait(false);
                    if (sent != hello.Length)
                    {
                        AppLog.Warn($"UDP hello short send: {sent}/{hello.Length}");
                    }
                }
                catch (Exception ex)
                {
                    if (token.IsCancellationRequested) return;
                    AppLog.Warn($"UDP hello failed: {ex.GetType().Name}: {ex.Message}");
                }
                var interval = _seenKeyFrame ? 5000 : 1500;
                await Task.Delay(interval, token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task UdpLoopAsync(CancellationToken token)
    {
        if (_udp is null || _remote is null) return;
        try
        {
            while (!token.IsCancellationRequested)
            {
                UdpReceiveResult result;
                try
                {
                    result = await _udp.ReceiveAsync(token).ConfigureAwait(false);
                }
                catch (SocketException ex) when (!token.IsCancellationRequested)
                {
                    // On Windows, sending a probe to a local port with no UDP listener
                    // produces ICMP port-unreachable, surfaced as ConnectionReset on the
                    // next receive. Keep the session alive so the TCP fallback can run.
                    AppLog.Warn($"UDP receive error: {ex.SocketErrorCode}; continuing (TCP fallback will start)");
                    await Task.Delay(200, token).ConfigureAwait(false);
                    continue;
                }
                if (!SameHost(result.RemoteEndPoint.Address, _remote.Address)) continue;
                var buffer = result.Buffer;

                if (Interlocked.Exchange(ref _udpPacketLogged, 1) == 0)
                {
                    AppLog.Info($"UDP first packet from {result.RemoteEndPoint}, {buffer.Length} bytes");
                }
                if (buffer.Length >= 8 &&
                    buffer[0] == (byte)'M' && buffer[1] == (byte)'S' &&
                    buffer[2] == (byte)'U' && buffer[3] == (byte)'1' &&
                    buffer[5] == 2 &&
                    Interlocked.Exchange(ref _udpAckLogged, 1) == 0)
                {
                    AppLog.Info($"UDP HELLO ACK received from {result.RemoteEndPoint}");
                }

                if (_usingTcpVideo) continue;
                if (!VideoProtocol.TryParse(buffer, buffer.Length, out var packet)) continue;
                if (packet.SessionId != _sessionId) continue;

                if (Interlocked.Exchange(ref _firstVideoPacketLogged, 1) == 0)
                {
                    AppLog.Info($"UDP video packet from {result.RemoteEndPoint}, frame={packet.FrameId}, key={packet.KeyFrame}");
                }


                Interlocked.Add(ref _bytesThisSecond, buffer.Length);
                _assembler.Add(packet);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
        catch (Exception ex)
        {
            CloseInternal($"视频接收失败: {ex.Message}");
        }
    }

    private async Task AudioLoopAsync(CancellationToken token)
    {
        try
        {
            await Task.Delay(700, token).ConfigureAwait(false);
            if (_welcome is null || _remote is null || _token is null || _closed != 0) return;
            for (var attempt = 0; attempt < 3 && !token.IsCancellationRequested; attempt++)
            {
                TcpClient? audio = null;
                try
                {
                    audio = new TcpClient(_remote.AddressFamily);
                    using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(token);
                    connectCts.CancelAfter(TimeSpan.FromSeconds(4));
                    await audio.ConnectAsync(_remote.Address, _welcome.AudioPort, connectCts.Token)
                        .ConfigureAwait(false);
                    audio.NoDelay = true;
                    var stream = audio.GetStream();
                    await stream.WriteAsync(BuildUdpHello(_sessionId, _token), token).ConfigureAwait(false);
                    await stream.FlushAsync(token).ConfigureAwait(false);
                    _tcpAudio = audio;
                    AppLog.Info($"Audio stream connected to {_remote.Address}:{_welcome.AudioPort}");

                    var header = new byte[12];
                    var frames = 0L;
                    while (!token.IsCancellationRequested && _closed == 0)
                    {
                        await ReadExactAsync(stream, header, token).ConfigureAwait(false);
                        var length = BinaryPrimitives.ReadInt32BigEndian(header);
                        var pts = BinaryPrimitives.ReadInt64BigEndian(header.AsSpan(4, 8));
                        if (length <= 0 || length > 64 * 1024) break;
                        var pcm = new byte[length];
                        await ReadExactAsync(stream, pcm, token).ConfigureAwait(false);
                        frames++;
                        if (frames == 1 || frames % 50 == 0)
                        {
                            var peak = 0;
                            for (var i = 0; i + 1 < pcm.Length; i += 2)
                            {
                                peak = Math.Max(peak, Math.Abs(BitConverter.ToInt16(pcm, i)));
                            }
                            AppLog.Info($"Audio PCM frames={frames}, bytes={length}, pts={pts}, peak={peak}");
                        }
                        AudioFrameReady?.Invoke(pcm);
                    }
                    return;
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception ex)
                {
                    if (_closed == 0)
                    {
                        AppLog.Warn($"Audio stream attempt {attempt + 1} failed: {ex.Message}");
                    }
                    try
                    {
                        audio?.Dispose();
                    }
                    catch
                    {
                    }
                    _tcpAudio = null;
                    await Task.Delay(1000, token).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// Fallback for the emulator / UDP-blocked networks: ADB can forward TCP 47801 but
    /// not UDP. The Android side listens on the same numeric port as a TCP listener.
    /// </summary>
    private async Task TcpFallbackLoopAsync(CancellationToken token)
    {
        try
        {
            await Task.Delay(3000, token).ConfigureAwait(false);
            if (_seenKeyFrame || _closed != 0 || _remote is null || _welcome is null || _token is null)
            {
                return;
            }

            var tcp = new TcpClient(_remote.AddressFamily);
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(token);
            connectCts.CancelAfter(TimeSpan.FromSeconds(4));
            await tcp.ConnectAsync(_remote.Address, _welcome.VideoPort, connectCts.Token)
                .ConfigureAwait(false);
            tcp.NoDelay = true;
            var stream = tcp.GetStream();
            var hello = BuildUdpHello(_sessionId, _token);
            await stream.WriteAsync(hello, token).ConfigureAwait(false);
            await stream.FlushAsync(token).ConfigureAwait(false);

            _tcpVideo = tcp;
            _usingTcpVideo = true;
            try
            {
                _udp?.Dispose();
            }
            catch
            {
            }
            AppLog.Info($"TCP video fallback connected to {_remote.Address}:{_welcome.VideoPort}");

            var header = new byte[13];
            while (!token.IsCancellationRequested && _closed == 0)
            {
                await ReadExactAsync(stream, header, token).ConfigureAwait(false);
                var length = BinaryPrimitives.ReadInt32BigEndian(header);
                var keyFrame = (header[4] & 1) != 0;
                var pts = BinaryPrimitives.ReadInt64BigEndian(header.AsSpan(5, 8));
                if (length <= 0 || length > VideoProtocol.MaxFrameBytes)
                {
                    throw new InvalidDataException($"Invalid TCP video frame length {length}");
                }
                var data = new byte[length];
                await ReadExactAsync(stream, data, token).ConfigureAwait(false);
                Interlocked.Add(ref _bytesThisSecond, length + header.Length);
                if (!_seenKeyFrame && !keyFrame) continue;
                OnFrameReady(new AssembledFrame(data, keyFrame, pts, 0));
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            if (_closed == 0) AppLog.Warn($"TCP video fallback failed: {ex.Message}");
            _usingTcpVideo = false;
        }
    }

    private static async Task ReadExactAsync(Stream stream, byte[] buffer, CancellationToken token)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset), token).ConfigureAwait(false);
            if (read <= 0) throw new EndOfStreamException("TCP video stream closed");
            offset += read;
        }
    }

    private async Task StatsLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(1000, token).ConfigureAwait(false);
                var now = Environment.TickCount64;
                var elapsed = Math.Max(1, now - _lastStatsMs) / 1000.0;
                var fps = Interlocked.Exchange(ref _framesThisSecond, 0) / elapsed;
                var bytes = Interlocked.Exchange(ref _bytesThisSecond, 0);
                var kbps = (int)(bytes * 8.0 / 1000.0 / elapsed);
                _lastStatsMs = now;
                StatsUpdated?.Invoke(new NetworkStats(
                    Math.Round(fps, 1),
                    kbps,
                    Interlocked.Read(ref _droppedFrames)));
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void OnFrameReady(AssembledFrame frame)
    {
        if (frame.KeyFrame)
        {
            _seenKeyFrame = true;
        }
        if (!_seenKeyFrame)
        {
            RequestKeyFrame();
            return;
        }

        Interlocked.Increment(ref _framesThisSecond);
        FrameReady?.Invoke(frame);
    }

    private async Task VideoWatchdogAsync(CancellationToken token)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(12), token).ConfigureAwait(false);
            if (!_seenKeyFrame)
            {
                AppLog.Warn("Video watchdog: no key frame within 12s; closing control session");
                CloseInternal("视频通道未建立：手机没有返回画面（可能是防火墙拦截了 UDP）");
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void AddTask(Task task)
    {
        lock (_tasksGate) _tasks.Add(task);
    }

    private Task[] SnapshotTasks()
    {
        lock (_tasksGate) return _tasks.ToArray();
    }

    private void OnLossDetected()
    {
        _seenKeyFrame = false;
        Interlocked.Increment(ref _droppedFrames);
        RequestKeyFrame();
    }

    private async Task SendLineAsync(string line, CancellationToken token)
    {
        var writer = _writer;
        if (writer is null) return;
        await _writeLock.WaitAsync(token).ConfigureAwait(false);
        try
        {
            await writer.WriteLineAsync(line.AsMemory(), token).ConfigureAwait(false);
            await writer.FlushAsync().ConfigureAwait(false);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private void CloseInternal(string reason)
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        AppLog.Info($"Closing viewer session: {reason}");
        try
        {
            _cts.Cancel();
        }
        catch
        {
        }
        try
        {
            _udp?.Dispose();
        }
        catch
        {
        }
        try
        {
            _tcpVideo?.Close();
        }
        catch
        {
        }
        try
        {
            _tcpAudio?.Close();
        }
        catch
        {
        }
        try
        {
            _tcp?.Close();
        }
        catch
        {
        }

        if (Interlocked.Exchange(ref _disconnectRaised, 1) == 0)
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    await Task.WhenAll(SnapshotTasks()).WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
                }
                catch
                {
                }
                Disconnected?.Invoke(reason);
            });
        }
    }

    private static async Task<List<IPAddress>> ResolveAsync(string host, CancellationToken token)
    {
        var normalized = HostParser.Normalize(host);
        if (IPAddress.TryParse(normalized, out var literal))
        {
            return new List<IPAddress> { literal };
        }

        var addresses = await Dns.GetHostAddressesAsync(normalized, token).ConfigureAwait(false);
        return addresses
            .Where(a => a.AddressFamily is AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)
            .OrderBy(a => a.AddressFamily == AddressFamily.InterNetworkV6 ? 0 : 1)
            .ToList();
    }

    private static bool SameHost(IPAddress a, IPAddress b)
    {
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        if (b.IsIPv4MappedToIPv6) b = b.MapToIPv4();
        if (a.AddressFamily == AddressFamily.InterNetworkV6 &&
            b.AddressFamily == AddressFamily.InterNetworkV6 &&
            a.IsIPv6LinkLocal && b.IsIPv6LinkLocal)
        {
            // Windows and Android may disagree on the scope id for the same link.
            return a.GetAddressBytes().AsSpan().SequenceEqual(b.GetAddressBytes());
        }
        return a.Equals(b);
    }

    private static byte[] BuildUdpHello(long session, byte[] token)
    {
        var packet = new byte[32];
        packet[0] = (byte)'M';
        packet[1] = (byte)'S';
        packet[2] = (byte)'U';
        packet[3] = (byte)'1';
        packet[4] = 1;
        packet[5] = 1;
        BinaryPrimitives.WriteInt64BigEndian(packet.AsSpan(8, 8), session);
        token.AsSpan(0, Math.Min(16, token.Length)).CopyTo(packet.AsSpan(16, 16));
        return packet;
    }

    public async ValueTask DisposeAsync()
    {
        CloseInternal("已断开");
        try
        {
            await Task.WhenAll(SnapshotTasks()).WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
        }
        catch
        {
        }
        _writeLock.Dispose();
        _cts.Dispose();
        _reader?.Dispose();
        _writer?.Dispose();
        _tcpStream?.Dispose();
        _tcp?.Dispose();
        _udp?.Dispose();
        _tcpVideo?.Dispose();
        _tcpAudio?.Dispose();
    }
}
