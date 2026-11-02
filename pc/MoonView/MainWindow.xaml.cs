using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using MoonView.Services;

namespace MoonView;

public partial class MainWindow : Window
{
    private readonly ViewerServer _server = new(Path.Combine(AppContext.BaseDirectory, "web"));
    private readonly DiscoveryService _discovery = new();
    private readonly ObservableCollection<DiscoveredDevice> _devices = new();
    private readonly DispatcherTimer _discoveryTimer;
    private readonly AppSettings _settings = SettingsStore.Load();
    private readonly string _viewerName = Environment.MachineName;

    private ShareClient? _client;
    private CancellationTokenSource? _connectCts;
    private bool _webReady;
    private int _firstFrame;
    private bool _isFullscreen;
    private WindowState _previousWindowState = WindowState.Normal;
    private bool _closing;
    private bool _shutdownStarted;

    public MainWindow()
    {
        InitializeComponent();
        DeviceList.ItemsSource = _devices;
        _discovery.DeviceFound += OnDeviceFound;
        _server.ViewerReady += OnViewerReady;
        _server.NeedKey += () => _client?.RequestKeyFrame();
        _discoveryTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
        _discoveryTimer.Tick += (_, _) =>
        {
            if (_client is null && !_closing) _discovery.Scan(TimeSpan.FromSeconds(3));
        };
        Loaded += Window_Loaded;
        Closing += Window_Closing;
        SourceInitialized += (_, _) =>
        {
            FitToWorkArea();
            ApplyDarkTitleBar();
        };
        SetState("idle");
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        AddressBox.Text = string.IsNullOrWhiteSpace(_settings.Address) ? "192.168.1.100" : _settings.Address;
        PortBox.Text = _settings.Port.ToString();
        RefreshButton.Content = "刷新";
        try
        {
            await _server.StartAsync();
            await InitializeWebViewAsync();
        }
        catch (Exception ex)
        {
            AppLog.Error("Viewer startup failed", ex);
            ShowError($"查看器初始化失败: {ex.Message}");
            ConnectButton.IsEnabled = false;
            return;
        }

        _discovery.Scan(TimeSpan.FromSeconds(4));
        _discoveryTimer.Start();

        var args = Environment.GetCommandLineArgs();
        var host = GetArg(args, "--host");
        var code = GetArg(args, "--code");
        var portText = GetArg(args, "--port");
        if (!string.IsNullOrWhiteSpace(host) && !string.IsNullOrWhiteSpace(code))
        {
            var port = int.TryParse(portText, out var parsedPort) ? parsedPort : 47800;
            await Task.Delay(650);
            await ConnectAsync(host, port, code.Trim());
        }
    }

    private async Task InitializeWebViewAsync()
    {
        var dataFolder = AppPaths.WebView2Directory;
        Directory.CreateDirectory(dataFolder);
        var options = new CoreWebView2EnvironmentOptions(
            "--autoplay-policy=no-user-gesture-required");
        var environment = await CoreWebView2Environment.CreateAsync(null, dataFolder, options);
        await VideoView.EnsureCoreWebView2Async(environment);
        VideoView.DefaultBackgroundColor = System.Drawing.Color.Black;
        if (VideoView.CoreWebView2 is null) throw new InvalidOperationException("WebView2 未初始化");
        VideoView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
        VideoView.CoreWebView2.Settings.AreDevToolsEnabled = false;
        VideoView.CoreWebView2.Settings.IsStatusBarEnabled = false;
        VideoView.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
        VideoView.CoreWebView2.NewWindowRequested += (_, args) => args.Handled = true;
        VideoView.CoreWebView2.WebMessageReceived += (_, args) =>
        {
            try
            {
                using var message = JsonDocument.Parse(args.WebMessageAsJson);
                if (message.RootElement.TryGetProperty("type", out var type) &&
                    type.GetString() == "toggle-fullscreen")
                {
                    ToggleFullscreen();
                }
            }
            catch
            {
            }
        };
        VideoView.CoreWebView2.Navigate(_server.Url);
        AppLog.Info($"Viewer UI loading {_server.Url}");
    }

    // ---- Connection UI ----

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        if (_client is not null)
        {
            await DisconnectAsync("已断开");
            return;
        }

