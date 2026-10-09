using System.Globalization;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;

namespace FoliaController;

// Line-for-line twin of the Android app's LyricFormats.kt: both must turn the fixtures in
// /tests/lyrics into the same bytes (checked by the CI smoke test). Keep them in step.

/// <summary>One timed word. Times are absolute milliseconds.</summary>
internal sealed record LyricWord(long StartMs, long DurationMs, string Text);

/// <summary>One lyric line. Words is empty for line-timed lyrics.</summary>
internal sealed record LyricLine(long StartMs, IReadOnlyList<LyricWord> Words, string Text);

internal sealed record TimedText(long StartMs, string Text);

/// <summary>Lyrics from any source, normalised: original lines plus optional translation / romaji tracks.</summary>
internal sealed record LyricDoc(IReadOnlyList<LyricLine> Lines, IReadOnlyList<TimedText> Translation, IReadOnlyList<TimedText> Romaji)
{
    public LyricDoc(IReadOnlyList<LyricLine> lines) : this(lines, Array.Empty<TimedText>(), Array.Empty<TimedText>()) { }

    /// <summary>A local .lrc file's own text: sent to Folia as is, so its own parser handles every LRC dialect.</summary>
    public string? RawLrc { get; init; }

    public bool WordByWord => Lines.Count > 0 && Lines.Count(l => l.Words.Count > 0) * 2 >= Lines.Count;
    public bool IsEmpty => Lines.All(l => string.IsNullOrWhiteSpace(l.Text));
}

