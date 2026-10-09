using System.Diagnostics;

namespace FoliaController;

internal static class Program
{
    public const string ShowEventName = @"Local\FoliaController.Windows.Show";
    private const string MutexName = @"Local\FoliaController.Windows";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--smoke-test")
        {
            return SmokeTest.RunAsync(args).GetAwaiter().GetResult();
        }

        // One copy at a time: a second launch just brings the first one's window up.
        using var mutex = new Mutex(true, MutexName, out var isFirst);
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
