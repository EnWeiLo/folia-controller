using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;

namespace FoliaController;

/// <summary>Settings shared by every screen (this window and other devices on the LAN).</summary>
internal sealed class AppSettings
{
    public int Port { get; set; } = 39863;
    public bool AllowLan { get; set; }
    public bool Translation { get; set; } = true;
    public bool Traditional { get; set; } = true;
    public int OffsetMs { get; set; }
    public int[]? WindowBounds { get; set; }
    public bool WindowMaximized { get; set; }
    public bool TrayHintShown { get; set; }
    public string? LocalLyricsFolder { get; set; }
    public bool DesktopLyrics { get; set; }
    /// <summary>音樂律動: feed what the PC plays into Folia's audio-reactive visuals.</summary>
    public bool AudioReactive { get; set; } = true;
    public bool DesktopLyricsLocked { get; set; }
    /// <summary>Lyric text size in pixels at 100% display scaling.</summary>
    public int DesktopLyricsSize { get; set; } = 34;
    /// <summary>Window position (left, top) in screen pixels; null = bottom centre of the main screen.</summary>
    public int[]? DesktopLyricsPosition { get; set; }

    /// <summary>False for the CI smoke test, which must not touch the real settings file.</summary>
    [JsonIgnore]
    public bool Persist { get; set; } = true;

    private static readonly object Gate = new();
    private static string FilePath => Path.Combine(AppPaths.Data, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var loaded = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath));
                if (loaded != null)
                {
                    if (loaded.Port is < 1024 or > 65000) loaded.Port = 39863;
                    loaded.OffsetMs = Math.Clamp(loaded.OffsetMs, -5000, 5000);
                    loaded.DesktopLyricsSize = Math.Clamp(loaded.DesktopLyricsSize, DesktopLyricsRenderer.MinSize, DesktopLyricsRenderer.MaxSize);
                    return loaded;
                }
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
        return new AppSettings();
    }

    public void Save()
    {
        if (!Persist) return;
        try
        {
            lock (Gate)
            {
                AppPaths.Ensure();
                var temp = FilePath + ".tmp";
                File.WriteAllText(temp, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
                File.Move(temp, FilePath, overwrite: true);
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
    }
}

/// <summary>"Start with Windows", as a per-user Run entry that starts in the tray.</summary>
internal static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "FoliaController";

    private static string ExePath => Environment.ProcessPath ?? Application.ExecutablePath;

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(ValueName) is string value &&
               value.Contains(ExePath, StringComparison.OrdinalIgnoreCase);
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(ValueName, $"\"{ExePath}\" --tray");
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}