internal static class LyricFormats
{
    private static readonly Regex LrcTime = new(@"^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]", RegexOptions.Compiled);
    private static readonly Regex LrcTimeAnywhere = new(@"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]", RegexOptions.Compiled);
    private static readonly Regex LrcTagLine = new(@"^\[[a-zA-Z#]+:.*\]$", RegexOptions.Compiled);
    private static readonly Regex YrcLine = new(@"^\[(\d+),(\d+)\](.*)$", RegexOptions.Compiled);
    // Word text runs up to the next timing marker, so lyrics that contain "(" or "<" survive.
    private static readonly Regex YrcWord = new(@"\((\d+),(\d+),-?\d+\)((?:(?!\(\d+,\d+,-?\d+\)).)*)", RegexOptions.Compiled);
    private static readonly Regex QrcWord = new(@"((?:(?!\(\d+,\d+\)).)*)\((\d+),(\d+)\)", RegexOptions.Compiled);
    private static readonly Regex KrcWord = new(@"<(\d+),(\d+),-?\d+>((?:(?!<\d+,\d+,-?\d+>).)*)", RegexOptions.Compiled);
    private static readonly Regex JsonText = new(@"""tx""\s*:\s*""((?:[^""\\]|\\.)*)""", RegexOptions.Compiled);
    private static readonly Regex JsonTime = new(@"""t""\s*:\s*(\d+)", RegexOptions.Compiled);
    private static readonly Regex QrcContent = new(@"LyricContent=""([\s\S]*?)""\s*/>", RegexOptions.Compiled);
    private static readonly Regex EnhancedWordTag = new(@"<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>", RegexOptions.Compiled);
    private static readonly Regex Kana = new(@"[぀-ヿ]", RegexOptions.Compiled);
    private static readonly Regex Tags = new(@"<[^>]*>", RegexOptions.Compiled);

    private static IEnumerable<string> SplitLines(string content) => content.Replace("\r", "").Split('\n');

    // ---------- parsers ----------

    /// <summary>Standard LRC (also NetEase's JSON credit lines). Several timestamps per line are allowed.</summary>
    public static LyricDoc ParseLrc(string content)
    {
        var lines = new List<LyricLine>();
        foreach (var raw in SplitLines(content))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith('{'))
            {
                var credit = CreditLine(line);
                if (credit != null) lines.Add(credit);
                continue;
            }
            if (LrcTagLine.IsMatch(line) && !LrcTimeAnywhere.IsMatch(line)) continue;
            var rest = line;
            var times = new List<long>();
            while (true)
            {
                var m = LrcTime.Match(rest);
                if (!m.Success) break;
                times.Add(LrcTimeMs(m));
                rest = rest[(m.Index + m.Length)..];
            }
            if (times.Count == 0) continue;
            var text = EnhancedWordTag.Replace(rest, "").Trim();
            // Enhanced LRC (<mm:ss.xx>word<mm:ss.xx>word…, e.g. from LDDC): word timing.
            IReadOnlyList<LyricWord> words = times.Count == 1 ? EnhancedWords(rest) : Array.Empty<LyricWord>();
            foreach (var t in times) lines.Add(new LyricLine(t, words, text));
        }
        return new LyricDoc(lines.OrderBy(l => l.StartMs).ToList());
    }

    /// <summary>Word timing from an enhanced-LRC line body; a word lasts until the next tag (500 ms for the last).</summary>
    private static List<LyricWord> EnhancedWords(string body)
    {
        var tags = EnhancedWordTag.Matches(body).ToList();
        if (tags.Count < 2) return new List<LyricWord>();
        var words = new List<LyricWord>();
        for (var i = 0; i < tags.Count; i++)
        {
            var tag = tags[i];
            var end = i + 1 < tags.Count ? tags[i + 1].Index : body.Length;
            var text = body[(tag.Index + tag.Length)..end];
            if (text.Length == 0) continue;
            var start = LrcTimeMs(tag);
            var next = i + 1 < tags.Count ? LrcTimeMs(tags[i + 1]) : start + 500;
            words.Add(new LyricWord(start, Math.Max(1, next - start), text));
        }
        return TrimLastWord(words).Where(w => w.Text.Length > 0).ToList();
    }

    /// <summary>LRC as a timed-text track (translation / romaji). Blank lines are dropped.</summary>
    public static IReadOnlyList<TimedText> ParseTrack(string? content)
    {
        if (string.IsNullOrWhiteSpace(content)) return Array.Empty<TimedText>();
        return ParseLrc(content).Lines.Where(l => !string.IsNullOrWhiteSpace(l.Text)).Select(l => new TimedText(l.StartMs, l.Text)).ToList();
    }

    /// <summary>NetEase YRC: [lineStart,lineDur](wordStart,wordDur,0)word…, absolute times.</summary>
    public static LyricDoc ParseYrc(string content)
    {
        var lines = new List<LyricLine>();
        foreach (var raw in SplitLines(content))
        {
            var line = raw.Trim();
            if (line.StartsWith('{'))
            {
                var credit = CreditLine(line);
                if (credit != null) lines.Add(credit);
                continue;
            }
            var m = YrcLine.Match(line);
            if (!m.Success) continue;
            var words = YrcWord.Matches(m.Groups[3].Value)
                .Select(w => new LyricWord(long.Parse(w.Groups[1].Value), long.Parse(w.Groups[2].Value), w.Groups[3].Value))
                .Where(w => w.Text.Length > 0).ToList();
            AddWordLine(lines, long.Parse(m.Groups[1].Value), words, m.Groups[3].Value);
        }
        return new LyricDoc(lines.OrderBy(l => l.StartMs).ToList());
    }

    /// <summary>QQ QRC (plain, after decryption): [lineStart,lineDur]word(wordStart,wordDur)…, absolute times.</summary>
    public static LyricDoc ParseQrc(string content)
    {
        var contentMatch = QrcContent.Match(content);
        var body = contentMatch.Success ? UnescapeXml(contentMatch.Groups[1].Value) : content;
        var lines = new List<LyricLine>();
        foreach (var raw in SplitLines(body))
        {
            var m = YrcLine.Match(raw.Trim());
            if (!m.Success) continue;
            var words = QrcWord.Matches(m.Groups[3].Value)
                .Select(w => new LyricWord(long.Parse(w.Groups[2].Value), long.Parse(w.Groups[3].Value), w.Groups[1].Value))
                .Where(w => w.Text.Length > 0).ToList();
            AddWordLine(lines, long.Parse(m.Groups[1].Value), words, m.Groups[3].Value);
        }
        return new LyricDoc(lines.OrderBy(l => l.StartMs).ToList());
    }

    /// <summary>True when decrypted QQ lyrics are word-timed QRC rather than LRC.</summary>
    public static bool IsQrc(string content) =>
        content.Contains("LyricContent=") || Regex.IsMatch(content, @"^\[\d+,\d+\].*\(\d+,\d+\)", RegexOptions.Multiline);

    /// <summary>Kugou KRC (plain, after decryption): [lineStart,lineDur]&lt;offset,dur,0&gt;word…, offsets relative to the line.</summary>
    public static LyricDoc ParseKrc(string content)
    {
        var lines = new List<LyricLine>();
        foreach (var raw in SplitLines(content))
        {
            var m = YrcLine.Match(raw.Trim());
            if (!m.Success) continue;
            var lineStart = long.Parse(m.Groups[1].Value);
            var words = KrcWord.Matches(m.Groups[3].Value)
                .Select(w => new LyricWord(lineStart + long.Parse(w.Groups[1].Value), long.Parse(w.Groups[2].Value), w.Groups[3].Value))
                .Where(w => w.Text.Length > 0).ToList();
            AddWordLine(lines, lineStart, words, m.Groups[3].Value);
        }
        return new LyricDoc(lines.OrderBy(l => l.StartMs).ToList());
    }

    /// <summary>
    /// Start time of every [start,dur] KRC line in file order, and whether it has any text.
    /// KRC translation rows are indexed by this list; romaji rows by its non-blank entries.
    /// </summary>
    public static List<(long Start, bool HasText)> KrcLineStarts(string content)
    {
        var result = new List<(long, bool)>();
        foreach (var raw in SplitLines(content))
        {
            var m = YrcLine.Match(raw.Trim());
            if (!m.Success) continue;
            var text = string.Concat(KrcWord.Matches(m.Groups[3].Value).Select(w => w.Groups[3].Value));
            if (text.Length == 0) text = Regex.Replace(m.Groups[3].Value, @"<\d+,\d+,-?\d+>", "");
            result.Add((long.Parse(m.Groups[1].Value), !string.IsNullOrWhiteSpace(text)));
        }
        return result;
    }

    /// <summary>The base64 [language:…] tag of a KRC file (translation / romaji as JSON), if any.</summary>
    public static string? KrcLanguageTag(string content)
    {
        var m = Regex.Match(content, @"^\[language:([^\]]*)\]", RegexOptions.Multiline);
        var value = m.Success ? m.Groups[1].Value.Trim() : "";
        return value.Length > 0 ? value : null;
    }

    /// <summary>
    /// TTML as used by the AMLL lyric database (inline x-translation / x-roman spans) and by Apple
    /// (translations in head, keyed by itunes:key). Background-vocal spans (x-bg) are left out.
    /// </summary>
    public static LyricDoc ParseTtml(string content)
    {
        var lines = new List<LyricLine>();
        var translation = new List<TimedText>();
        var romaji = new List<TimedText>();
        var headTranslations = TtmlHeadTexts(content, "translation");
        var headRomaji = TtmlHeadTexts(content, "transliteration");
        foreach (Match p in Regex.Matches(content, @"<p\b([^>]*)>([\s\S]*?)</p>"))
        {
            var beginAttr = Attr(p.Groups[1].Value, "begin");
            var pBegin = beginAttr == null ? null : TtmlTimeMs(beginAttr);
            if (pBegin == null) continue;
            var pKey = Attr(p.Groups[1].Value, "itunes:key");
            var body = p.Groups[2].Value;
            var words = new List<LyricWord>();
            string? trans = null, roman = null;
            var plain = new StringBuilder();
            var index = 0;
            var pendingSpace = false;
            while (index < body.Length)
            {
                var open = body.IndexOf("<span", index, StringComparison.Ordinal);
                var gap = open < 0 ? body[index..] : body[index..open];
                var gapText = UnescapeXml(Tags.Replace(gap, ""));
                if (gapText.Length > 0)
                {
                    if (words.Count == 0) plain.Append(gapText);
                    if (string.IsNullOrWhiteSpace(gapText)) pendingSpace = true;
                    else if (words.Count > 0)
                    {
                        var last = words[^1];
                        words[^1] = last with { Text = last.Text + gapText };
                    }
                }
                if (open < 0) break;
                var tagEnd = body.IndexOf('>', open);
                if (tagEnd < 0) break;
                var attrs = body.Substring(open + 5, tagEnd - (open + 5));
                var close = MatchingSpanEnd(body, tagEnd + 1);
                var inner = body[(tagEnd + 1)..close];
                index = Math.Min(body.Length, close + "</span>".Length);
                var role = Attr(attrs, "ttm:role") ?? Attr(attrs, "role");
                switch (role)
                {
                    case "x-translation":
                        trans = UnescapeXml(Tags.Replace(inner, "")).Trim();
                        break;
                    case "x-roman":
                        roman = UnescapeXml(Tags.Replace(inner, "")).Trim();
                        break;
                    case "x-bg":
                        break;
                    default:
                        var b = Attr(attrs, "begin");
                        var e = Attr(attrs, "end");
                        var begin = b == null ? null : TtmlTimeMs(b);
                        var end = e == null ? null : TtmlTimeMs(e);
                        var text = UnescapeXml(Tags.Replace(inner, ""));
                        if (begin != null && end != null && text.Length > 0)
                        {
                            if (pendingSpace && words.Count > 0)
                            {
                                var last = words[^1];
                                words[^1] = last with { Text = last.Text + " " };
                            }
                            words.Add(new LyricWord(begin.Value, Math.Max(1, end.Value - begin.Value), text));
                        }
                        break;
                }
                pendingSpace = false;
            }
            var trimmed = TrimLastWord(words);
            var lineText = (trimmed.Count > 0 ? string.Concat(trimmed.Select(w => w.Text)) : plain.ToString()).Trim();
            if (lineText.Length == 0) continue;
            var start = Math.Min(pBegin.Value, trimmed.Count > 0 ? trimmed[0].StartMs : pBegin.Value);
            lines.Add(new LyricLine(start, trimmed, lineText));
            var t = trans ?? (pKey != null && headTranslations.TryGetValue(pKey, out var ht) ? ht : null);
            if (!string.IsNullOrEmpty(t)) translation.Add(new TimedText(start, t));
            var r = roman ?? (pKey != null && headRomaji.TryGetValue(pKey, out var hr) ? hr : null);
            if (!string.IsNullOrEmpty(r)) romaji.Add(new TimedText(start, r));
        }
        return new LyricDoc(lines.OrderBy(l => l.StartMs).ToList(), translation, romaji);
    }

    private static Dictionary<string, string> TtmlHeadTexts(string content, string block)
    {
        var result = new Dictionary<string, string>();
        foreach (Match b in Regex.Matches(content, $@"<{block}\b[^>]*>([\s\S]*?)</{block}>"))
        {
            foreach (Match t in Regex.Matches(b.Groups[1].Value, @"<text\b([^>]*)>([\s\S]*?)</text>"))
            {
                var key = Attr(t.Groups[1].Value, "for");
                if (key == null) continue;
                var text = UnescapeXml(Tags.Replace(t.Groups[2].Value, "")).Trim();
                if (text.Length > 0) result.TryAdd(key, text);
            }
        }
        return result;
    }

    // ---------- output for Folia ----------

    /// <summary>
    /// Folia's lyric container ([awlrc:lrc:…,tlrc:…,rlrc:…,awlrc:…], from LX Music / Kugou exports):
    /// one string carrying word timing, translation and romaji. Every track uses the same
    /// [mm:ss.mmm] stamps, and translation / romaji are re-keyed to the exact start of their line.
    /// </summary>
    public static string ToFoliaContainer(LyricDoc doc)
    {
        var lines = doc.Lines.Where(l => !string.IsNullOrWhiteSpace(l.Text)).ToList();
        var wordByWord = doc.WordByWord;
        var lrc = new StringBuilder();
        // Line-timed lyrics keep their blank lines: in LRC they mark an instrumental break.
        if (!wordByWord)
        {
            foreach (var line in doc.Lines) lrc.Append(Stamp(line.StartMs)).Append(Clean(line.Text)).Append('\n');
        }
        var awlrc = new StringBuilder();
        var lineStarts = new List<long>(lines.Count);
        for (var index = 0; index < lines.Count; index++)
        {
            var line = lines[index];
            var words = line.Words;
            var start = words.Count > 0 ? Math.Min(line.StartMs, words[0].StartMs) : line.StartMs;
            lineStarts.Add(start);
            if (wordByWord) lrc.Append(Stamp(start)).Append(Clean(line.Text)).Append('\n');
            if (!wordByWord) continue;
            awlrc.Append(Stamp(start));
            if (words.Count == 0)
            {
                var next = index + 1 < lines.Count ? lines[index + 1].StartMs : start + 3000;
                awlrc.Append("<0,").Append(Math.Clamp(next - start, 1, 10_000)).Append('>').Append(Clean(line.Text));
            }
            else
            {
                foreach (var w in words)
                {
                    awlrc.Append('<').Append(Math.Max(0, w.StartMs - start)).Append(',').Append(Math.Max(1, w.DurationMs)).Append('>')
                        .Append(Clean(w.Text));
                }
            }
            awlrc.Append('\n');
        }
        var parts = new List<string> { "lrc:" + B64(lrc.ToString()) };
        var tlrc = AlignedTrack(lineStarts, doc.Translation);
        if (tlrc != null) parts.Add("tlrc:" + B64(tlrc));
        var rlrc = AlignedTrack(lineStarts, doc.Romaji);
        if (rlrc != null) parts.Add("rlrc:" + B64(rlrc));
        if (wordByWord) parts.Add("awlrc:" + B64(awlrc.ToString()));
        return "[awlrc:" + string.Join(",", parts) + "]";
    }

    /// <summary>Each track entry goes to the nearest line start within 1 s (each line takes at most one).</summary>
    private static string? AlignedTrack(List<long> lineStarts, IReadOnlyList<TimedText> track)
    {
        if (track.Count == 0 || lineStarts.Count == 0) return null;
        var assigned = new string?[lineStarts.Count];
        foreach (var entry in track.OrderBy(t => t.StartMs))
        {
            if (string.IsNullOrWhiteSpace(entry.Text) || entry.Text.Trim() == "//") continue;
            var best = -1;
            var bestDiff = long.MaxValue;
            for (var i = 0; i < lineStarts.Count; i++)
            {
                var diff = Math.Abs(lineStarts[i] - entry.StartMs);
                if (diff < bestDiff && assigned[i] == null)
                {
                    bestDiff = diff;
                    best = i;
                }
            }
            if (best >= 0 && bestDiff <= 1000) assigned[best] = entry.Text.Trim();
        }
        if (assigned.All(a => a == null)) return null;
        var output = new StringBuilder();
        for (var i = 0; i < lineStarts.Count; i++)
        {
            if (assigned[i] == null) continue;
            output.Append(Stamp(lineStarts[i])).Append(Clean(assigned[i]!)).Append('\n');
        }
        return output.ToString();
    }

    // ---------- text conversion ----------

    public static bool HasKana(LyricDoc doc) => doc.Lines.Any(l => Kana.IsMatch(l.Text));

    /// <summary>
    /// Applies convert (Simplified → Traditional) to the original lines and the translation.
    /// Japanese lyrics (any kana) keep their original text; romaji is never touched.
    /// </summary>
    public static LyricDoc ConvertChinese(LyricDoc doc, Func<string, string> convert)
    {
        var lines = HasKana(doc)
            ? doc.Lines
            : doc.Lines.Select(l => new LyricLine(l.StartMs, l.Words.Select(w => w with { Text = convert(w.Text) }).ToList(), convert(l.Text))).ToList();
        return new LyricDoc(lines, doc.Translation.Select(t => t with { Text = convert(t.Text) }).ToList(), doc.Romaji);
    }

    // ---------- decryption ----------

    private static readonly byte[] KrcKey =
        { 0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69 };

    /// <summary>Kugou KRC download (base64 content): drop the 4-byte "krc1" header, XOR with the key, inflate.</summary>
    public static string DecryptKrc(string base64)
    {
        var raw = Convert.FromBase64String(base64.Trim());
        if (raw.Length <= 4) throw new FormatException("KRC too short");
        var data = new byte[raw.Length - 4];
        for (var i = 0; i < data.Length; i++) data[i] = (byte)(raw[i + 4] ^ KrcKey[i % KrcKey.Length]);
        return Inflate(data);
    }

    /// <summary>QQ Music QRC (hex): QQ's own 3DES variant on 8-byte blocks, then zlib.</summary>
    public static string DecryptQrc(string hex)
    {
        var clean = hex.Trim();
        if (clean.Length < 16 || clean.Length % 2 != 0) throw new FormatException("QRC hex has odd length");
        var data = Convert.FromHexString(clean);
        var output = new byte[data.Length - data.Length % 8];
        for (var i = 0; i + 8 <= data.Length; i += 8) QrcCipher.DecryptBlock(data, i, output, i);
        return Inflate(output);
    }

    /// <summary>zlib inflate that ignores padding after the end of the stream.</summary>
    private static string Inflate(byte[] data)
    {
        using var input = new MemoryStream(data);
        using var zlib = new ZLibStream(input, CompressionMode.Decompress);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        while (true)
        {
            int n;
            try
            {
                n = zlib.Read(buffer, 0, buffer.Length);
            }
            catch (InvalidDataException) when (output.Length > 0)
            {
                break; // trailing padding after the stream
            }
            if (n <= 0) break;
            output.Write(buffer, 0, n);
        }
        return Encoding.UTF8.GetString(output.ToArray());
    }

    // ---------- helpers ----------

    private static void AddWordLine(List<LyricLine> lines, long lineStart, List<LyricWord> words, string rawBody)
    {
        if (words.Count > 0)
        {
            var text = string.Concat(words.Select(w => w.Text));
            if (!string.IsNullOrWhiteSpace(text)) lines.Add(new LyricLine(Math.Min(lineStart, words[0].StartMs), words, text.Trim()));
        }
        else
        {
            var text = Regex.Replace(rawBody, @"\(\d+,\d+(?:,-?\d+)?\)|<\d+,\d+,-?\d+>", "").Trim();
            if (text.Length > 0) lines.Add(new LyricLine(lineStart, Array.Empty<LyricWord>(), text));
        }
    }

    private static LyricLine? CreditLine(string json)
    {
        var time = JsonTime.Match(json);
        if (!time.Success || !long.TryParse(time.Groups[1].Value, out var t)) return null;
        var text = string.Concat(JsonText.Matches(json).Select(m => UnescapeJson(m.Groups[1].Value))).Trim();
        return text.Length == 0 ? null : new LyricLine(t, Array.Empty<LyricWord>(), text);
    }

    private static List<LyricWord> TrimLastWord(List<LyricWord> words)
    {
        if (words.Count == 0) return words;
        var result = words.Take(words.Count - 1).ToList();
        var last = words[^1];
        result.Add(last with { Text = last.Text.TrimEnd() });
        return result;
    }

    private static long LrcTimeMs(Match m)
    {
        var minutes = long.Parse(m.Groups[1].Value);
        var seconds = long.Parse(m.Groups[2].Value);
        var fraction = m.Groups[3].Value;
        long ms = fraction.Length switch
        {
            0 => 0,
            1 => long.Parse(fraction) * 100,
            2 => long.Parse(fraction) * 10,
            _ => long.Parse(fraction[..3]),
        };
        return minutes * 60_000 + seconds * 1000 + ms;
    }

    /// <summary>TTML clock values: 12.345s, ss.mmm, mm:ss.mmm, hh:mm:ss.mmm.</summary>
    public static long? TtmlTimeMs(string value)
    {
        var v = value.Trim();
        static double? Num(string s) => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? d : null;
        if (v.EndsWith("ms", StringComparison.Ordinal)) return Num(v[..^2]) is { } ms ? (long)ms : null;
        if (v.EndsWith('s')) return Num(v[..^1]) is { } s ? (long)(s * 1000) : null;
        var parts = v.Split(':');
        if (parts.Length == 0 || parts.Length > 3) return null;
        var total = 0.0;
        foreach (var part in parts)
        {
            if (Num(part) is not { } n) return null;
            total = total * 60 + n;
        }
        return (long)Math.Floor(total * 1000 + 0.5); // Java's Math.round
    }

    private static string? Attr(string attrs, string name)
    {
        var m = Regex.Match(attrs, @"(?:^|\s)" + Regex.Escape(name) + @"\s*=\s*""([^""]*)""");
        return m.Success ? m.Groups[1].Value : null;
    }

    /// <summary>Index of the &lt;/span&gt; closing the span whose content starts at from (spans can nest).</summary>
    private static int MatchingSpanEnd(string body, int from)
    {
        var depth = 1;
        var i = from;
        while (i < body.Length)
        {
            var nextOpen = body.IndexOf("<span", i, StringComparison.Ordinal);
            var nextClose = body.IndexOf("</span>", i, StringComparison.Ordinal);
            if (nextClose < 0) return body.Length;
            if (nextOpen >= 0 && nextOpen < nextClose)
            {
                depth++;
                i = nextOpen + 5;
            }
            else
            {
                depth--;
                if (depth == 0) return nextClose;
                i = nextClose + 7;
            }
        }
        return body.Length;
    }

    public static string UnescapeXml(string s)
    {
        if (!s.Contains('&')) return s;
        return Regex.Replace(s, @"&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);", m =>
        {
            var e = m.Groups[1].Value;
            return e switch
            {
                "amp" => "&",
                "lt" => "<",
                "gt" => ">",
                "quot" => "\"",
                "apos" => "'",
                _ => char.ConvertFromUtf32(e.StartsWith("#x") ? Convert.ToInt32(e[2..], 16) : int.Parse(e[1..])),
            };
        });
    }

    private static string UnescapeJson(string s)
    {
        if (!s.Contains('\\')) return s;
        var output = new StringBuilder();
        var i = 0;
        while (i < s.Length)
        {
            var c = s[i];
            if (c == '\\' && i + 1 < s.Length)
            {
                var n = s[i + 1];
                switch (n)
                {
                    case 'n': output.Append('\n'); break;
                    case 't': output.Append('\t'); break;
                    case 'r': break;
                    case 'u':
                        if (i + 5 < s.Length)
                        {
                            output.Append((char)Convert.ToInt32(s.Substring(i + 2, 4), 16));
                            i += 4;
                        }
                        break;
                    default: output.Append(n); break;
                }
                i += 2;
            }
            else
            {
                output.Append(c);
                i++;
            }
        }
        return output.ToString();
    }

    /// <summary>Text inside a track line: no line breaks, and no '&lt;' (it would start a word marker).</summary>
    private static string Clean(string text) => text.Replace('\n', ' ').Replace('\r', ' ').Replace('<', '＜');

    private static string Stamp(long ms)
    {
        var t = Math.Max(0, ms);
        var minutes = t / 60_000;
        var seconds = t / 1000 % 60;
        var millis = t % 1000;
        return "[" + (minutes < 10 ? "0" : "") + minutes.ToString(CultureInfo.InvariantCulture) + ":" + (seconds < 10 ? "0" : "") +
               seconds.ToString(CultureInfo.InvariantCulture) + "." + millis.ToString("000", CultureInfo.InvariantCulture) + "]";
    }

    private static string B64(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));
}

