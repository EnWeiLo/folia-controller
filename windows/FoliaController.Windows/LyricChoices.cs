using System.Text.Json.Nodes;

namespace FoliaController;

/// <summary>
/// Lyrics the user picked by hand, remembered per song (title + artist), so the same song gets the
/// same lyrics next time. Kept in %LOCALAPPDATA%\FoliaController\lyric-choices.json.
/// </summary>
internal static class LyricChoices
{
    private static readonly object Gate = new();
    private static JsonObject? _store;
    public static bool Persist { get; set; } = true;

    private static string FilePath => Path.Combine(AppPaths.Data, "lyric-choices.json");

    public static string Key(SongQuery query)
    {
        static string N(string s) => new(s.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
        return N(LyricRepository.CleanTitle(query.Title)) + "|" + N(LyricRepository.PrimaryArtist(query.Artist));
    }

    public static (bool NoLyrics, LyricCandidate? Candidate) Get(SongQuery query)
    {
        lock (Gate)
        {
            var node = Store()[Key(query)];
            if (node is JsonObject o && o["source"] is JsonValue v && v.TryGetValue<string>(out var source) && source == "none") return (true, null);
            return (false, LyricCandidate.FromJson(node));
        }
    }

    public static bool Has(SongQuery query)
    {
        lock (Gate) return Store().ContainsKey(Key(query));
    }

    public static void Set(SongQuery query, LyricCandidate candidate)
    {
        lock (Gate)
        {
            Store()[Key(query)] = candidate.ToJson(includeLyrics: false);
            Save();
        }
    }

    public static void SetNone(SongQuery query)
    {
        lock (Gate)
        {
            Store()[Key(query)] = new JsonObject { ["source"] = "none" };
            Save();
        }
    }

    public static void Remove(SongQuery query)
    {
        lock (Gate)
        {
            if (Store().Remove(Key(query))) Save();
        }
    }

    private static JsonObject Store()
    {
        if (_store != null) return _store;
        try
        {
            if (Persist && File.Exists(FilePath) && JsonNode.Parse(File.ReadAllText(FilePath)) is JsonObject loaded)
            {
                return _store = loaded;
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
        return _store = new JsonObject();
    }

    private static void Save()
    {
        if (!Persist || _store == null) return;
        try
        {
            AppPaths.Ensure();
            var temp = FilePath + ".tmp";
            File.WriteAllText(temp, _store.ToJsonString());
            File.Move(temp, FilePath, overwrite: true);
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
    }
}
