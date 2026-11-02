using System.Text;

namespace MoonView;

public static class AppLog
{
    private static readonly object Gate = new();
    private static string DirectoryPath => AppPaths.LogDirectory;
    private static string FilePath => Path.Combine(DirectoryPath, "moonview.log");

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message, Exception? exception = null) =>
        Write("ERROR", exception is null ? message : $"{message}{Environment.NewLine}{exception}");

    private static void Write(string level, string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(DirectoryPath);
                var file = new FileInfo(FilePath);
                if (file.Exists && file.Length > 2 * 1024 * 1024)
                {
                    var backup = FilePath + ".old";
                    File.Delete(backup);
                    File.Move(FilePath, backup);
                }
                File.AppendAllText(
                    FilePath,
                    $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} [{level}] {message}{Environment.NewLine}",
                    Encoding.UTF8);
            }
        }
        catch
        {
            // Logging must never take the app down.
        }
    }
}