/// <summary>
/// QQ Music's 3DES variant (not standard DES: its own bit order and two altered S-box entries),
/// decrypt direction only. Twin of QrcCipher in LyricFormats.kt.
/// </summary>
internal static class QrcCipher
{
    private static readonly byte[] Key = Encoding.ASCII.GetBytes("!@#)(*$%123ZXC!@!@#)(NHL");

    private static readonly int[][] Sbox =
    {
        new[] { 14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13 },
        new[] { 15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9 },
        new[] { 10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12 },
        new[] { 7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14 },
        new[] { 2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3 },
        new[] { 12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13 },
        new[] { 4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12 },
        new[] { 13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11 },
    };
    private static readonly int[] Ip0 = { 57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7 };
    private static readonly int[] Ip1 = { 56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6 };
    private static readonly int[] Perm = { 15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24 };
    private static readonly int[] KeyShift = { 1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1 };
    private static readonly int[] KeyPermC = { 56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35 };
    private static readonly int[] KeyPermD = { 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3 };
    private static readonly int[] KeyCompression = { 13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36,
        46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31 };

    // Decrypt = key 3 decrypt, key 2 encrypt, key 1 decrypt.
    private static readonly uint[][][] Schedules =
    {
        KeySchedule(Key, 16, decrypt: true),
        KeySchedule(Key, 8, decrypt: false),
        KeySchedule(Key, 0, decrypt: true),
    };

