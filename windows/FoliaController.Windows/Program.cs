using System.Diagnostics;

namespace FoliaController;

internal static class Program
{
    public const string ShowEventName = @"Local\FoliaController.Windows.Show";
    private const string MutexName = @"Local\FoliaController.Windows";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length > 2 && args[0] == "--fixture-test")
        {
            return SmokeTest.RunFixtures(args[1], args[2]);
        }
        if (args.Length > 2 && args[0] == "--desktop-lyrics-test")
        {
            return SmokeTest.DesktopLyricsCheck(args[1], args[2]);
        }
        if (args.Length > 1 && args[0] == "--render-desktop-lyrics")
        {
            return SmokeTest.RenderDesktopLyrics(args[1]);
        }
        if (args.Length > 0 && args[0] == "--smoke-test")
        {
            return SmokeTest.RunAsync(args).GetAwaiter().GetResult();
        }

        // One copy at a time: a second launch just brings the first one's window up — unless the
        // running copy is a different exe (an older build, or FoliaController.exe from before the
        // rename), which is closed so this one takes over.
        using var mutex = new Mutex(true, MutexName, out var isFirst);
        if (!isFirst && CloseOtherBuilds())
        {
            try { isFirst = mutex.WaitOne(TimeSpan.FromSeconds(8)); }
            catch (AbandonedMutexException) { isFirst = true; }
        }
        if (!isFirst)
        {
            try
            {
                using var existing = EventWaitHandle.OpenExisting(ShowEventName);
                existing.Set();
            }
            catch (Exception)
            {
                // The other copy is shutting down.
            }
            return 0;
        }

        using var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);

        Application.ThreadException += (_, e) => CrashLog.Write(e.Exception, fatal: false);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => CrashLog.Write(e.ExceptionObject as Exception, fatal: true);
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            CrashLog.Write(e.Exception, fatal: false);
            e.SetObserved();
        };

        ApplicationConfiguration.Initialize();
        var startInTray = args.Contains("--tray", StringComparer.OrdinalIgnoreCase);
        Application.Run(new TrayContext(showEvent, startInTray));
        return 0;
    }

    /// <summary>Ends running copies of this app that come from another exe file. True if any was found.</summary>
    private static bool CloseOtherBuilds()
    {
        var self = Environment.ProcessPath ?? "";
        var myId = Environment.ProcessId;
        var found = false;
        foreach (var name in new[] { "Flowrics", "FoliaController" })
        {
            foreach (var process in Process.GetProcessesByName(name))
            {
                using (process)
                {
                    try
                    {
                        if (process.Id == myId) continue;
                        var path = process.MainModule?.FileName ?? "";
                        if (string.Equals(path, self, StringComparison.OrdinalIgnoreCase)) continue;
                        process.Kill();
                        process.WaitForExit(5000);
                        found = true;
                    }
                    catch (Exception)
                    {
                        // Not ours to close, or already gone.
                    }
                }
            }
        }
        return found;
    }
}

internal static class AppPaths
{
    public static string Data { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FoliaController");

    public static string Ensure(string sub = "")
    {
        var path = sub.Length == 0 ? Data : Path.Combine(Data, sub);
        Directory.CreateDirectory(path);
        return path;
    }
}

internal static class CrashLog
{
    private static readonly object Gate = new();

    public static void Write(Exception? error, bool fatal)
    {
        if (error == null) return;
        try
        {
            lock (Gate)
            {
                File.AppendAllText(
                    Path.Combine(AppPaths.Ensure(), "error.log"),
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {(fatal ? "FATAL" : "error")} {error}\r\n\r\n");
            }
        }
        catch (Exception)
        {
            // Nothing else to do.
        }
        Debug.WriteLine(error);
    }
}
