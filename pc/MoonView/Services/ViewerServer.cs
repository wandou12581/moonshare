using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace MoonView.Services;

/// <summary>
/// Loopback HTTP + WebSocket host for the video renderer. Keeping the renderer in
/// WebView2 lets Chromium's hardware WebCodecs decoder handle H.264 without shipping
/// a native codec or an ffmpeg binary.
/// </summary>
public sealed class ViewerServer : IAsyncDisposable
{
    private readonly string _webRoot;
    private readonly List<Client> _clients = new();
    private readonly List<Client> _audioClients = new();
    private readonly object _gate = new();
    private WebApplication? _app;
    private string _statusJson = JsonSerializer.Serialize(new
    {
        type = "state",
        state = "idle",
        message = "等待连接",
        device = ""
    });

    public ViewerServer(string webRoot)
    {
        _webRoot = webRoot;
    }

    public string Url { get; private set; } = "";
    public bool HasViewer
    {
        get
        {
            lock (_gate) return _clients.Count > 0;
        }
    }

    public event Action? ViewerReady;
    public event Action? NeedKey;
    public event Action<double>? ViewerDecodeFps;

    public async Task StartAsync()
    {
        if (!Directory.Exists(_webRoot))
        {
            throw new DirectoryNotFoundException($"Web assets not found: {_webRoot}");
        }

        var port = GetFreePort();
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = Array.Empty<string>(),
            ContentRootPath = _webRoot
        });
        builder.Logging.ClearProviders();
        builder.WebHost.UseUrls($"http://127.0.0.1:{port}");

        var app = builder.Build();
        var files = new PhysicalFileProvider(_webRoot);
        app.Use(async (context, next) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            await next();
        });
        app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(10)
        });
        app.UseDefaultFiles(new DefaultFilesOptions { FileProvider = files });
        app.UseStaticFiles(new StaticFileOptions
        {
            FileProvider = files,
            ServeUnknownFileTypes = true
        });

        app.Map("/ws", HandleWebSocketAsync);
        app.Map("/audio", HandleAudioWebSocketAsync);
        app.MapGet("/health", () => Results.Text("ok"));

        await app.StartAsync().ConfigureAwait(false);
        _app = app;
        Url = $"http://127.0.0.1:{port}/";
        AppLog.Info($"Viewer server listening at {Url}");
    }

    public void SetStatus(string state, string message, string device = "")
    {
        var json = JsonSerializer.Serialize(new { type = "state", state, message, device });
        lock (_gate) _statusJson = json;
        BroadcastText(json);
    }

    public void SetStreamInfo(int width, int height, int fps, string codec = "H.264")
    {
        BroadcastText(JsonSerializer.Serialize(new
        {
            type = "stream",
            width,
            height,
            fps,
            codec
        }));
    }

    public void BroadcastFrame(byte[] annexB, bool keyFrame, long presentationTimeUs)
    {
        if (annexB.Length == 0) return;
        var message = new byte[16 + annexB.Length];
        message[0] = (byte)'M';
        message[1] = (byte)'S';
        message[2] = (byte)'F';
        message[3] = (byte)'1';
        message[4] = keyFrame ? (byte)1 : (byte)0;
        System.Buffers.Binary.BinaryPrimitives.WriteInt64LittleEndian(
            message.AsSpan(8, 8),
            presentationTimeUs);
        Buffer.BlockCopy(annexB, 0, message, 16, annexB.Length);

        Client[] clients;
        lock (_gate) clients = _clients.ToArray();
        foreach (var client in clients) client.EnqueueBinary(message);
    }

    public void BroadcastAudio(byte[] pcm)
    {
        if (pcm.Length == 0) return;
        Client[] clients;
        lock (_gate) clients = _audioClients.ToArray();
        foreach (var client in clients) client.EnqueueBinary(pcm);
    }

    public void BroadcastText(string json)
    {
        Client[] clients;
        lock (_gate) clients = _clients.ToArray();
        foreach (var client in clients) client.EnqueueText(json);
    }

    private async Task HandleWebSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        var socket = await context.WebSockets.AcceptWebSocketAsync().ConfigureAwait(false);
        var client = new Client(socket, OnClientText);
        string status;
        lock (_gate)
        {
            _clients.Add(client);
            status = _statusJson;
        }
        client.EnqueueText(status);
        AppLog.Info("Web renderer connected");
        ViewerReady?.Invoke();

        try
        {
            await client.ReceiveLoopAsync().ConfigureAwait(false);
        }
        finally
        {
            lock (_gate) _clients.Remove(client);
            await client.DisposeAsync().ConfigureAwait(false);
            AppLog.Info("Web renderer disconnected");
        }
    }

    private async Task HandleAudioWebSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }
        var socket = await context.WebSockets.AcceptWebSocketAsync().ConfigureAwait(false);
        var client = new Client(socket, _ => { }, 64);
        lock (_gate) _audioClients.Add(client);
        AppLog.Info("Audio renderer connected");
        try
        {
            var buffer = new byte[1024];
            while (socket.State == WebSocketState.Open)
            {
                var result = await socket.ReceiveAsync(buffer, CancellationToken.None).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close) break;
            }
        }
        catch
        {
        }
        finally
        {
            lock (_gate) _audioClients.Remove(client);
            await client.DisposeAsync().ConfigureAwait(false);
            AppLog.Info("Audio renderer disconnected");
        }
    }

    private void OnClientText(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            var type = root.TryGetProperty("type", out var typeElement) ? typeElement.GetString() : "";
            switch (type)
            {
                case "ready":
                    NeedKey?.Invoke();
                    break;
                case "need-key":
                    NeedKey?.Invoke();
                    break;
                case "stats":
                    if (root.TryGetProperty("decodeFps", out var fps) && fps.TryGetDouble(out var value))
                    {
                        ViewerDecodeFps?.Invoke(value);
                    }
                    break;
            }
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Bad renderer message: {ex.Message}");
        }
    }

    private static int GetFreePort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        try
        {
            listener.Start();
            return ((IPEndPoint)listener.LocalEndpoint).Port;
        }
        finally
        {
            listener.Stop();
        }
    }

    public async ValueTask DisposeAsync()
    {
        Client[] clients;
        Client[] audioClients;
        lock (_gate)
        {
            clients = _clients.ToArray();
            _clients.Clear();
            audioClients = _audioClients.ToArray();
            _audioClients.Clear();
        }
        foreach (var client in clients.Concat(audioClients))
        {
            await client.DisposeAsync().ConfigureAwait(false);
        }
        if (_app is not null)
        {
            try
            {
                await _app.StopAsync().WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
            }
            catch
            {
            }
            await _app.DisposeAsync().ConfigureAwait(false);
            _app = null;
        }
    }

    private sealed class Client : IAsyncDisposable
    {
        private readonly WebSocket _socket;
        private readonly Action<string> _onText;
        private readonly CancellationTokenSource _cts = new();
        private readonly Channel<Outbound> _outbound;
        private readonly Task _sender;

        public Client(WebSocket socket, Action<string> onText, int capacity = 12)
        {
            _socket = socket;
            _onText = onText;
            _outbound = Channel.CreateBounded<Outbound>(new BoundedChannelOptions(capacity)
            {
                FullMode = BoundedChannelFullMode.DropOldest,
                SingleReader = true,
                SingleWriter = false
            });
            _sender = Task.Run(SendLoopAsync);
        }

        public void EnqueueBinary(byte[] message) =>
            _outbound.Writer.TryWrite(new Outbound(message, WebSocketMessageType.Binary));

        public void EnqueueText(string text) =>
            _outbound.Writer.TryWrite(new Outbound(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text));

        private async Task SendLoopAsync()
        {
            try
            {
                await foreach (var message in _outbound.Reader.ReadAllAsync(_cts.Token).ConfigureAwait(false))
                {
                    if (_socket.State != WebSocketState.Open) break;
                    await _socket.SendAsync(
                        message.Data,
                        message.Type,
                        true,
                        _cts.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (WebSocketException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
        }

        public async Task ReceiveLoopAsync()
        {
            var buffer = new byte[8192];
            try
            {
                while (_socket.State == WebSocketState.Open && !_cts.IsCancellationRequested)
                {
                    var result = await _socket.ReceiveAsync(buffer, _cts.Token).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    if (result.MessageType != WebSocketMessageType.Text) continue;
                    var json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                    _onText(json);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (WebSocketException)
            {
            }
        }

        public async ValueTask DisposeAsync()
        {
            try
            {
                _cts.Cancel();
            }
            catch
            {
            }
            try
            {
                _outbound.Writer.TryComplete();
                await _sender.WaitAsync(TimeSpan.FromSeconds(1)).ConfigureAwait(false);
            }
            catch
            {
            }
            try
            {
                if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                {
                    using var closeCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
                    await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", closeCts.Token)
                        .ConfigureAwait(false);
                }
            }
            catch
            {
            }
            _socket.Dispose();
            _cts.Dispose();
        }
    }

    private readonly record struct Outbound(byte[] Data, WebSocketMessageType Type);
}