    public static void DecryptBlock(byte[] input, int inOffset, byte[] output, int outOffset)
    {
        var block = new byte[8];
        Array.Copy(input, inOffset, block, 0, 8);
        foreach (var schedule in Schedules) block = Crypt(block, schedule);
        Array.Copy(block, 0, output, outOffset, 8);
    }

    private static uint Bitnum(byte[] a, int offset, int b, int c) =>
        (uint)((a[offset + (b / 32) * 4 + 3 - (b % 32) / 8] >> (7 - b % 8)) & 1) << c;

    private static uint BitnumIntr(uint a, int b, int c) => ((a >> (31 - b)) & 1u) << c;

    private static uint BitnumIntl(uint a, int b, int c) => ((a << b) & 0x80000000u) >> c;

    private static int SboxBit(uint a) => (int)((a & 32) | ((a & 31) >> 1) | ((a & 1) << 4));

    private static byte[] Crypt(byte[] input, uint[][] key)
    {
        uint s0 = 0, s1 = 0;
        for (var i = 0; i < 32; i++)
        {
            s0 |= Bitnum(input, 0, Ip0[i], 31 - i);
            s1 |= Bitnum(input, 0, Ip1[i], 31 - i);
        }
        for (var round = 0; round < 15; round++)
        {
            var previous = s1;
            s1 = F(s1, key[round]) ^ s0;
            s0 = previous;
        }
        s0 = F(s1, key[15]) ^ s0;

        var output = new byte[8];
        for (var b = 0; b < 8; b++)
        {
            var v = BitnumIntr(s1, b, 7) | BitnumIntr(s0, b, 6) | BitnumIntr(s1, b + 8, 5) | BitnumIntr(s0, b + 8, 4) |
                    BitnumIntr(s1, b + 16, 3) | BitnumIntr(s0, b + 16, 2) | BitnumIntr(s1, b + 24, 1) | BitnumIntr(s0, b + 24, 0);
            output[(b + 4) % 8] = (byte)v;
        }
        return output;
    }

