using System.Text.Json;

namespace MoonView;

public sealed class AppSettings
{
    public string Address { get; set; } = "";
    public int Port { get; set; } = 47800;
}

public static class SettingsStore
{
    private static string DirectoryPath => AppPaths.DataDirectory;
    private static string FilePath => Path.Combine(DirectoryPath, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return new AppSettings();
            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Could not read settings: {ex.Message}");
            return new AppSettings();
        }
    }

    public static void Save(AppSettings settings)
    {
        try
        {
            Directory.CreateDirectory(DirectoryPath);
            File.WriteAllText(
                FilePath,
                JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Could not save settings: {ex.Message}");
        }
    }
}
