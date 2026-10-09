using System.Text;

namespace FoliaController;

/// <summary>
/// Lyric files in a folder the user picked. Files are matched by name: "Title.lrc",
/// "Artist - Title.lrc" or "Title - Artist.lrc". Twin of LocalLyrics.kt.
/// </summary>
internal static class LocalLyrics
{
    private static readonly HashSet<string> Extensions = new(StringComparer.OrdinalIgnoreCase) { ".lrc", ".ttml", ".yrc", ".qrc", ".krc", ".txt" };
    private static readonly object Gate = new();
    private static List<string> _index = new();
    private static string? _indexedFolder;
    private static DateTime _indexedAt;

    public static Func<string?> Folder { get; set; } = () => null;

    static LocalLyrics()
    {
        // Big5 / GB18030 for older Chinese .lrc files.
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public static void Invalidate()
    {
        lock (Gate) _indexedFolder = null;
    }

    /// <summary>Matching files, best first, as picker candidates.</summary>
    public static List<LyricCandidate> Candidates(SongQuery query)
    {
        var files = Files();
        var title = LyricRepository.Normalize(LyricRepository.CleanTitle(query.Title));
        var artist = LyricRepository.Normalize(LyricRepository.PrimaryArtist(query.Artist));
        var result = new List<LyricCandidate>();
        if (title.Length == 0) return result;
        foreach (var path in files)
        {
            var name = Path.GetFileName(path);
            var b = LyricRepository.Normalize(Path.GetFileNameWithoutExtension(path));
            int score;
            if (b == title) score = 5;
            else if (artist.Length > 0 && (b == artist + title || b == title + artist)) score = 6;
            else if (title.Length >= 2 && b.Contains(title) && artist.Length > 0 && b.Contains(artist)) score = 4;
            else if (title.Length >= 2 && b.Contains(title)) score = 2;
            else continue;
            result.Add(new LyricCandidate("local", path, name, "", "", 0, new Dictionary<string, string>(), score, score >= 4));
        }
        return result.OrderByDescending(c => c.Score).ToList();
    }

    /// <summary>The best matching file's lyrics, if any.</summary>
    public static LyricResult? Find(SongQuery query)
    {
        var best = Candidates(query).FirstOrDefault(c => c.Accepted);
        if (best == null) return null;
        var doc = Load(best.Id);
        return doc == null ? null : new LyricResult(doc, best);
    }

    /// <summary>Reads and parses one file (only files inside the chosen folder).</summary>
    public static LyricDoc? Load(string path)
    {
        var folder = Folder();
        if (folder == null) return null;
        try
        {
            var full = Path.GetFullPath(path);
            var root = Path.GetFullPath(folder).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (!full.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !File.Exists(full)) return null;
            var text = Decode(File.ReadAllBytes(full));
            var doc = Path.GetExtension(full).ToLowerInvariant() switch
            {
                ".ttml" => LyricFormats.ParseTtml(text),
                ".yrc" => LyricFormats.ParseYrc(text),
                ".qrc" => LyricFormats.ParseQrc(text),
                ".krc" => LyricFormats.ParseKrc(text),
                _ => LyricFormats.ParseLrc(text) with { RawLrc = text },
            };
            return doc.IsEmpty ? null : doc;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static List<string> Files()
    {
        var folder = Folder();
        if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder)) return new List<string>();
        lock (Gate)
        {
            if (_indexedFolder == folder && DateTime.UtcNow - _indexedAt < TimeSpan.FromMinutes(2)) return _index;
            var files = new List<string>();
            try
            {
                var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, MaxRecursionDepth = 4 };
                foreach (var file in Directory.EnumerateFiles(folder, "*", options))
                {
                    if (Extensions.Contains(Path.GetExtension(file))) files.Add(file);
                    if (files.Count >= 5000) break;
                }
            }
            catch (Exception)
            {
            }
            _index = files;
            _indexedFolder = folder;
            _indexedAt = DateTime.UtcNow;
            return files;
        }
    }

    /// <summary>UTF-8 (with or without BOM), UTF-16 with BOM, else Big5, else GB18030.</summary>
    public static string Decode(byte[] bytes)
    {
        if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF) return Encoding.UTF8.GetString(bytes, 3, bytes.Length - 3);
        if (bytes.Length >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE) return Encoding.Unicode.GetString(bytes, 2, bytes.Length - 2);
        if (bytes.Length >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF) return Encoding.BigEndianUnicode.GetString(bytes, 2, bytes.Length - 2);
        foreach (var codePage in new[] { 65001, 950, 54936 })
        {
            try
            {
                var encoding = Encoding.GetEncoding(codePage, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
                return encoding.GetString(bytes);
            }
            catch (Exception)
            {
                // Not this encoding.
            }
        }
        return Encoding.UTF8.GetString(bytes);
    }
}
