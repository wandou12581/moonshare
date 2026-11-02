namespace MoonView;

/// <summary>
/// Resolves a writable per-user data directory. Normally this is
/// %LOCALAPPDATA%\MoonShare. Some locked-down Windows policies deny an apphost
/// write access there while still allowing Temp, so fall back gracefully instead
/// of failing at startup (WebView2 also needs a writable user-data folder).
/// </summary>
public static class AppPaths
{
    private static readonly Lazy<string> RootDirectory = new(ResolveRoot, true);

    public static string DataDirectory => RootDirectory.Value;
    public static string LogDirectory => Path.Combine(DataDirectory, "logs");
    public static string WebView2Directory => Path.Combine(DataDirectory, "WebView2");

    private static string ResolveRoot()
    {
        var candidates = new[]
        {
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MoonShare"),
            Path.Combine(Path.GetTempPath(), "MoonShare"),
            Path.Combine(AppContext.BaseDirectory, "MoonShareData")
        };

        foreach (var candidate in candidates)
        {
            if (string.IsNullOrWhiteSpace(candidate)) continue;
            try
            {
                Directory.CreateDirectory(candidate);
                var probe = Path.Combine(candidate, ".write-test");
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
                return candidate;
            }
            catch
            {
                // Try the next location.
            }
        }

        return Path.Combine(Path.GetTempPath(), "MoonShare");
    }
}
