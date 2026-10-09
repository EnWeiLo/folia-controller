using System.Net;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Text.Encodings.Web;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace FoliaController;

/// <summary>What the player says is playing.</summary>
internal sealed record SongQuery(string Title, string Artist, string Album, long DurationMs);

/// <summary>A song found on one lyric source. Extra holds what that source needs to fetch it again.</summary>
internal sealed record LyricCandidate(
    string Source,
    string Id,
    string Title,
    string Artist,
    string Album,
    long DurationMs,
    IReadOnlyDictionary<string, string> Extra,
    int Score,
    bool Accepted)
{
    public JsonObject ToJson(bool includeLyrics = false)
    {
        var extra = new JsonObject();
        foreach (var (k, v) in Extra)
        {
            if (k == "synced" && !includeLyrics) continue; // LRCLIB ships the lyrics in the search result
            extra[k] = v;
        }
        return new JsonObject
        {
            ["source"] = Source, ["id"] = Id, ["title"] = Title, ["artist"] = Artist, ["album"] = Album,
            ["durationMs"] = DurationMs, ["score"] = Score, ["accepted"] = Accepted, ["extra"] = extra,
            ["sourceName"] = LyricRepository.SourceName(Source),
        };
    }

    public static LyricCandidate? FromJson(JsonNode? node)
    {
        if (node is not JsonObject o) return null;
        var extra = new Dictionary<string, string>();
        if (o["extra"] is JsonObject e)
        {
            foreach (var (k, v) in e) if (v is JsonValue value && value.TryGetValue<string>(out var s)) extra[k] = s;
        }
        string Str(string key) => o[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";
        long Num(string key) => o[key] is JsonValue v && v.TryGetValue<long>(out var n) ? n : 0;
        var source = Str("source");
        var id = Str("id");
        if (source.Length == 0 || id.Length == 0) return null;
        return new LyricCandidate(source, id, Str("title"), Str("artist"), Str("album"), Num("durationMs"), extra, (int)Num("score"),
            o["accepted"] is JsonValue a && a.TryGetValue<bool>(out var acc) && acc);
    }
}

/// <summary>Lyrics ready for Folia, plus where they came from.</summary>
internal sealed record LyricResult(LyricDoc Doc, LyricCandidate Candidate)
{
    public string SourceLabel => LyricRepository.SourceName(Candidate.Source) + (Doc.WordByWord ? "（逐字）" : "");
}

/// <summary>
/// Finds synced lyrics for whatever another app is playing — same rules as the Android app
/// (LyricRepository.kt): NetEase, QQ Music, Kugou and LRCLIB searched together and scored on
/// title / artist / length (a romanised title still matches on artist + exact length); the AMLL
/// database tried first for the best NetEase / QQ hit; word-by-word preferred over line-by-line.
/// </summary>
internal static class LyricRepository
{
    private const string AppUserAgent = "FoliaController/1.0 (Windows; personal lyrics display)";
    private const string BrowserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";
    private const string KugouSecret = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";

    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AutomaticDecompression = DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
    })
    {
        Timeout = TimeSpan.FromSeconds(12),
    };

    private static readonly (string, string)[] NeteaseHeaders =
    {
        ("Referer", "https://music.163.com/"), ("User-Agent", BrowserUserAgent), ("Cookie", "os=pc; appver=2.10.13"),
    };
    private static readonly (string, string)[] QqHeaders = { ("Cookie", "tmeLoginType=-1;"), ("User-Agent", "okhttp/3.14.9") };
    private static readonly (string, string)[] AppHeaders = { ("User-Agent", AppUserAgent) };

    private static readonly Regex Brackets = new(@"\s*[(\[（【「][^)\]）】」]*[)\]）】」]", RegexOptions.Compiled);
    private static readonly Regex DashSuffix = new(@"\s+[-–—]\s+.*$", RegexOptions.Compiled);
    // Players join several artists with ", " or "、"; "&", "/" and "x" are often part of one name.
    private static readonly Regex ArtistSplit = new(@"\s*(?:,|，|、|;|\s+feat\.?\s+|\s+ft\.?\s+)\s*", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex Kana = new(@"[぀-ヿ]", RegexOptions.Compiled);
    private static readonly Regex Cjk = new(@"[぀-ヿ㐀-鿿가-힯]", RegexOptions.Compiled);
    private static readonly Regex PureMusic = new("纯音乐|純音樂|此歌曲为没有填词的纯音乐", RegexOptions.Compiled);
    // Other versions of a song; matched on lower-cased titles (Latin words must stand alone).
    private static readonly Regex VersionLatin = new("(?<![a-z])(live|remix|mix|acoustic|instrumental|inst|karaoke|off vocal|cover|demo|ver|version|sped up|slowed)(?![a-z])", RegexOptions.Compiled);
    private static readonly Regex VersionCjk = new("伴奏|纯音乐|純音樂|现场|現場|翻唱|カラオケ|ライブ", RegexOptions.Compiled);
    // QQ's server wants the request JSON with the text itself, not \u escapes.
    private static readonly System.Text.Json.JsonSerializerOptions RawJson = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    private static readonly object CacheGate = new();
    private static readonly Dictionary<string, (LyricResult? Result, List<LyricCandidate> Candidates)> Cache = new();
    private static readonly Queue<string> CacheOrder = new();

    public static string SourceName(string source) => source switch
    {
        "netease" => "網易雲",
        "qq" => "QQ 音樂",
        "kugou" => "酷狗",
        "lrclib" => "LRCLIB",
        "amll-ncm" or "amll-qq" => "AMLL",
        "local" => "本機檔案",
        _ => source,
    };

    // ---------- automatic search ----------

    /// <summary>Best lyrics and every candidate seen (for the manual picker).</summary>
    public static async Task<(LyricResult? Result, List<LyricCandidate> Candidates)> FindAsync(SongQuery query, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(query.Title)) return (null, new List<LyricCandidate>());
        var key = $"{query.Title}|{query.Artist}|{query.DurationMs / 1000}";
        lock (CacheGate)
        {
            if (Cache.TryGetValue(key, out var cached)) return cached;
        }

        var candidates = await SearchAllAsync(query, ct);
        var result = await PickBestAsync(candidates.Where(c => c.Accepted).ToList(), ct);
        var outcome = (result, candidates);
        if (result != null || candidates.Count > 0)
        {
            lock (CacheGate)
            {
                if (Cache.TryAdd(key, outcome))
                {
                    CacheOrder.Enqueue(key);
                    while (CacheOrder.Count > 60) Cache.Remove(CacheOrder.Dequeue());
                }
            }
        }
        return outcome;
    }

    /// <summary>Drops the cached search for this song (after the user resets a hand-picked lyric).</summary>
    public static void Forget(SongQuery query)
    {
        lock (CacheGate) Cache.Remove($"{query.Title}|{query.Artist}|{query.DurationMs / 1000}");
    }

    /// <summary>Every source, in parallel; scored and sorted best first.</summary>
    public static async Task<List<LyricCandidate>> SearchAllAsync(SongQuery query, CancellationToken ct)
    {
        var tasks = new[]
        {
            Safe(() => SearchNeteaseAsync(query, ct)),
            Safe(() => SearchQqAsync(query, ct)),
            Safe(() => SearchKugouAsync(query, ct)),
            Safe(() => SearchLrclibAsync(query, ct)),
        };
        var all = (await Task.WhenAll(tasks)).SelectMany(x => x).ToList();
        var order = SourceOrder(query);
        // Matching files in the local lyrics folder always come first.
        return LocalLyrics.Candidates(query).Concat(all.OrderByDescending(c => c.Accepted).ThenByDescending(c => c.Score).ThenBy(c => order.IndexOf(c.Source))).ToList();

        static async Task<List<LyricCandidate>> Safe(Func<Task<List<LyricCandidate>>> search)
        {
            try { return await search(); } catch (OperationCanceledException) { throw; } catch (Exception) { return new List<LyricCandidate>(); }
        }
    }

    private static List<string> SourceOrder(SongQuery q) =>
        Cjk.IsMatch(q.Title + q.Artist) ? new() { "netease", "qq", "kugou", "lrclib" } : new() { "lrclib", "netease", "qq", "kugou" };

    private static async Task<LyricResult?> PickBestAsync(List<LyricCandidate> accepted, CancellationToken ct)
    {
        if (accepted.Count == 0) return null;
        // 1. AMLL: hand-made word-by-word lyrics, keyed by NetEase / QQ song id.
        var probes = new List<(LyricCandidate Candidate, Task<LyricDoc?> Task)>();
        var ne = accepted.FirstOrDefault(c => c.Source == "netease");
        if (ne != null) { var c = ne with { Source = "amll-ncm" }; probes.Add((c, FetchSafeAsync(c, ct))); }
        var qq = accepted.FirstOrDefault(c => c.Source == "qq");
        if (qq != null) { var c = qq with { Source = "amll-qq" }; probes.Add((c, FetchSafeAsync(c, ct))); }
        foreach (var (candidate, task) in probes)
        {
            var doc = await task;
            if (doc != null && !doc.IsEmpty) return new LyricResult(doc, candidate);
        }
        // 2. The scored candidates in order; prefer word-by-word over line-by-line.
        LyricResult? fallback = null;
        var tried = 0;
        foreach (var candidate in accepted)
        {
            if (tried >= 6) break;
            if (fallback != null && candidate.Source == "lrclib") continue; // line-by-line only
            tried++;
            var doc = await FetchSafeAsync(candidate, ct);
            if (doc == null || doc.IsEmpty) continue;
            var result = new LyricResult(doc, candidate);
            if (doc.WordByWord) return result;
            fallback ??= result;
        }
        return fallback;
    }

    private static async Task<LyricDoc?> FetchSafeAsync(LyricCandidate c, CancellationToken ct)
    {
        try { return await FetchAsync(c, ct); } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return null; } catch (Exception ex) when (ex is not OperationCanceledException) { return null; }
    }

    /// <summary>Lyrics for one candidate (also used when the user picks one by hand).</summary>
    public static async Task<LyricDoc?> FetchAsync(LyricCandidate c, CancellationToken ct)
    {
        var doc = c.Source switch
        {
            "netease" => await FetchNeteaseAsync(c.Id, ct),
            "qq" => await FetchQqAsync(c, ct),
            "kugou" => await FetchKugouAsync(c, ct),
            "lrclib" => c.Extra.TryGetValue("synced", out var synced) ? LyricFormats.ParseLrc(synced) : await FetchLrclibAsync(c.Id, ct),
            "amll-ncm" => await FetchAmllAsync("ncm", c.Id, ct),
            "amll-qq" => await FetchAmllAsync("qq", c.Id, ct),
            "local" => LocalLyrics.Load(c.Id),
            _ => null,
        };
        if (doc == null || doc.IsEmpty || doc.Lines.All(l => PureMusic.IsMatch(l.Text))) return null;
        return doc;
    }

    // ---------- scoring ----------

    /// <summary>Title, artist and length agreement → score; null when it is clearly another song.</summary>
    private static (int Score, bool Accepted)? Score(SongQuery query, string title, IReadOnlyList<string> artists, long durationMs)
    {
        var wantTitle = Norm(CleanTitle(query.Title));
        var gotTitle = Norm(CleanTitle(title));
        var wantArtist = Norm(PrimaryArtist(query.Artist));
        var titleScore =
            wantTitle.Length == 0 || gotTitle.Length == 0 ? 0 :
            gotTitle == wantTitle ? 4 :
            (gotTitle.Contains(wantTitle) || wantTitle.Contains(gotTitle)) && Math.Min(gotTitle.Length, wantTitle.Length) >= 2 ? 2 : 0;
        var artistMatch = wantArtist.Length > 0 && artists.Any(a =>
        {
            var n = Norm(a);
            return n.Length > 0 && (n == wantArtist || n.Contains(wantArtist) || wantArtist.Contains(n));
        });
        long? diff = query.DurationMs > 0 && durationMs > 0 ? Math.Abs(durationMs - query.DurationMs) : null;
        if (diff > 15_000) return null;
        var score = titleScore + (artistMatch ? 3 : 0);
        // Same title before cleaning: the studio track beats "Title (Live …)".
        if (titleScore == 4 && Norm(title) == Norm(query.Title)) score += 1;
        // A live / remix / karaoke … version the player is not playing.
        if (VersionTags(title).Except(VersionTags(query.Title)).Any()) score -= 2;
        if (diff != null)
        {
            score += diff <= 2500 ? 3 : diff <= 5000 ? 1 : diff > 8000 ? -3 : 0;
        }
        // A romanised or translated title cannot match by text: same artist + same length will do.
        var accepted = titleScore == 0 ? artistMatch && diff != null && diff <= 1500 : score >= 6;
        return (score, accepted);
    }

    private static LyricCandidate? Candidate(SongQuery query, string source, string id, string title, IReadOnlyList<string> artists,
        string album, long durationMs, Dictionary<string, string>? extra = null)
    {
        var scored = Score(query, title, artists, durationMs);
        if (scored == null) return null;
        return new LyricCandidate(source, id, title, string.Join(" / ", artists), album, durationMs,
            extra ?? new Dictionary<string, string>(), scored.Value.Score, scored.Value.Accepted);
    }

    private static HashSet<string> VersionTags(string title)
    {
        var lower = title.ToLowerInvariant();
        return VersionLatin.Matches(lower).Select(m => m.Value).Concat(VersionCjk.Matches(lower).Select(m => m.Value)).ToHashSet();
    }

    /// <summary>
    /// Queries to try, in order: the text as the player shows it; the Simplified spelling (Chinese
    /// catalogs) when that differs — never first, since Japanese names in kanji only (米津玄師) must
    /// stay as they are; then, for Latin titles, the artist alone (romanised titles).
    /// </summary>
    private static List<string> SearchTerms(SongQuery query, Func<string, string, string>? format = null)
    {
        format ??= (t, a) => $"{t} {a}".Trim();
        var title = CleanTitle(query.Title);
        var artist = PrimaryArtist(query.Artist);
        var terms = new List<string>();
        void Add(string term)
        {
            if (!terms.Contains(term)) terms.Add(term);
        }
        var original = format(title, artist);
        Add(original);
        if (!Kana.IsMatch(original)) Add(ChineseConverter.ToSimplified(original));
        if (artist.Length > 0 && !Cjk.IsMatch(title)) Add(artist);
        return terms;
    }

    private static async Task<List<LyricCandidate>> SearchUntilAcceptedAsync(SongQuery query, Func<string, Task<List<LyricCandidate>>> search,
        List<string>? terms = null)
    {
        var all = new List<LyricCandidate>();
        foreach (var term in terms ?? SearchTerms(query))
        {
            List<LyricCandidate> found;
            try { found = await search(term); } catch (OperationCanceledException) { throw; } catch (Exception) { found = new(); }
            all.AddRange(found.Where(c => all.All(a => a.Id != c.Id)));
            if (found.Any(c => c.Accepted)) break;
        }
        return all;
    }

    // ---------- NetEase ----------

    private static Task<List<LyricCandidate>> SearchNeteaseAsync(SongQuery query, CancellationToken ct) =>
        SearchUntilAcceptedAsync(query, async term =>
        {
            var body = await GetAsync($"https://music.163.com/api/search/get/web?s={Enc(term)}&type=1&offset=0&limit=20", NeteaseHeaders, ct);
            var songs = Get(Get(Parse(body), "result"), "songs") as JsonArray;
            var list = new List<LyricCandidate>();
            if (songs == null) return list;
            foreach (var s in songs)
            {
                if (s is not JsonObject song) continue;
                var c = Candidate(query, "netease", Num(song["id"]).ToString(), Str(song["name"]), Names(song["artists"]),
                    Str(Get(song["album"], "name")), Num(song["duration"]));
                if (c != null) list.Add(c);
            }
            return list;
        });

    private static async Task<LyricDoc?> FetchNeteaseAsync(string id, CancellationToken ct)
    {
        var body = await GetAsync($"https://music.163.com/api/song/lyric/v1?id={id}&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0", NeteaseHeaders, ct)
                   ?? await GetAsync($"https://music.163.com/api/song/lyric?id={id}&lv=-1&kv=-1&tv=-1&rv=-1", NeteaseHeaders, ct);
        if (Parse(body) is not JsonObject o) return null;
        string? Track(string name) => Get(o[name], "lyric") is JsonValue v && v.TryGetValue<string>(out var s) && !string.IsNullOrWhiteSpace(s) ? s : null;
        var yrc = Track("yrc");
        var lrc = Track("lrc");
        if (yrc != null)
        {
            return LyricFormats.ParseYrc(yrc) with
            {
                Translation = LyricFormats.ParseTrack(Track("ytlrc") ?? Track("tlyric")),
                Romaji = LyricFormats.ParseTrack(Track("yromalrc") ?? Track("romalrc")),
            };
        }
        if (lrc != null)
        {
            return LyricFormats.ParseLrc(lrc) with
            {
                Translation = LyricFormats.ParseTrack(Track("tlyric")),
                Romaji = LyricFormats.ParseTrack(Track("romalrc")),
            };
        }
        return null;
    }

    // ---------- QQ Music ----------

    private static async Task<JsonObject?> QqRequestAsync(string method, string module, JsonObject param, CancellationToken ct)
    {
        var payload = new JsonObject
        {
            ["comm"] = new JsonObject
            {
                ["ct"] = 11, ["cv"] = "1003006", ["v"] = "1003006", ["os_ver"] = "15", ["phonetype"] = "24122RKC7C",
                ["rom"] = "Redmi/miro/miro:15/AE3A.240806.005/OS2.0.102.0.VOMCNXM:user/release-keys",
                ["tmeAppID"] = "qqmusiclight", ["nettype"] = "NETWORK_WIFI", ["udid"] = "0", ["uid"] = "0",
            },
            ["request"] = new JsonObject { ["method"] = method, ["module"] = module, ["param"] = param },
        };
        var body = await PostJsonAsync("https://u.y.qq.com/cgi-bin/musicu.fcg", payload.ToJsonString(RawJson), QqHeaders, ct);
        if (Parse(body) is not JsonObject o || Num(o["code"], -1) != 0) return null;
        var request = o["request"] as JsonObject;
        return request != null && Num(request["code"], -1) == 0 ? request["data"] as JsonObject : null;
    }

    private static Task<List<LyricCandidate>> SearchQqAsync(SongQuery query, CancellationToken ct) =>
        SearchUntilAcceptedAsync(query, async term =>
        {
            var param = new JsonObject
            {
                ["search_id"] = ((long)(Random.Shared.NextDouble() * 1e14) + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() % 86_400_000).ToString(),
                ["remoteplace"] = "search.android.keyboard", ["query"] = term.Length > 60 ? term[..60] : term, ["search_type"] = 0,
                ["num_per_page"] = 20, ["page_num"] = 1, ["highlight"] = 0, ["nqc_flag"] = 0, ["page_id"] = 1, ["grp"] = 1,
            };
            var data = await QqRequestAsync("DoSearchForQQMusicLite", "music.search.SearchCgiService", param, ct);
            var list = new List<LyricCandidate>();
            if (Get(Get(data, "body"), "item_song") is not JsonArray songs) return list;
            foreach (var s in songs)
            {
                if (s is not JsonObject song) continue;
                var id = Num(song["id"]);
                if (id <= 0) continue;
                var artists = Names(song["singer"]);
                var c = Candidate(query, "qq", id.ToString(), Str(song["title"]), artists, Str(Get(song["album"], "name")), Num(song["interval"]) * 1000,
                    new Dictionary<string, string> { ["mid"] = Str(song["mid"]), ["singer"] = string.Join(", ", artists) });
                if (c != null) list.Add(c);
            }
            return list;
        });

    private static async Task<LyricDoc?> FetchQqAsync(LyricCandidate c, CancellationToken ct)
    {
        static string B64(string s) => Convert.ToBase64String(Encoding.UTF8.GetBytes(s));
        var param = new JsonObject
        {
            ["albumName"] = B64(c.Album), ["crypt"] = 1, ["ct"] = 19, ["cv"] = 2111, ["interval"] = c.DurationMs / 1000,
            ["lrc_t"] = 0, ["qrc"] = 1, ["qrc_t"] = 0, ["roma"] = 1, ["roma_t"] = 0,
            ["singerName"] = B64(c.Extra.TryGetValue("singer", out var singer) ? singer : c.Artist),
            ["songID"] = long.Parse(c.Id), ["songName"] = B64(c.Title), ["trans"] = 1, ["trans_t"] = 0, ["type"] = 0,
        };
        var data = await QqRequestAsync("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", param, ct);
        if (data == null) return null;
        string? Decrypt(string field)
        {
            var hex = Str(data[field]);
            if (hex.Length < 16) return null;
            try
            {
                var text = LyricFormats.DecryptQrc(hex);
                return string.IsNullOrWhiteSpace(text) ? null : text;
            }
            catch (Exception) { return null; }
        }
        var lyric = Decrypt("lyric");
        if (lyric == null) return null;
        var doc = LyricFormats.IsQrc(lyric) ? LyricFormats.ParseQrc(lyric) : LyricFormats.ParseLrc(lyric);
        var roma = Decrypt("roma");
        IReadOnlyList<TimedText> romaji = roma == null ? Array.Empty<TimedText>()
            : LyricFormats.IsQrc(roma) ? LyricFormats.ParseQrc(roma).Lines.Select(l => new TimedText(l.StartMs, l.Text)).ToList()
            : LyricFormats.ParseTrack(roma);
        return doc with { Translation = LyricFormats.ParseTrack(Decrypt("trans")), Romaji = romaji };
    }

    // ---------- Kugou ----------

    private static async Task<JsonObject?> KugouSignedAsync(string url, Dictionary<string, string> parameters, CancellationToken ct)
    {
        var all = new SortedDictionary<string, string>(StringComparer.Ordinal) { ["appid"] = "3116", ["clientver"] = "11070" };
        foreach (var (k, v) in parameters) all[k] = v;
        var signText = KugouSecret + string.Concat(all.Select(kv => $"{kv.Key}={kv.Value}")) + KugouSecret;
        var signature = Convert.ToHexString(MD5.HashData(Encoding.UTF8.GetBytes(signText))).ToLowerInvariant();
        var query = string.Join("&", all.Select(kv => $"{kv.Key}={Enc(kv.Value)}")) + "&signature=" + signature;
        var body = await GetAsync($"{url}?{query}", new[]
        {
            ("User-Agent", "Android14-1070-11070-201-0-Lyric-wifi"), ("KG-Rec", "1"), ("KG-RC", "1"),
            ("KG-CLIENTTIMEMS", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString()),
        }, ct);
        if (Parse(body) is not JsonObject o) return null;
        var error = Num(o["error_code"], 0);
        return error is 0 or 200 ? o : null;
    }

    private static Task<List<LyricCandidate>> SearchKugouAsync(SongQuery query, CancellationToken ct)
    {
        var title = CleanTitle(query.Title);
        var artist = PrimaryArtist(query.Artist);
        var simplifiedTitle = ChineseConverter.ToSimplified(title);
        var terms = SearchTerms(query, (t, a) => a.Length > 0 ? $"{a} - {t}" : t).Where(k => k.Contains(title) || k.Contains(simplifiedTitle)).ToList();
        return SearchUntilAcceptedAsync(query, keyword => SearchKugouOnceAsync(query, keyword, title, artist, ct), terms);
    }

    private static async Task<List<LyricCandidate>> SearchKugouOnceAsync(SongQuery query, string keyword, string title, string artist, CancellationToken ct)
    {
        var o = await KugouSignedAsync("https://lyrics.kugou.com/v1/search", new Dictionary<string, string>
        {
            ["album_audio_id"] = "", ["duration"] = query.DurationMs.ToString(), ["hash"] = "",
            ["keyword"] = keyword, ["lrctxt"] = "1", ["man"] = "no",
        }, ct);
        var list = new List<LyricCandidate>();
        if (Get(o, "candidates") is not JsonArray items) return list;
        foreach (var item in items)
        {
            if (item is not JsonObject c) continue;
            var id = Str(c["id"]);
            if (id.Length == 0 && c["id"] is JsonValue idv && idv.TryGetValue<long>(out var idn)) id = idn.ToString();
            if (id.Length == 0) continue;
            var song = Str(c["song"]);
            var singer = Str(c["singer"]);
            var candidate = Candidate(query, "kugou", id, song.Length > 0 ? song : title, new[] { singer.Length > 0 ? singer : artist }, "",
                Num(c["duration"]), new Dictionary<string, string> { ["accesskey"] = Str(c["accesskey"]) });
            if (candidate != null) list.Add(candidate);
        }
        return list;
    }

    private static async Task<LyricDoc?> FetchKugouAsync(LyricCandidate c, CancellationToken ct)
    {
        if (!c.Extra.TryGetValue("accesskey", out var accessKey)) return null;
        var o = await KugouSignedAsync("https://lyrics.kugou.com/download", new Dictionary<string, string>
        {
            ["accesskey"] = accessKey, ["charset"] = "utf8", ["client"] = "mobi", ["fmt"] = "krc", ["id"] = c.Id, ["ver"] = "1",
        }, ct);
        var content = Str(Get(o, "content"));
        if (content.Length == 0) return null;
        if (Num(o!["contenttype"]) == 2) return LyricFormats.ParseLrc(Encoding.UTF8.GetString(Convert.FromBase64String(content)));
        string krc;
        try { krc = LyricFormats.DecryptKrc(content); } catch (Exception) { return null; }
        var doc = LyricFormats.ParseKrc(krc);
        var tag = LyricFormats.KrcLanguageTag(krc);
        if (tag == null) return doc;
        JsonNode? language;
        try { language = JsonNode.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(tag))); } catch (Exception) { return doc; }
        if (Get(language, "content") is not JsonArray contents) return doc;
        var starts = LyricFormats.KrcLineStarts(krc);
        var sung = starts.Where(s => s.HasText).ToList();
        var translation = new List<TimedText>();
        var romaji = new List<TimedText>();
        foreach (var node in contents)
        {
            if (Get(node, "lyricContent") is not JsonArray rows) continue;
            var type = Num(Get(node, "type"), -1);
            if (type == 1)
            {
                // One entry per KRC line, blank lines included.
                for (var r = 0; r < Math.Min(rows.Count, starts.Count); r++)
                {
                    var text = rows[r] is JsonArray row && row.Count > 0 ? Str(row[0]) : "";
                    if (!string.IsNullOrWhiteSpace(text)) translation.Add(new TimedText(starts[r].Start, text));
                }
            }
            else if (type == 0)
            {
                // One entry per non-blank KRC line, one string per word.
                for (var r = 0; r < Math.Min(rows.Count, sung.Count); r++)
                {
                    if (rows[r] is not JsonArray words) continue;
                    var text = string.Concat(words.Select(w => Str(w))).Trim();
                    if (text.Length > 0) romaji.Add(new TimedText(sung[r].Start, text));
                }
            }
        }
        return doc with { Translation = translation, Romaji = romaji };
    }

    // ---------- LRCLIB ----------

    private static async Task<List<LyricCandidate>> SearchLrclibAsync(SongQuery query, CancellationToken ct)
    {
        var title = CleanTitle(query.Title);
        var artist = PrimaryArtist(query.Artist);
        var list = new List<LyricCandidate>();
        async Task Collect(string parameters)
        {
            if (Parse(await GetAsync($"https://lrclib.net/api/search?{parameters}", AppHeaders, ct)) is not JsonArray items) return;
            foreach (var node in items)
            {
                if (node is not JsonObject item) continue;
                var synced = Str(item["syncedLyrics"]);
                if (string.IsNullOrWhiteSpace(synced)) continue;
                var id = Num(item["id"]).ToString();
                if (list.Any(c => c.Id == id)) continue;
                var seconds = item["duration"] is JsonValue d && d.TryGetValue<double>(out var dv) ? dv : 0;
                var c = Candidate(query, "lrclib", id, Str(item["trackName"]), new[] { Str(item["artistName"]) }, Str(item["albumName"]),
                    (long)(seconds * 1000), new Dictionary<string, string> { ["synced"] = synced });
                if (c != null) list.Add(c);
            }
        }
        await Collect($"track_name={Enc(title)}" + (artist.Length > 0 ? $"&artist_name={Enc(artist)}" : ""));
        if (!list.Any(c => c.Accepted)) await Collect($"q={Enc($"{title} {artist}".Trim())}");
        return list;
    }

    private static async Task<LyricDoc?> FetchLrclibAsync(string id, CancellationToken ct)
    {
        var synced = Str(Get(Parse(await GetAsync($"https://lrclib.net/api/get/{id}", AppHeaders, ct)), "syncedLyrics"));
        return string.IsNullOrWhiteSpace(synced) ? null : LyricFormats.ParseLrc(synced);
    }

    // ---------- AMLL ----------

    private static async Task<LyricDoc?> FetchAmllAsync(string platform, string id, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(6));
        var body = await GetAsync($"https://amll-ttml-db.stevexmh.net/{platform}/{Enc(id)}?format=ttml", AppHeaders, timeout.Token);
        return body != null && body.Contains("<tt") ? LyricFormats.ParseTtml(body) : null;
    }

    // ---------- helpers ----------

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

    private static string Norm(string text) => Normalize(text);

    /// <summary>Letters and digits only, lower case, Simplified: for comparing names across sources.</summary>
    public static string Normalize(string text) =>
        new(ChineseConverter.ToSimplified(text).ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());

    private static string Enc(string text) => Uri.EscapeDataString(text);

    /// <summary>Child of a JSON object; null for anything else (the indexer would throw on arrays and values).</summary>
    private static JsonNode? Get(JsonNode? node, string key) => node is JsonObject o ? o[key] : null;

    private static string Str(JsonNode? node) => node is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";

    private static long Num(JsonNode? node, long fallback = 0)
    {
        if (node is not JsonValue v) return fallback;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d)) return (long)d;
        if (v.TryGetValue<string>(out var s) && long.TryParse(s, out var p)) return p;
        return fallback;
    }

    private static List<string> Names(JsonNode? array) =>
        array is JsonArray a ? a.Select(n => Str(Get(n, "name"))).Where(n => n.Length > 0).ToList() : new List<string>();

    private static JsonNode? Parse(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        try { return JsonNode.Parse(body); } catch (JsonException) { return null; }
    }

    private static async Task<string?> GetAsync(string url, (string, string)[] headers, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        return await SendAsync(request, headers, ct);
    }

    private static async Task<string?> PostJsonAsync(string url, string json, (string, string)[] headers, CancellationToken ct)
    {
        var content = new StringContent(json, Encoding.UTF8);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json"); // no "; charset=…", like the Android app
        using var request = new HttpRequestMessage(HttpMethod.Post, url) { Content = content };
        return await SendAsync(request, headers, ct);
    }

    private static async Task<string?> SendAsync(HttpRequestMessage request, (string, string)[] headers, CancellationToken ct)
    {
        try
        {
            foreach (var (name, value) in headers) request.Headers.TryAddWithoutValidation(name, value);
            using var response = await Http.SendAsync(request, ct);
            if (!response.IsSuccessStatusCode) return null;
            return await response.Content.ReadAsStringAsync(ct);
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

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int LCMapStringEx(
        string lpLocaleName, uint dwMapFlags, string lpSrcStr, int cchSrc,
        [Out] char[]? lpDestStr, int cchDest, IntPtr lpVersionInformation, IntPtr lpReserved, IntPtr sortHandle);

    public static string ToSimplified(string text) => Map(text, "zh-CN", LcmapSimplifiedChinese);

    public static string ToTraditional(string text) => Map(text, "zh-TW", LcmapTraditionalChinese);

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