    private static uint F(uint state, uint[] key)
    {
        var t1 = BitnumIntl(state, 31, 0) | ((state & 0xf0000000u) >> 1) | BitnumIntl(state, 4, 5) |
                 BitnumIntl(state, 3, 6) | ((state & 0x0f000000u) >> 3) | BitnumIntl(state, 8, 11) |
                 BitnumIntl(state, 7, 12) | ((state & 0x00f00000u) >> 5) | BitnumIntl(state, 12, 17) |
                 BitnumIntl(state, 11, 18) | ((state & 0x000f0000u) >> 7) | BitnumIntl(state, 16, 23);
        var t2 = BitnumIntl(state, 15, 0) | ((state & 0x0000f000u) << 15) | BitnumIntl(state, 20, 5) |
                 BitnumIntl(state, 19, 6) | ((state & 0x00000f00u) << 13) | BitnumIntl(state, 24, 11) |
                 BitnumIntl(state, 23, 12) | ((state & 0x000000f0u) << 11) | BitnumIntl(state, 28, 17) |
                 BitnumIntl(state, 27, 18) | ((state & 0x0000000fu) << 9) | BitnumIntl(state, 0, 23);
        var l = new uint[]
        {
            ((t1 >> 24) & 0xff) ^ key[0], ((t1 >> 16) & 0xff) ^ key[1], ((t1 >> 8) & 0xff) ^ key[2],
            ((t2 >> 24) & 0xff) ^ key[3], ((t2 >> 16) & 0xff) ^ key[4], ((t2 >> 8) & 0xff) ^ key[5],
        };
        var s = ((uint)Sbox[0][SboxBit(l[0] >> 2)] << 28) |
                ((uint)Sbox[1][SboxBit(((l[0] & 0x03) << 4) | (l[1] >> 4))] << 24) |
                ((uint)Sbox[2][SboxBit(((l[1] & 0x0f) << 2) | (l[2] >> 6))] << 20) |
                ((uint)Sbox[3][SboxBit(l[2] & 0x3f)] << 16) |
                ((uint)Sbox[4][SboxBit(l[3] >> 2)] << 12) |
                ((uint)Sbox[5][SboxBit(((l[3] & 0x03) << 4) | (l[4] >> 4))] << 8) |
                ((uint)Sbox[6][SboxBit(((l[4] & 0x0f) << 2) | (l[5] >> 6))] << 4) |
                (uint)Sbox[7][SboxBit(l[5] & 0x3f)];
        uint result = 0;
        for (var i = 0; i < 32; i++) result |= BitnumIntl(s, Perm[i], i);
        return result;
    }

    private static uint[][] KeySchedule(byte[] key, int offset, bool decrypt)
    {
        var schedule = new uint[16][];
        for (var i = 0; i < 16; i++) schedule[i] = new uint[6];
        uint c = 0, d = 0;
        for (var i = 0; i < 28; i++)
        {
            c |= Bitnum(key, offset, KeyPermC[i], 31 - i);
            d |= Bitnum(key, offset, KeyPermD[i], 31 - i);
        }
        for (var i = 0; i < 16; i++)
        {
            var shift = KeyShift[i];
            c = ((c << shift) | (c >> (28 - shift))) & 0xfffffff0u;
            d = ((d << shift) | (d >> (28 - shift))) & 0xfffffff0u;
            var target = decrypt ? 15 - i : i;
            for (var j = 0; j < 6; j++) schedule[target][j] = 0;
            for (var j = 0; j < 24; j++) schedule[target][j / 8] |= BitnumIntr(c, KeyCompression[j], 7 - j % 8);
            for (var j = 24; j < 48; j++) schedule[target][j / 8] |= BitnumIntr(d, KeyCompression[j] - 27, 7 - j % 8);
        }
        return schedule;
    }
}