        var host = HostParser.Normalize(AddressBox.Text);
        if (string.IsNullOrWhiteSpace(host))
        {
            ShowError("请输入手机屏幕上显示的 IP 地址。");
            return;
        }
        if (!int.TryParse(PortBox.Text.Trim(), out var port) || port is < 1 or > 65535)
        {
            ShowError("端口必须是 1–65535 之间的数字。");
            return;
        }
        var code = new string(CodeBox.Text.Where(char.IsDigit).ToArray());
        if (code.Length != 6)
        {
            ShowError("请输入手机屏幕上显示的 6 位配对码。");
            CodeBox.Focus();
            return;
        }

        await ConnectAsync(host, port, code);
    }

    private async Task ConnectAsync(string host, int port, string code)
    {
        if (_client is not null) return;
        HideError();
        AddressBox.Text = host;
        PortBox.Text = port.ToString();
        CodeBox.Text = code;
        _connectCts?.Dispose();
        _connectCts = new CancellationTokenSource();
        SetInputsEnabled(false);
        ConnectButton.Content = "连接中…";
        ConnectButton.IsEnabled = false;
        SetState("connecting");
        StatusText.Text = "正在连接";
        SessionTitle.Text = "正在连接手机";
        SessionSubtitle.Text = $"{host}:{port}";
        StatsStateText.Text = "连接中";

        var client = new ShareClient();
        _client = client;
        _firstFrame = 0;

        client.Welcomed += welcome => Dispatcher.BeginInvoke(() => OnWelcome(client, welcome));
        client.FrameReady += frame =>
        {
            if (Interlocked.Exchange(ref _firstFrame, 1) == 0)
            {
                Dispatcher.BeginInvoke(() => OnFirstFrame(client));
            }
            _server.BroadcastFrame(frame.Data, frame.KeyFrame, frame.PresentationTimeUs);
        };
        client.AudioFrameReady += pcm => _server.BroadcastAudio(pcm);
        client.StatsUpdated += stats => Dispatcher.BeginInvoke(() => OnStats(client, stats));
        client.StreamChanged += info => Dispatcher.BeginInvoke(() => OnStreamChanged(client, info));
        client.Disconnected += reason => Dispatcher.BeginInvoke(() => OnDisconnected(client, reason));

        try
        {
            var welcome = await client.ConnectAsync(
                host,
                port,
                code,
                _viewerName,
                _connectCts.Token);
            _settings.Address = host;
            _settings.Port = port;
            SettingsStore.Save(_settings);
            _server.SetStreamInfo(welcome.Width, welcome.Height, welcome.Fps);
            _server.SetStatus("waiting", "已配对，等待手机发送画面…", welcome.Name);
            if (_webReady)
            {
                StartVideo(client);
            }
            AppLog.Info($"Connected to {welcome.Name} ({welcome.Width}x{welcome.Height})");
        }
        catch (OperationCanceledException)
        {
            await CleanupFailedClient(client);
        }
        catch (ShareException ex)
        {
            AppLog.Warn($"Pairing failed: {ex.Code} {ex.Message}");
            await CleanupFailedClient(client);
            ShowError(ex.Message);
            SetState("error");
        }
        catch (Exception ex)
        {
            AppLog.Error("Connect failed", ex);
            await CleanupFailedClient(client);
            ShowError($"连接失败: {ex.Message}");
            SetState("error");
        }
    }

    private void OnViewerReady()
    {
        Dispatcher.BeginInvoke(() =>
        {
            _webReady = true;
            if (_client is { } client && !client.IsVideoStarted)
            {
                StartVideo(client);
            }
            if (_client?.Welcome is { } welcome)
            {
                _server.SetStreamInfo(welcome.Width, welcome.Height, welcome.Fps);
            }
        });
    }

    private void StartVideo(ShareClient client)
    {
        try
        {
            client.StartVideo();
        }
        catch (Exception ex)
        {
            AppLog.Error("Could not start UDP video", ex);
            ShowError($"无法建立视频通道: {ex.Message}");
        }
    }

    private void OnWelcome(ShareClient client, WelcomeInfo welcome)
    {
        if (_client != client) return;
        _server.SetStreamInfo(welcome.Width, welcome.Height, welcome.Fps);
        SessionTitle.Text = welcome.Name;
        SessionSubtitle.Text = $"{welcome.Width} × {welcome.Height} · H.264 · {welcome.Fps} fps";
        ResolutionText.Text = $"{welcome.Width} × {welcome.Height}";
        DisconnectButton.IsEnabled = true;
        ConnectButton.Content = "断开连接";
        ConnectButton.Style = (Style)FindResource("DangerButton");
        ConnectButton.IsEnabled = true;
        if (_firstFrame == 0)
        {
            StatsStateText.Text = "等待视频";
            StatusText.Text = "已配对";
            SetState("waiting");
        }
    }

    private void OnFirstFrame(ShareClient client)
    {
        if (_client != client) return;
        _server.SetStatus("live", "正在接收画面", _client?.Welcome?.Name ?? "");
        SetState("live");
        StatusText.Text = "正在观看";
        StatsStateText.Text = "直播中";
        SessionSubtitle.Text = "只读画面 · 局域网直连";
    }

    private void OnStats(ShareClient client, NetworkStats stats)
    {
        if (_client != client) return;
        FpsText.Text = $"{stats.Fps:0.0} fps";
        BitrateText.Text = $"{stats.Kbps / 1000.0:0.0} Mbps";
        DroppedText.Text = stats.DroppedFrames.ToString();
        _server.BroadcastText(JsonSerializer.Serialize(new
        {
            type = "stats",
            fps = stats.Fps,
            kbps = stats.Kbps,
            dropped = stats.DroppedFrames
        }));
    }

    private void OnStreamChanged(ShareClient client, StreamInfo info)
    {
        if (_client != client) return;
        ResolutionText.Text = $"{info.Width} × {info.Height}";
        _server.SetStreamInfo(info.Width, info.Height, info.Fps);
    }

    private async void OnDisconnected(ShareClient client, string reason)
    {
        if (_client != client) return;
        _client = null;
        _server.SetStatus("idle", reason, "");
        await ResetConnectionUi(reason);
    }

    private async Task CleanupFailedClient(ShareClient client)
    {
        if (_client == client) _client = null;
        try
        {
            await client.DisposeAsync();
        }
        catch
        {
        }
        ResetInputs();
        DisconnectButton.IsEnabled = false;
    }

    private async Task DisconnectAsync(string reason)
    {
        var client = _client;
        _client = null;
        _connectCts?.Cancel();
        if (client is not null)
        {
            try
            {
                await client.DisposeAsync();
            }
            catch (Exception ex)
            {
                AppLog.Warn($"Dispose client: {ex.Message}");
            }
        }
        _server.SetStatus("idle", reason, "");
        await ResetConnectionUi(reason);
    }

    private Task ResetConnectionUi(string reason)
    {
        ResetInputs();
        DisconnectButton.IsEnabled = false;
        StatsStateText.Text = "空闲";
        FpsText.Text = "—";
        BitrateText.Text = "—";
        DroppedText.Text = "—";
        ResolutionText.Text = "—";
        SessionTitle.Text = "等待连接";
        SessionSubtitle.Text = "从左侧选择设备，或输入手机屏幕上显示的地址";
        StatusText.Text = "未连接";
        SetState("idle");
        if (!string.IsNullOrWhiteSpace(reason) && reason != "已断开")
        {
            ShowError(reason);
        }
        return Task.CompletedTask;
    }

    private void ResetInputs()
    {
        SetInputsEnabled(true);
        CodeBox.Clear();
        ConnectButton.Content = "连接并观看";
        ConnectButton.Style = (Style)FindResource("PrimaryButton");
        ConnectButton.IsEnabled = true;
    }

    private void SetInputsEnabled(bool enabled)
    {
        AddressBox.IsEnabled = enabled;
        PortBox.IsEnabled = enabled;
        CodeBox.IsEnabled = enabled;
        DeviceList.IsEnabled = enabled;
        RefreshButton.IsEnabled = enabled;
        if (enabled) ConnectButton.IsEnabled = true;
    }

    private void SetState(string state)
    {
        var color = state switch
        {
            "live" => "#6EE7D1",
            "waiting" => "#58A6FF",
            "connecting" => "#FBBF24",
            "error" => "#F87171",
            _ => "#4E6278"
        };
        StatusDot.Fill = BrushFromHex(color);
        StatusText.Foreground = BrushFromHex(state == "idle" ? "#7E91A6" : color);
    }

    // ---- Discovery ----

    private void OnDeviceFound(DiscoveredDevice device)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var existing = _devices.FirstOrDefault(d =>
                d.Name == device.Name && d.Port == device.Port);
            if (existing is not null)
            {
                var index = _devices.IndexOf(existing);
                if (index >= 0) _devices[index] = device;
            }
            else
            {
                _devices.Add(device);
            }
            DiscoveryEmpty.Visibility = Visibility.Collapsed;
        });
    }

    private void DeviceList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (DeviceList.SelectedItem is not DiscoveredDevice device) return;
        AddressBox.Text = device.Address;
        PortBox.Text = device.Port.ToString();
        CodeBox.Focus();
    }

    private void RefreshButton_Click(object sender, RoutedEventArgs e)
    {
        if (_client is not null) return;
        DiscoveryEmpty.Text = "正在搜索局域网内的 MoonShare…";
        DiscoveryEmpty.Visibility = Visibility.Visible;
        _discovery.Scan(TimeSpan.FromSeconds(4));
    }

    private void CodeBox_PreviewTextInput(object sender, TextCompositionEventArgs e)
    {
        e.Handled = e.Text.Any(c => !char.IsDigit(c));
    }

    // ---- Chrome / fullscreen ----

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.F11)
        {
            ToggleFullscreen();
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && _isFullscreen)
        {
            ToggleFullscreen();
            e.Handled = true;
        }
    }

    private void FullscreenButton_Click(object sender, RoutedEventArgs e) => ToggleFullscreen();

    private void ToggleFullscreen()
    {
        if (!_isFullscreen)
        {
            _previousWindowState = WindowState;
            _isFullscreen = true;
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Maximized;
            Sidebar.Visibility = Visibility.Collapsed;
            SidebarColumn.Width = new GridLength(0);
            ViewerHeader.Visibility = Visibility.Collapsed;
            HeaderRow.Height = new GridLength(0);
            FullscreenButton.Content = "退出全屏";
        }
        else
        {
            _isFullscreen = false;
            WindowStyle = WindowStyle.SingleBorderWindow;
            WindowState = _previousWindowState;
            Sidebar.Visibility = Visibility.Visible;
            SidebarColumn.Width = new GridLength(320);
            ViewerHeader.Visibility = Visibility.Visible;
            HeaderRow.Height = new GridLength(54);
            FullscreenButton.Content = "全屏  F11";
        }
    }

    private void DisconnectButton_Click(object sender, RoutedEventArgs e)
    {
        _ = DisconnectAsync("已断开");
    }

    // ---- Errors / lifecycle ----

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }

    private void HideError()
    {
        ErrorText.Visibility = Visibility.Collapsed;
    }

    private async void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_closing) return;
        e.Cancel = true;
        if (_shutdownStarted) return;
        _shutdownStarted = true;
        await ShutdownAsync();
        _closing = true;
        Close();
    }

    private async Task ShutdownAsync()
    {
        _discoveryTimer.Stop();
        _discovery.Dispose();
        _connectCts?.Cancel();
        var client = _client;
        _client = null;
        if (client is not null)
        {
            try
            {
                await client.DisposeAsync();
            }
            catch
            {
            }
        }
        try
        {
            VideoView.Dispose();
        }
        catch
        {
        }
        try
        {
            await _server.DisposeAsync();
        }
        catch
        {
        }
    }

    private void FitToWorkArea()
    {
        try
        {
            var handle = new WindowInteropHelper(this).Handle;
            var dpi = GetDpiForWindow(handle);
            if (dpi <= 0) dpi = 96;
            var screenWidth = GetSystemMetrics(0);
            var screenHeight = GetSystemMetrics(1);
            var availableWidth = screenWidth * 96.0 / dpi - 28;
            var availableHeight = screenHeight * 96.0 / dpi - 86;
            Width = Math.Min(1240, Math.Max(760, availableWidth));
            Height = Math.Min(780, Math.Max(480, availableHeight));
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = Math.Max(0, (screenWidth * 96.0 / dpi - Width) / 2);
            Top = Math.Max(0, (screenHeight * 96.0 / dpi - 58 - Height) / 2);
            AppLog.Info($"Window fit: {Width:0}x{Height:0} DIP at {Left:0},{Top:0}, screen {screenWidth}x{screenHeight} px, dpi {dpi}");
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Could not fit window: {ex.Message}");
        }
    }

    private static string? GetArg(string[] args, string name)
    {
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
            {
                return args[i + 1];
            }
        }
        return null;
    }

    private static Brush BrushFromHex(string hex) =>
        new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));

    private void ApplyDarkTitleBar()
    {
        try
        {
            var handle = new WindowInteropHelper(this).Handle;
            var enabled = 1;
            var result = DwmSetWindowAttribute(handle, 20, ref enabled, sizeof(int));
            if (result != 0)
            {
                DwmSetWindowAttribute(handle, 19, ref enabled, sizeof(int));
            }
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Dark title bar unavailable: {ex.Message}");
        }
    }

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    [DllImport("user32.dll")]
    private static extern int GetDpiForWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);
}
