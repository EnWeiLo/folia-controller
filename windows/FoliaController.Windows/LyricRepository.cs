using System.Net;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace FoliaController;

/// <summary>Timed lyrics for one song. Karaoke is NetEase YRC (word-by-word); Lrc is line-timed LRC.</summary>
internal sealed record LyricResult(string Source, string SourceLabel, string? Lrc, string? Karaoke, string? Translation);

/// <summary>
/// Finds synced lyrics for whatever another app is playing — the same rules as the Android app:
/// NetEase first for CJK titles (better coverage, word-by-word YRC), LRCLIB first otherwise.
/// </summary>
internal static class LyricRepository
{
    private const string AppUserAgent = "FoliaController/1.0 (Windows; personal lyrics display)";
    private const string BrowserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";

    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AutomaticDecompression = DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
    })
    {
        Timeout = TimeSpan.FromSeconds(12),
    };

    private static readonly Regex Timestamp = new(@"\[\d{1,3}:\d{1,2}", RegexOptions.Compiled);
    private static readonly Regex YrcLine = new(@"^\[\d+,\d+\]", RegexOptions.Compiled | RegexOptions.Multiline);
    private static readonly Regex Brackets = new(@"\s*[(\[（【「][^)\]）】」]*[)\]）】」]", RegexOptions.Compiled);
    private static readonly Regex DashSuffix = new(@"\s+[-–—]\s+.*$", RegexOptions.Compiled);
    // Players join several artists with ", " or "、"; "&", "/" and "x" are often part of one name
    // (Simon & Garfunkel, AC/DC, Lil Nas X), so they are not split on.
    private static readonly Regex ArtistSplit = new(@"\s*(?:,|，|、|;|\s+feat\.?\s+|\s+ft\.?\s+)\s*", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex Cjk = new(@"[぀-ヿ㐀-鿿가-힯]", RegexOptions.Compiled);

    private static readonly object CacheGate = new();
    private static readonly Dictionary<string, LyricResult> Cache = new();
    private static readonly Queue<string> CacheOrder = new();

    public static async Task<LyricResult?> FindAsync(string title, string artist, string album, long durationMs, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(title)) return null;
        var key = $"{title}|{artist}|{durationMs / 1000}";
        lock (CacheGate)
        {
            if (Cache.TryGetValue(key, out var cached)) return cached;
        }

        var preferNetease = Cjk.IsMatch(title + artist);
        var result = preferNetease
            ? await NeteaseAsync(title, artist, durationMs, ct) ?? await LrclibAsync(title, artist, album, durationMs, ct)
            : await LrclibAsync(title, artist, album, durationMs, ct) ?? await NeteaseAsync(title, artist, durationMs, ct);

        if (result != null)
        {
            lock (CacheGate)
            {
                if (Cache.TryAdd(key, result))
                {
                    CacheOrder.Enqueue(key);
                    while (CacheOrder.Count > 80) Cache.Remove(CacheOrder.Dequeue());
                }
            }
        }
        return result;
    }

    // ---------- LRCLIB ----------

    private static async Task<LyricResult?> LrclibAsync(string title, string artist, string album, long durationMs, CancellationToken ct)
    {
        var mainArtist = PrimaryArtist(artist);
        foreach (var t in TitleVariants(title))
        {
            if (durationMs > 0)
            {
                var query = $"track_name={Enc(t)}&artist_name={Enc(mainArtist)}";
                if (!string.IsNullOrWhiteSpace(album)) query += $"&album_name={Enc(album)}";
                query += $"&duration={durationMs / 1000}";
                var got = await GetAsync($"https://lrclib.net/api/get?{query}", LrclibHeaders, ct);
                if (got != null && got.Value.Status == 200)
                {
                    using var doc = Parse(got.Value.Body);
                    var synced = doc == null ? null : Str(doc.RootElement, "syncedLyrics");
                    if (synced != null && Timestamp.IsMatch(synced)) return new LyricResult("lrclib", "LRCLIB", synced, null, null);
                }
            }

            var found = await LrclibSearchAsync($"track_name={Enc(t)}&artist_name={Enc(mainArtist)}", durationMs, 4000, ct);
            if (found != null) return new LyricResult("lrclib", "LRCLIB", found, null, null);
            // Artist spelled differently (周杰倫 / Jay Chou): title only, but the length must match closely.
            if (durationMs > 0)
            {
                found = await LrclibSearchAsync($"track_name={Enc(t)}", durationMs, 2000, ct);
                if (found != null) return new LyricResult("lrclib", "LRCLIB", found, null, null);
            }
        }
        return null;
    }

    private static async Task<string?> LrclibSearchAsync(string query, long durationMs, long maxDiffMs, CancellationToken ct)
    {
        var got = await GetAsync($"https://lrclib.net/api/search?{query}", LrclibHeaders, ct);
        if (got == null || got.Value.Status != 200) return null;
        using var doc = Parse(got.Value.Body);
        if (doc == null || doc.RootElement.ValueKind != JsonValueKind.Array) return null;

        string? best = null;
        var bestDiff = long.MaxValue;
        foreach (var item in doc.RootElement.EnumerateArray())
        {
            var synced = Str(item, "syncedLyrics");
            if (synced == null || !Timestamp.IsMatch(synced)) continue;
            long diff = 0;
            if (durationMs > 0)
            {
                var seconds = item.TryGetProperty("duration", out var d) && d.ValueKind == JsonValueKind.Number ? d.GetDouble() : 0;
                diff = Math.Abs((long)(seconds * 1000) - durationMs);
                if (diff > maxDiffMs) continue;
            }
            if (diff < bestDiff)
            {
                bestDiff = diff;
                best = synced;
            }
        }
        return best;
    }

    private static readonly (string, string)[] LrclibHeaders = { ("User-Agent", AppUserAgent) };

    // ---------- NetEase Cloud Music ----------

    private static readonly (string, string)[] NeteaseHeaders =
    {
        ("Referer", "https://music.163.com/"),
        ("User-Agent", BrowserUserAgent),
        ("Cookie", "os=pc; appver=2.10.13"),
    };

    private static async Task<LyricResult?> NeteaseAsync(string title, string artist, long durationMs, CancellationToken ct)
    {
        var cleanedTitle = CleanTitle(title);
        var mainArtist = PrimaryArtist(artist);
        var wantTitle = Norm(cleanedTitle);
        var wantArtist = Norm(mainArtist);
        if (wantTitle.Length == 0) return null;

        var query = ChineseConverter.ToSimplified($"{cleanedTitle} {mainArtist}".Trim());
        var search = await GetAsync($"https://music.163.com/api/search/get/web?s={Enc(query)}&type=1&offset=0&limit=20", NeteaseHeaders, ct);
        if (search == null) return null;
        using var doc = Parse(search.Value.Body);
        if (doc == null ||
            !doc.RootElement.TryGetProperty("result", out var resultEl) || resultEl.ValueKind != JsonValueKind.Object ||
            !resultEl.TryGetProperty("songs", out var songs) || songs.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        long bestId = -1;
        var bestScore = 0;
        foreach (var song in songs.EnumerateArray())
        {
            var name = Norm(CleanTitle(Str(song, "name") ?? ""));
            if (name.Length == 0) continue;
            int score;
            if (name == wantTitle) score = 4;
            else if ((name.Contains(wantTitle) || wantTitle.Contains(name)) && Math.Min(name.Length, wantTitle.Length) >= 2) score = 2;
            else continue;

            if (wantArtist.Length > 0 && song.TryGetProperty("artists", out var artists) && artists.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in artists.EnumerateArray())
                {
                    var an = Norm(Str(a, "name") ?? "");
                    if (an.Length > 0 && (an == wantArtist || an.Contains(wantArtist) || wantArtist.Contains(an)))
                    {
                        score += 3;
                        break;
                    }
                }
            }

            var songDuration = song.TryGetProperty("duration", out var dur) && dur.ValueKind == JsonValueKind.Number ? dur.GetInt64() : 0;
            if (durationMs > 0 && songDuration > 0)
            {
                var diff = Math.Abs(songDuration - durationMs);
                if (diff <= 2500) score += 3;
                else if (diff > 8000) score -= 3;
            }

            if (score > bestScore)
            {
                bestScore = score;
                bestId = song.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.Number ? id.GetInt64() : -1;
            }
        }
        // Title alone is not enough: the artist or the length must agree too.
        if (bestId <= 0 || bestScore < 6) return null;

        using var lyric = await NeteaseLyricAsync(bestId, ct);
        if (lyric == null) return null;
        var root = lyric.RootElement;
        var yrc = Nested(root, "yrc", "lyric");
        if (yrc != null && !YrcLine.IsMatch(yrc)) yrc = null;
        var lrc = Nested(root, "lrc", "lyric");
        if (lrc != null && !Timestamp.IsMatch(lrc)) lrc = null;
        var translation = Nested(root, "tlyric", "lyric");
        if (translation != null && !Timestamp.IsMatch(translation)) translation = null;
        if (yrc == null && lrc == null) return null;
        return new LyricResult("netease", yrc != null ? "網易雲（逐字）" : "網易雲", lrc, yrc, translation);
    }

    private static async Task<JsonDocument?> NeteaseLyricAsync(long id, CancellationToken ct)
    {
        var v1 = await GetAsync(
            $"https://music.163.com/api/song/lyric/v1?id={id}&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0", NeteaseHeaders, ct);
        var doc = v1 == null ? null : Parse(v1.Value.Body);
        if (doc != null && (doc.RootElement.TryGetProperty("lrc", out _) || doc.RootElement.TryGetProperty("yrc", out _))) return doc;
        doc?.Dispose();

        var legacy = await GetAsync($"https://music.163.com/api/song/lyric?id={id}&lv=-1&kv=-1&tv=-1", NeteaseHeaders, ct);
        doc = legacy == null ? null : Parse(legacy.Value.Body);
        if (doc != null && doc.RootElement.TryGetProperty("lrc", out _)) return doc;
        doc?.Dispose();
        return null;
    }

    // ---------- helpers ----------

    private static IEnumerable<string> TitleVariants(string title)
    {
        var trimmed = title.Trim();
        yield return trimmed;
        var cleaned = CleanTitle(title);
        if (cleaned.Length > 0 && cleaned != trimmed) yield return cleaned;
    }

    public static string CleanTitle(string title)
    {
        var noBrackets = Brackets.Replace(title, "");
        var cleaned = DashSuffix.Replace(noBrackets, "").Trim();
        return cleaned.Length == 0 ? title.Trim() : cleaned;
    }

    public static string PrimaryArtist(string artist)
    {
        foreach (var part in ArtistSplit.Split(artist))
        {
            if (!string.IsNullOrWhiteSpace(part)) return part.Trim();
        }
        return artist.Trim();
    }

    private static string Norm(string text)
    {
        var simplified = ChineseConverter.ToSimplified(text).ToLowerInvariant();
        return new string(simplified.Where(char.IsLetterOrDigit).ToArray());
    }

    private static string Enc(string text) => Uri.EscapeDataString(text);

    private static string? Str(JsonElement el, string name) =>
        el.ValueKind == JsonValueKind.Object && el.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String &&
        !string.IsNullOrWhiteSpace(v.GetString())
            ? v.GetString()
            : null;

    private static string? Nested(JsonElement el, string outer, string inner) =>
        el.ValueKind == JsonValueKind.Object && el.TryGetProperty(outer, out var o) ? Str(o, inner) : null;

    private static JsonDocument? Parse(string body)
    {
        try
        {
            return JsonDocument.Parse(body);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static async Task<(int Status, string Body)?> GetAsync(string url, (string, string)[] headers, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            foreach (var (name, value) in headers) request.Headers.TryAddWithoutValidation(name, value);
            using var response = await Http.SendAsync(request, ct);
            var body = await response.Content.ReadAsStringAsync(ct);
            return ((int)response.StatusCode, body);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return null;
        }
    }
}

