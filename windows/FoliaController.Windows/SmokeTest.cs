namespace FoliaController;

/// <summary>
/// `FoliaController.exe --smoke-test &lt;port&gt; &lt;log&gt;`: used by CI on a build server, where there is no
/// player and no desktop. Runs the real web server, hub and lyric search against a fake player,
/// and writes what it saw to the log. Stops when "&lt;log&gt;.stop" appears (or after 3 minutes).
/// </summary>
internal static class SmokeTest
{
    /// <summary>The same songs as the Android app's LiveLyricsTest.</summary>
    public static readonly SongQuery[] LiveSongs =
    {
        new("晴天", "周杰倫", "葉惠美", 269_000),
        new("告白氣球", "周杰倫", "周杰倫的床邊故事", 215_000),
        new("夜に駆ける", "YOASOBI", "THE BOOK", 261_000),
        new("Yoru ni Kakeru", "YOASOBI", "", 261_000),
        new("Lemon", "米津玄師", "", 276_000),
        new("Blinding Lights", "The Weeknd", "After Hours", 200_040),
    };

    /// <summary>Every source searched for each test song, and what the automatic pick would be.</summary>
    public static async Task<string> LiveReportAsync()
    {
        var report = new System.Text.StringBuilder("== live lyric search (Windows) ==\n");
        using (var diag = new CancellationTokenSource(TimeSpan.FromSeconds(30)))
        {
            report.Append("raw responses:\n").Append(await LyricRepository.DiagnosticsAsync(diag.Token));
        }
        foreach (var song in LiveSongs)
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(60));
            try
            {
                var (result, candidates) = await LyricRepository.FindAsync(song, cts.Token);
                report.Append($"{song.Title} / {song.Artist}: ");
                report.Append(result == null ? "NONE" :
                    $"{result.SourceLabel} [{result.Candidate.Title} / {result.Candidate.Artist}] lines={result.Doc.Lines.Count} " +
                    $"translation={result.Doc.Translation.Count} romaji={result.Doc.Romaji.Count}");
                report.Append('\n');
                foreach (var group in candidates.GroupBy(c => c.Source))
                {
                    var top = group.First();
                    report.Append($"    {group.Key}: {group.Count(c => c.Accepted)}/{group.Count()} accepted; top: {top.Title} / {top.Artist} score={top.Score}\n");
                }
                foreach (var source in new[] { "netease", "qq", "kugou", "lrclib" }.Where(s => candidates.All(c => c.Source != s)))
                {
                    report.Append($"    {source}: no results\n");
                }
                foreach (var (source, amll) in new[] { ("netease", "amll-ncm"), ("qq", "amll-qq") })
                {
                    var c = candidates.FirstOrDefault(x => x.Source == source && x.Accepted);
                    if (c == null) continue;
                    var amllDoc = await LyricRepository.FetchAsync(c with { Source = amll }, cts.Token);
                    report.Append($"    {amll} ({c.Id}): {(amllDoc == null ? "none" : $"lines={amllDoc.Lines.Count} wordByWord={amllDoc.WordByWord} translation={amllDoc.Translation.Count}")}\n");
                }
                var qq = candidates.FirstOrDefault(c => c.Source == "qq" && c.Accepted);
                if (qq != null)
                {
                    var doc = await LyricRepository.FetchAsync(qq, cts.Token);
                    report.Append($"    qq lyrics: {(doc == null ? "none" : $"lines={doc.Lines.Count} wordByWord={doc.WordByWord} translation={doc.Translation.Count} romaji={doc.Romaji.Count}")}\n");
                }
                var kg = candidates.FirstOrDefault(c => c.Source == "kugou" && c.Accepted);
                if (kg != null)
                {
                    var doc = await LyricRepository.FetchAsync(kg, cts.Token);
                    report.Append($"    kugou lyrics: {(doc == null ? "none" : $"lines={doc.Lines.Count} wordByWord={doc.WordByWord} translation={doc.Translation.Count} romaji={doc.Romaji.Count}")}\n");
                }
            }
            catch (Exception ex)
            {
                report.Append($"{song.Title}: ERROR {ex.GetType().Name} {ex.Message}\n");
            }
        }
        return report.ToString();
    }

    /// <summary>
    /// `--fixture-test &lt;dir&gt; &lt;out&gt;`: every fixture in tests/lyrics through LyricFormats.cs, compared with
    /// the .expected files the Android app's Kotlin code produced. Exit code 0 only if all match.
    /// </summary>
    public static int RunFixtures(string dir, string outPath)
    {
        var lines = new List<string>();
        var failures = 0;
        foreach (var file in Directory.GetFiles(dir).OrderBy(f => Path.GetFileName(f), StringComparer.Ordinal))
        {
            var name = Path.GetFileName(file);
            var ext = Path.GetExtension(file).TrimStart('.');
            if (!new[] { "yrc", "qrc", "krc", "lrc", "ttml" }.Contains(ext) || name.EndsWith(".trans.lrc") || name.EndsWith(".roma.lrc")) continue;
            var text = File.ReadAllText(file);
            var doc = ext switch
            {
                "yrc" => LyricFormats.ParseYrc(text),
                "qrc" => LyricFormats.ParseQrc(text),
                "krc" => LyricFormats.ParseKrc(text),
                "ttml" => LyricFormats.ParseTtml(text),
                _ => LyricFormats.ParseLrc(text),
            };
            var baseName = name[..name.LastIndexOf('.')];
            var trans = Path.Combine(dir, baseName + ".trans.lrc");
            if (File.Exists(trans)) doc = doc with { Translation = LyricFormats.ParseTrack(File.ReadAllText(trans)) };
            var roma = Path.Combine(dir, baseName + ".roma.lrc");
            if (File.Exists(roma)) doc = doc with { Romaji = LyricFormats.ParseTrack(File.ReadAllText(roma)) };
            var actual = LyricFormats.ToFoliaContainer(doc);
            var expectedPath = file + ".expected";
            var ok = File.Exists(expectedPath) && File.ReadAllText(expectedPath) == actual;
            if (!ok) failures++;
            lines.Add($"{(ok ? "PASS" : "FAIL")}  {name}" + (ok ? "" : "\n  got:      " + actual + "\n  expected: " + (File.Exists(expectedPath) ? File.ReadAllText(expectedPath) : "(missing)")));
        }
        foreach (var vector in System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(Path.Combine(dir, "qrc-vectors.json")))!.AsArray())
        {
            string got;
            try { got = LyricFormats.DecryptQrc((string)vector!["hex"]!); } catch (Exception ex) { got = "ERROR " + ex.Message; }
            var ok = got == (string)vector!["plain"]!;
            if (!ok) failures++;
            lines.Add($"{(ok ? "PASS" : "FAIL")}  qrc vector ({((string)vector["hex"]!).Length} hex)" + (ok ? "" : "  got: " + got[..Math.Min(80, got.Length)]));
        }
        foreach (var vector in System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(Path.Combine(dir, "krc-vectors.json")))!.AsArray())
        {
            string got;
            try { got = LyricFormats.DecryptKrc((string)vector!["base64"]!); } catch (Exception ex) { got = "ERROR " + ex.Message; }
            var ok = got == (string)vector!["plain"]!;
            if (!ok) failures++;
            lines.Add($"{(ok ? "PASS" : "FAIL")}  krc vector" + (ok ? "" : "  got: " + got[..Math.Min(80, got.Length)]));
        }
        lines.Add(failures == 0 ? "all fixtures match" : $"{failures} fixture(s) differ");
        File.WriteAllLines(outPath, lines);
        return failures == 0 ? 0 : 1;
    }

    public static async Task<int> RunAsync(string[] args)
    {
        var port = args.Length > 1 && int.TryParse(args[1], out var p) ? p : 39999;
        var logPath = args.Length > 2 ? args[2] : "smoke.log";
        await using var log = new StreamWriter(logPath, append: false) { AutoFlush = true };
        try
        {
            await log.WriteLineAsync("to traditional: " + ChineseConverter.ToTraditional("故事的小黄花 从出生那年就飘着"));
            await log.WriteLineAsync("to simplified: " + ChineseConverter.ToSimplified("晴天 周杰倫 葉惠美"));
            var japanese = LyricFormats.ParseLrc("[00:01.00]夜に駆ける 沈むように");
            await log.WriteLineAsync("japanese kept: " + LyricFormats.ConvertChinese(japanese, ChineseConverter.ToTraditional).Lines[0].Text);
            await log.WriteLineAsync("clean title: " + LyricRepository.CleanTitle("Hey Jude - Remastered 2015") + " | " + LyricRepository.CleanTitle("七里香（Live）"));
            await log.WriteLineAsync("primary artist: " + LyricRepository.PrimaryArtist("Jay Chou, Mayday") + " | " + LyricRepository.PrimaryArtist("Simon & Garfunkel"));
            await log.WriteLineAsync("");

            var assets = WebAssets.Load();
            await log.WriteLineAsync($"assets: folia files={assets.FoliaFileCount} index={assets.IndexHtml.Length} launcher={assets.LauncherHtml.Length} icon={assets.AppIcon.Length}");

            var settings = new AppSettings { Persist = false };
            LyricChoices.Persist = false;
            var source = new FakeSource();
            var hub = new MediaHub(source, settings);
            using var server = new WebServer(assets, hub);
            hub.Broadcast = server.Broadcast;
            hub.LanInfo = () => (false, Array.Empty<string>());
            server.Start(port, lan: false);
            await log.WriteLineAsync($"listening on {server.Port}");
            await source.StartAsync();

            // The live search over every lyric source runs while the protocol checks talk to the server.
            var live = LiveReportAsync();
            var stopFile = logPath + ".stop";
            for (var i = 0; i < 600 && !(File.Exists(stopFile) && live.IsCompleted); i++) await Task.Delay(500);
            await log.WriteLineAsync("player commands: " + string.Join(", ", source.Commands));
            await log.WriteLineAsync("settings after test: offset=" + settings.OffsetMs);
            await log.WriteLineAsync("");
            await log.WriteLineAsync(live.IsCompleted ? await live : "live report did not finish in time");
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
