using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace FoliaController;

/// <summary>
/// 自動更新: looks at this app's latest GitHub release and, on request, swaps the running exe for the
/// new one in place — same folder, same file name — so the firewall rule and "start with Windows"
/// keep pointing at it. (Windows lets a running exe be renamed but not overwritten: the old one is
/// moved aside as .old, the new one takes its name, and a new copy starts once this one has exited.)
/// </summary>
internal static class Updater
{
    private const string Repo = "EnWeiLo/folia-controller";
    private static readonly HttpClient Http = CreateClient();
    private static readonly object Gate = new();

    private static string _state = "idle"; // idle | checking | available | uptodate | downloading | installing | error
    private static int _latest;
    private static string _url = "";
    private static string _page = "";
    private static int _percent;
    private static string _message = "";
    private static DateTime _lastBroadcast;

    /// <summary>Something about the update changed: tell the settings panels.</summary>
    public static event Action? Changed;
    /// <summary>A new build was found by a background check (not by pressing the button).</summary>
    public static event Action<int>? FoundInBackground;
    /// <summary>Set by the app: close it so the new exe can start.</summary>
    public static Action? Quit { get; set; }

    public static int CurrentBuild { get; } =
        System.Reflection.Assembly.GetExecutingAssembly().GetName().Version?.Build ?? 0;

    private static string ExePath => Environment.ProcessPath ?? Application.ExecutablePath;

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Flowrics", "1.0"));
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return client;
    }

    public static JsonObject Snapshot()
    {
        lock (Gate)
        {
            return new JsonObject
            {
                ["state"] = _state,
                ["latest"] = _latest > 0 ? $"1.0.{_latest}" : "",
                ["available"] = _state is "available" or "downloading" or "installing",
                ["percent"] = _percent,
                ["message"] = _message,
                ["page"] = _page,
                ["canInstall"] = _canReplace.Value,
            };
        }
    }

    /// <summary>Removes the leftover from the last update; call once at startup.</summary>
    public static void CleanUp()
    {
        try
        {
            var old = ExePath + ".old";
            if (File.Exists(old)) File.Delete(old);
            var partial = ExePath + ".new";
            if (File.Exists(partial)) File.Delete(partial);
        }
        catch (Exception)
        {
            // Still in use for a moment; the next start tries again.
        }
    }

    private static readonly Lazy<bool> _canReplace = new(CanReplaceExe);

    private static bool CanReplaceExe()
    {
        try
        {
            var dir = Path.GetDirectoryName(ExePath);
            if (dir == null) return false;
            var probe = Path.Combine(dir, ".flowrics-write-test");
            File.WriteAllText(probe, "");
            File.Delete(probe);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static void Set(string state, string message = "", int percent = 0, bool force = true)
    {
        lock (Gate)
        {
            _state = state;
            _message = message;
            _percent = percent;
            if (!force && (DateTime.UtcNow - _lastBroadcast).TotalMilliseconds < 400) return;
            _lastBroadcast = DateTime.UtcNow;
        }
        Changed?.Invoke();
    }

    /// <summary>Asks GitHub for the newest release. Quiet unless <paramref name="manual"/>.</summary>
    public static async Task CheckAsync(bool manual)
    {
        lock (Gate)
        {
            if (_state is "checking" or "downloading" or "installing") return;
        }
        if (manual || _state != "available") Set("checking");
        try
        {
            using var response = await Http.GetAsync($"https://api.github.com/repos/{Repo}/releases/latest");
            response.EnsureSuccessStatusCode();
            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            var root = doc.RootElement;
            var tag = root.GetProperty("tag_name").GetString() ?? "";
            var build = tag.StartsWith("build-", StringComparison.Ordinal) && int.TryParse(tag["build-".Length..], out var n) ? n : 0;
            string url = "";
            foreach (var asset in root.GetProperty("assets").EnumerateArray())
            {
                var name = asset.GetProperty("name").GetString() ?? "";
                if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                {
                    url = asset.GetProperty("browser_download_url").GetString() ?? "";
                    break;
                }
            }
            var page = root.TryGetProperty("html_url", out var h) ? h.GetString() ?? "" : "";
            if (build > CurrentBuild && url.Length > 0)
            {
                var first = false;
                lock (Gate)
                {
                    first = _latest != build;
                    _latest = build;
                    _url = url;
                    _page = page;
                }
                Set("available");
                if (!manual && first) FoundInBackground?.Invoke(build);
            }
            else
            {
                Set("uptodate");
            }
        }
        catch (Exception ex)
        {
            if (manual) Set("error", "連不上 GitHub，稍後再試。");
            else Set("idle");
            Debug.WriteLine(ex);
        }
    }

    public static void RequestInstall()
    {
        _ = Task.Run(InstallAsync);
    }

    private static async Task InstallAsync()
    {
        string url;
        lock (Gate)
        {
            if (_state != "available" || _url.Length == 0) return;
            url = _url;
        }
        var exe = ExePath;
        var fresh = exe + ".new";
        var old = exe + ".old";
        try
        {
            Set("downloading", "", 0);
            using (var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead))
            {
                response.EnsureSuccessStatusCode();
                var total = response.Content.Headers.ContentLength ?? 0;
                await using var input = await response.Content.ReadAsStreamAsync();
                await using var output = new FileStream(fresh, FileMode.Create, FileAccess.Write, FileShare.None);
                var buffer = new byte[128 * 1024];
                long done = 0;
                int read;
                while ((read = await input.ReadAsync(buffer)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, read));
                    done += read;
                    if (total > 0) Set("downloading", "", (int)(done * 100 / total), force: false);
                }
                if (total > 0 && done != total) throw new IOException("incomplete download");
            }
            var info = new FileInfo(fresh);
            using (var check = File.OpenRead(fresh))
            {
                if (info.Length < 1_000_000 || check.ReadByte() != 'M' || check.ReadByte() != 'Z') throw new IOException("not an exe");
            }

            Set("installing");
            if (File.Exists(old)) File.Delete(old);
            File.Move(exe, old);
            try
            {
                File.Move(fresh, exe);
            }
            catch (Exception)
            {
                File.Move(old, exe); // put the old one back
                throw;
            }

            // Start the new copy a moment after this one has exited (its single-instance lock is released then).
            var start = new ProcessStartInfo("cmd.exe", $"/c ping -n 3 127.0.0.1 >nul & start \"\" \"{exe}\"")
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            Process.Start(start);
            Quit?.Invoke();
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
            try { if (File.Exists(fresh)) File.Delete(fresh); } catch (Exception) { }
            Set("error", "更新失敗，可以到下載頁手動下載。");
        }
    }
}
