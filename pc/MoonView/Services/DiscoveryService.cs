using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace MoonView.Services;

public sealed record DiscoveredDevice(
    string Name,
    string Address,
    int Port,
    bool Sharing,
    DateTimeOffset LastSeen)
{
    public string Endpoint => Address.Contains(':') ? $"[{Address}]:{Port}" : $"{Address}:{Port}";
}

/// <summary>
/// IPv4 LAN discovery. IPv6 is supported for direct connections (manual address);
/// IPv6 link-local discovery is deliberately not attempted because it needs an
/// interface-scoped multicast dance that is not portable across Windows adapters.
/// </summary>
public sealed class DiscoveryService : IDisposable
{
    public const int DiscoveryPort = 47802;
    private const string Probe = "MOONSHARE/1 DISCOVER";

    private readonly object _gate = new();
    private readonly Dictionary<string, DiscoveredDevice> _seen = new(StringComparer.OrdinalIgnoreCase);
    private CancellationTokenSource? _cts;
    private bool _disposed;

    public event Action<DiscoveredDevice>? DeviceFound;

    public void Scan(TimeSpan duration)
    {
        if (_disposed) return;
        CancellationTokenSource cts;
        lock (_gate)
        {
            _cts?.Cancel();
            _cts?.Dispose();
            _cts = new CancellationTokenSource();
            cts = _cts;
        }
        _ = Task.Run(() => ScanAsync(duration, cts.Token));
    }

    private async Task ScanAsync(TimeSpan duration, CancellationToken token)
    {
        try
        {
            using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, 0))
            {
                EnableBroadcast = true
            };
            udp.Client.ReceiveBufferSize = 64 * 1024;

            using var receiveCts = CancellationTokenSource.CreateLinkedTokenSource(token);
            var targets = BuildTargets();
            var receiver = ReceiveLoopAsync(udp, receiveCts.Token);
            var payload = Encoding.UTF8.GetBytes(Probe);
            for (var round = 0; round < 3 && !token.IsCancellationRequested; round++)
            {
                foreach (var target in targets)
                {
                    try
                    {
                        await udp.SendAsync(payload, target, token).ConfigureAwait(false);
                    }
                    catch (Exception ex) when (ex is SocketException or OperationCanceledException)
                    {
                        if (token.IsCancellationRequested) break;
                    }
                }
                await Task.Delay(260, token).ConfigureAwait(false);
            }

            var remaining = duration - TimeSpan.FromMilliseconds(780);
            if (remaining > TimeSpan.Zero)
            {
                await Task.Delay(remaining, token).ConfigureAwait(false);
            }

            receiveCts.Cancel();
            try
            {
                await receiver.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Discovery failed: {ex.Message}");
        }
    }

    private async Task ReceiveLoopAsync(UdpClient udp, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await udp.ReceiveAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException) when (!token.IsCancellationRequested)
            {
                continue;
            }

            try
            {
                var endpoint = result.RemoteEndPoint;
                var address = endpoint.Address.IsIPv4MappedToIPv6
                    ? endpoint.Address.MapToIPv4().ToString()
                    : endpoint.Address.ToString();

                using var document = JsonDocument.Parse(result.Buffer);
                var root = document.RootElement;
                if (!root.TryGetProperty("app", out var app) || app.GetString() != "MoonShare") continue;
                var name = root.TryGetProperty("name", out var nameElement)
                    ? nameElement.GetString() ?? "Android"
                    : "Android";
                var port = root.TryGetProperty("port", out var portElement)
                    ? portElement.GetInt32()
                    : 47800;
                var sharing = !root.TryGetProperty("sharing", out var sharingElement) || sharingElement.GetBoolean();
                if (port is < 1 or > 65535) continue;

                var device = new DiscoveredDevice(name, address, port, sharing, DateTimeOffset.Now);
                // A multi-homed PC can receive the same phone's reply from more than one
                // local interface. Collapse by device name + port so the list stays clean.
                var key = $"{name}\u001f{port}";
                lock (_gate)
                {
                    if (_seen.TryGetValue(key, out var old) &&
                        old.Address == device.Address &&
                        old.Sharing == device.Sharing)
                    {
                        _seen[key] = device;
                        continue;
                    }
                    _seen[key] = device;
                }
                DeviceFound?.Invoke(device);
            }
            catch (JsonException)
            {
            }
            catch (Exception ex)
            {
                AppLog.Warn($"Discovery reply ignored: {ex.Message}");
            }
        }
    }

    private static List<IPEndPoint> BuildTargets()
    {
        var targets = new List<IPEndPoint>
        {
            new(IPAddress.Broadcast, DiscoveryPort)
        };

        try
        {
            foreach (var nif in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nif.OperationalStatus != OperationalStatus.Up ||
                    nif.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                {
                    continue;
                }

                foreach (var unicast in nif.GetIPProperties().UnicastAddresses)
                {
                    if (unicast.Address.AddressFamily != AddressFamily.InterNetwork || unicast.IPv4Mask is null)
                    {
                        continue;
                    }
                    var ip = unicast.Address.GetAddressBytes();
                    var mask = unicast.IPv4Mask.GetAddressBytes();
                    if (ip.Length != 4 || mask.Length != 4) continue;
                    var broadcast = new byte[4];
                    for (var i = 0; i < 4; i++)
                    {
                        broadcast[i] = (byte)(ip[i] | ~mask[i]);
                    }
                    var endpoint = new IPEndPoint(new IPAddress(broadcast), DiscoveryPort);
                    if (!targets.Any(t => t.Address.Equals(endpoint.Address)))
                    {
                        targets.Add(endpoint);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Could not enumerate interfaces for discovery: {ex.Message}");
        }

        return targets;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        lock (_gate)
        {
            _cts?.Cancel();
            _cts?.Dispose();
            _cts = null;
        }
    }
}