/// <summary>Simplified ⇄ Traditional Chinese through Windows' own character mapping (LCMapStringEx).</summary>
internal static class ChineseConverter
{
    private const uint LcmapSimplifiedChinese = 0x02000000;
    private const uint LcmapTraditionalChinese = 0x04000000;
    private static readonly Regex Kana = new(@"[぀-ヿ]", RegexOptions.Compiled);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int LCMapStringEx(
        string lpLocaleName, uint dwMapFlags, string lpSrcStr, int cchSrc,
        [Out] char[]? lpDestStr, int cchDest, IntPtr lpVersionInformation, IntPtr lpReserved, IntPtr sortHandle);

    public static string ToSimplified(string text) => Map(text, "zh-CN", LcmapSimplifiedChinese);

    /// <summary>Simplified → Traditional; Japanese text (it has kana) is left alone.</summary>
    public static string ToTraditional(string text) => Kana.IsMatch(text) ? text : Map(text, "zh-TW", LcmapTraditionalChinese);

    private static string Map(string text, string locale, uint flag)
    {
        if (string.IsNullOrEmpty(text) || !OperatingSystem.IsWindows()) return text;
        try
        {
            var length = LCMapStringEx(locale, flag, text, text.Length, null, 0, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            if (length <= 0) return text;
            var buffer = new char[length];
            length = LCMapStringEx(locale, flag, text, text.Length, buffer, length, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            return length > 0 ? new string(buffer, 0, length) : text;
        }
        catch (Exception)
        {
            return text;
        }
    }
}
