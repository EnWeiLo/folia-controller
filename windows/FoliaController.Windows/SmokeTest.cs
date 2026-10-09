namespace FoliaController;

/// <summary>
/// `FoliaController.exe --smoke-test &lt;port&gt; &lt;log&gt;`: used by CI on a build server, where there is no
/// player and no desktop. Runs the real web server, hub and lyric search against a fake player,
/// and writes what it saw to the log. Stops when "&lt;log&gt;.stop" appears (or after 3 minutes).
/// </summary>
internal static class SmokeTest
{
    public static async Task<int> RunAsync(string[] args)
    {
        var port = args.Length > 1 && int.TryParse(args[1], out var p) ? p : 39999;
        var logPath = args.Length > 2 ? args[2] : "smoke.log";
        await using var log = new StreamWriter(logPath, append: false) { AutoFlush = true };
        try
        {
            await log.WriteLineAsync("to traditional: " + ChineseConverter.ToTraditional("故事的小黄花 从出生那年就飘着"));
            await log.WriteLineAsync("to simplified: " + ChineseConverter.ToSimplified("晴天 周杰倫 葉惠美"));
            await log.WriteLineAsync("japanese kept: " + ChineseConverter.ToTraditional("夜に駆ける 沈むように"));
            await log.WriteLineAsync("clean title: " + LyricRepository.CleanTitle("Hey Jude - Remastered 2015") + " | " + LyricRepository.CleanTitle("七里香（Live）"));
            await log.WriteLineAsync("primary artist: " + LyricRepository.PrimaryArtist("Jay Chou, Mayday") + " | " + LyricRepository.PrimaryArtist("Simon & Garfunkel"));

            foreach (var (title, artist, album, duration) in new[]
                     {
                         ("Blinding Lights", "The Weeknd", "After Hours", 200_040L),
                         ("晴天", "周杰倫", "葉惠美", 269_000L),
                         ("夜に駆ける", "YOASOBI", "", 261_000L),
                     })
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(40));
                var result = await LyricRepository.FindAsync(title, artist, album, duration, cts.Token);
                await log.WriteLineAsync(result == null
                    ? $"lyrics {title}: none"
                    : $"lyrics {title}: {result.SourceLabel} lrc={result.Lrc?.Length ?? 0} karaoke={result.Karaoke?.Length ?? 0} translation={result.Translation?.Length ?? 0}");
            }

            var assets = WebAssets.Load();
            await log.WriteLineAsync($"assets: folia files={assets.FoliaFileCount} index={assets.IndexHtml.Length} launcher={assets.LauncherHtml.Length} icon={assets.AppIcon.Length}");

            var settings = new AppSettings { Persist = false };
            var source = new FakeSource();
            var hub = new MediaHub(source, settings);
            using var server = new WebServer(assets, hub);
            hub.Broadcast = server.Broadcast;
            hub.LanInfo = () => (false, Array.Empty<string>());
            server.Start(port, lan: false);
            await log.WriteLineAsync($"listening on {server.Port}");
            await source.StartAsync();

            var stopFile = logPath + ".stop";
            for (var i = 0; i < 360 && !File.Exists(stopFile); i++) await Task.Delay(500);
            await log.WriteLineAsync("player commands: " + string.Join(", ", source.Commands));
            await log.WriteLineAsync("settings after test: translation=" + settings.Translation + " offset=" + settings.OffsetMs);
            await log.WriteLineAsync("done");
            return 0;
        }
        catch (Exception ex)
        {
            await log.WriteLineAsync("ERROR " + ex);
            return 1;
        }
    }
}
