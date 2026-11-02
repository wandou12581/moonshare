namespace MoonView;

public static class HostParser
{
    /// <summary>
    /// Accepts 192.168.1.5, 2408:8207::1, [2408:8207::1], fe80::1%12 and hostnames.
    /// The TCP port is entered separately.
    /// </summary>
    public static string Normalize(string input)
    {
        var value = input.Trim();
        if (value.StartsWith('[') && value.EndsWith(']'))
        {
            value = value[1..^1];
        }
        return value;
    }
}
