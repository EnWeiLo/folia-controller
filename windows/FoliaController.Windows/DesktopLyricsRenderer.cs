using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.Text.RegularExpressions;

namespace FoliaController;

/// <summary>What the desktop lyrics show at one moment: the current line (with word timing) and its translation.</summary>
internal sealed record LyricFrame(string Key, string Main, IReadOnlyList<LyricWord> Words, string Sub, bool IsTitle)
{
    public bool IsEmpty => Main.Length == 0 && Sub.Length == 0;
}

internal enum ToolbarButton { None, Prev, PlayPause, Next, Smaller, Bigger, Lock, Close }

internal static class DesktopLyricsModel
{
    /// <summary>The frame for <paramref name="clockMs"/>; null when nothing is playing.</summary>
    public static LyricFrame? FrameAt(DisplayState state, long clockMs)
    {
        if (!state.HasSession) return null;
        var doc = state.Doc;
        var title = new LyricFrame("title", state.Title, Array.Empty<LyricWord>(), state.Artist, IsTitle: true);
        if (doc == null || doc.IsEmpty) return state.Title.Length > 0 ? title : null;

        var lines = doc.Lines;
        var idx = LastAtOrBefore(lines, clockMs);
        if (idx < 0) return title; // the intro, before the first line
        // Several lines with one timestamp: the first is the lyric (a local LRC may put its translation second).
        while (idx > 0 && lines[idx - 1].StartMs == lines[idx].StartMs) idx--;
        var line = lines[idx];

        var last = lines.Count - 1;
        while (last > 0 && string.IsNullOrWhiteSpace(lines[last].Text)) last--;
        if (idx >= last)
        {
            var end = line.Words.Count > 0 ? line.Words[^1].StartMs + line.Words[^1].DurationMs : line.StartMs + 6000;
            if (idx > last || clockMs > end + 3000) return new LyricFrame("end", "", Array.Empty<LyricWord>(), "", false);
        }
        if (string.IsNullOrWhiteSpace(line.Text)) return new LyricFrame($"gap{idx}", "", Array.Empty<LyricWord>(), "", false);

        var sub = "";
        foreach (var t in doc.Translation)
        {
            if (Math.Abs(t.StartMs - line.StartMs) <= 50) { sub = t.Text; break; }
            if (t.StartMs > line.StartMs + 50) break;
        }
        if (sub.Length == 0 && doc.Translation.Count == 0 && idx + 1 < lines.Count && lines[idx + 1].StartMs == line.StartMs)
        {
            sub = lines[idx + 1].Text;
        }
        var words = line.Words.Count > 0 ? line.Words : (IReadOnlyList<LyricWord>)Array.Empty<LyricWord>();
        var main = words.Count > 0 ? string.Concat(words.Select(w => w.Text)).Trim() : line.Text.Trim();
        return new LyricFrame($"line{idx}", main, words, sub.Trim(), false);
    }

    private static int LastAtOrBefore(IReadOnlyList<LyricLine> lines, long clock)
    {
        int lo = 0, hi = lines.Count - 1, found = -1;
        while (lo <= hi)
        {
            var mid = (lo + hi) / 2;
            if (lines[mid].StartMs <= clock) { found = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return found;
    }
}

/// <summary>Everything needed to draw one picture of the desktop lyrics.</summary>
internal sealed record RenderInput(
    LyricFrame? Current,
    LyricFrame? Previous,
    double Transition,       // 0 → 1 while the new line replaces the previous one
    long ClockMs,
    CoverPalette Palette,
    bool Hover,
    bool Playing,
    ToolbarButton HoverButton);

/// <summary>
/// Draws the desktop lyrics into a transparent 32-bit picture: outlined text (readable on any
/// background), sung words in the cover's highlight colour, the translation in small type under it,
/// and a toolbar while the mouse is over it.
/// </summary>
internal sealed class DesktopLyricsRenderer : IDisposable
{
    public const int MinSize = 20;
    public const int MaxSize = 72;

    private static readonly Regex Kana = new(@"[぀-ヿ]", RegexOptions.Compiled);
    private static readonly Regex Hangul = new(@"[가-힯]", RegexOptions.Compiled);
    private static readonly Dictionary<string, FontFamily> Families = new();
    private static readonly object FamilyGate = new();
    private static InstalledFontCollection? Installed;

    private static readonly ToolbarButton[] Buttons =
    {
        ToolbarButton.Prev, ToolbarButton.PlayPause, ToolbarButton.Next,
        ToolbarButton.Smaller, ToolbarButton.Bigger, ToolbarButton.Lock, ToolbarButton.Close,
    };

    private readonly StringFormat _format;
    private Bitmap? _layer;
    private string? _widthsKey;
    private float[] _wordEdges = Array.Empty<float>();

    /// <summary>Display scaling (1 = 100%).</summary>
    public float Scale { get; set; } = 1f;
    /// <summary>Lyric text size in pixels at 100%.</summary>
    public int TextSize { get; set; } = 34;

    public DesktopLyricsRenderer()
    {
        _format = (StringFormat)StringFormat.GenericTypographic.Clone();
        _format.FormatFlags |= StringFormatFlags.MeasureTrailingSpaces | StringFormatFlags.NoWrap;
    }

    private float Em => TextSize * Scale;
    private float SubEm => Math.Max(13f * Scale, Em * 0.5f);
    private float Pad => 10f * Scale;
    private float ToolbarHeight => 34f * Scale;
    private float LyricTop => Pad + ToolbarHeight + 6f * Scale;
    private float MainHeight => Em * 1.45f;

    public Size WindowSize(Rectangle workArea)
    {
        var width = (int)Math.Min(workArea.Width * 0.72, Math.Max(640 * Scale, Em * 30));
        var height = (int)Math.Ceiling(LyricTop + MainHeight + SubEm * 1.6f + Pad);
        return new Size(width, height);
    }

    // ---------- fonts ----------

    private static FontFamily Family(params string[] names)
    {
        lock (FamilyGate)
        {
            var key = string.Join("|", names);
            if (Families.TryGetValue(key, out var cached)) return cached;
            FontFamily? found = null;
            Installed ??= new InstalledFontCollection();
            foreach (var name in names)
            {
                found = Installed.Families.FirstOrDefault(f => f.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
                if (found != null) break;
            }
            found ??= FontFamily.GenericSansSerif;
            Families[key] = found;
            return found;
        }
    }

    private static FontFamily FamilyFor(string text) =>
        Kana.IsMatch(text) ? Family("Yu Gothic UI", "Yu Gothic", "Meiryo UI", "Meiryo", "Microsoft JhengHei UI", "Segoe UI")
        : Hangul.IsMatch(text) ? Family("Malgun Gothic", "Microsoft JhengHei UI", "Segoe UI")
        : Family("Microsoft JhengHei UI", "Microsoft JhengHei", "Microsoft YaHei UI", "Segoe UI");

    private static FontFamily IconFamily => Family("Segoe Fluent Icons", "Segoe MDL2 Assets");

    private static FontStyle Bold(FontFamily family) =>
        family.IsStyleAvailable(FontStyle.Bold) ? FontStyle.Bold : FontStyle.Regular;

    // ---------- drawing ----------

    public void Render(Graphics g, Size size, RenderInput input)
    {
        g.Clear(Color.Transparent);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.CompositingQuality = CompositingQuality.HighQuality;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;

        if (input.Hover)
        {
            using var back = RoundedRect(new RectangleF(0.5f, 0.5f, size.Width - 1, size.Height - 1), 16f * Scale);
            using var brush = new SolidBrush(Color.FromArgb(120, 12, 12, 16));
            g.FillPath(brush, back);
            DrawToolbar(g, size, input);
        }

        var t = Math.Clamp(input.Transition, 0, 1);
        var eased = (float)(1 - Math.Pow(1 - t, 3));
        if (input.Previous != null && t < 1 && !input.Previous.IsEmpty)
        {
            DrawFrameFaded(g, size, input.Previous, input, 1 - eased, -Em * 0.35f * eased);
        }
        if (input.Current != null && !input.Current.IsEmpty)
        {
            if (t >= 1 || input.Previous == null) DrawFrame(g, size, input.Current, input, 0);
            else DrawFrameFaded(g, size, input.Current, input, eased, Em * 0.35f * (1 - eased));
        }
    }

    /// <summary>A frame drawn whole into a side layer, then blended: outline and fill fade together.</summary>
    private void DrawFrameFaded(Graphics g, Size size, LyricFrame frame, RenderInput input, float alpha, float dy)
    {
        if (alpha <= 0.01f) return;
        if (_layer == null || _layer.Width != size.Width || _layer.Height != size.Height)
        {
            _layer?.Dispose();
            _layer = new Bitmap(size.Width, size.Height, PixelFormat.Format32bppPArgb);
        }
        using (var lg = Graphics.FromImage(_layer))
        {
            lg.Clear(Color.Transparent);
            lg.SmoothingMode = SmoothingMode.AntiAlias;
            lg.PixelOffsetMode = PixelOffsetMode.HighQuality;
            DrawFrame(lg, size, frame, input, dy);
        }
        using var attributes = new ImageAttributes();
        attributes.SetColorMatrix(new ColorMatrix { Matrix33 = alpha });
        g.DrawImage(_layer, new Rectangle(0, 0, size.Width, size.Height), 0, 0, size.Width, size.Height, GraphicsUnit.Pixel, attributes);
    }

    private void DrawFrame(Graphics g, Size size, LyricFrame frame, RenderInput input, float dy)
    {
        var maxWidth = size.Width - Pad * 4;
        var cx = size.Width / 2f;
        var mainTop = LyricTop + dy;
        if (frame.Main.Length > 0)
        {
            var family = FamilyFor(frame.Main);
            var em = frame.IsTitle ? Em * 0.8f : Em;
            var sung = frame.Words.Count > 0 ? (Color?)input.Palette.HighlightColor : null;
            DrawText(g, frame.Main, family, Bold(family), em, cx, mainTop + (MainHeight - em * 1.2f) / 2, maxWidth,
                Color.FromArgb(frame.IsTitle ? 235 : 250, 255, 255, 255), sung, frame, input.ClockMs, 0.12f);
        }
        if (frame.Sub.Length > 0)
        {
            var family = FamilyFor(frame.Sub);
            DrawText(g, frame.Sub, family, FontStyle.Regular, SubEm, cx, mainTop + MainHeight + SubEm * 0.1f, maxWidth,
                Color.FromArgb(225, 255, 255, 255), null, null, 0, 0.16f);
        }
    }

    private void DrawText(Graphics g, string text, FontFamily family, FontStyle style, float em, float cx, float top,
        float maxWidth, Color fill, Color? sungColor, LyricFrame? frame, long clockMs, float strokeRatio)
    {
        var width = Measure(g, text, family, style, em);
        if (width > maxWidth && width > 0)
        {
            em *= maxWidth / width;
            width = maxWidth;
        }
        var left = cx - width / 2;
        using var path = new GraphicsPath();
        path.AddString(text, family, (int)style, em, new PointF(left, top), _format);

        using (var shadow = new Pen(Color.FromArgb(60, 0, 0, 0), em * (strokeRatio + 0.1f)) { LineJoin = LineJoin.Round })
        {
            g.DrawPath(shadow, path);
        }
        using (var outline = new Pen(Color.FromArgb(220, 16, 13, 24), em * strokeRatio) { LineJoin = LineJoin.Round })
        {
            g.DrawPath(outline, path);
        }
        using (var brush = new SolidBrush(fill)) g.FillPath(brush, path);

        if (sungColor is { } sung && frame != null)
        {
            var x = SungWidth(g, frame, family, style, em, clockMs);
            if (x > 0)
            {
                var state = g.Save();
                g.SetClip(new RectangleF(left - em, top - em, x + em, em * 3));
                using var brush = new SolidBrush(sung);
                g.FillPath(brush, path);
                g.Restore(state);
            }
        }
    }

    private float Measure(Graphics g, string text, FontFamily family, FontStyle style, float em)
    {
        using var font = new Font(family, em, style, GraphicsUnit.Pixel);
        return g.MeasureString(text, font, PointF.Empty, _format).Width;
    }

    /// <summary>How far (in pixels from the line's left edge) the sung colour has reached.</summary>
    private float SungWidth(Graphics g, LyricFrame frame, FontFamily family, FontStyle style, float em, long clock)
    {
        var words = frame.Words;
        var key = $"{frame.Key}|{frame.Main}|{em:0.0}";
        if (key != _widthsKey)
        {
            _widthsKey = key;
            _wordEdges = new float[words.Count + 1];
            var prefix = "";
            // Leading spaces were trimmed from the line, so measure prefixes the same way.
            for (var i = 0; i < words.Count; i++)
            {
                prefix += words[i].Text;
                _wordEdges[i + 1] = Measure(g, prefix.TrimStart(), family, style, em);
            }
        }
        if (clock < words[0].StartMs) return 0;
        for (var i = 0; i < words.Count; i++)
        {
            var w = words[i];
            if (clock < w.StartMs) return _wordEdges[i];
            if (clock < w.StartMs + w.DurationMs)
            {
                var p = (clock - w.StartMs) / (float)Math.Max(1, w.DurationMs);
                return _wordEdges[i] + (_wordEdges[i + 1] - _wordEdges[i]) * p;
            }
        }
        return _wordEdges[^1] + em;
    }

    // ---------- toolbar ----------

    private float ButtonSize => 32f * Scale;
    private float SeparatorWidth => 12f * Scale;

    private RectangleF ToolbarRect(Size size)
    {
        var width = Buttons.Length * ButtonSize + 2 * SeparatorWidth + 12f * Scale;
        return new RectangleF((size.Width - width) / 2f, Pad, width, ToolbarHeight);
    }

    private IEnumerable<(ToolbarButton Button, RectangleF Rect)> ButtonRects(Size size)
    {
        var bar = ToolbarRect(size);
        var x = bar.Left + 6f * Scale;
        var y = bar.Top + (bar.Height - ButtonSize) / 2;
        foreach (var button in Buttons)
        {
            if (button is ToolbarButton.Smaller or ToolbarButton.Lock) x += SeparatorWidth;
            yield return (button, new RectangleF(x, y, ButtonSize, ButtonSize));
            x += ButtonSize;
        }
    }

    public ToolbarButton HitTest(Point point, Size size)
    {
        foreach (var (button, rect) in ButtonRects(size))
        {
            if (rect.Contains(point)) return button;
        }
        return ToolbarButton.None;
    }

    private void DrawToolbar(Graphics g, Size size, RenderInput input)
    {
        var bar = ToolbarRect(size);
        using (var path = RoundedRect(bar, bar.Height / 2))
        using (var brush = new SolidBrush(Color.FromArgb(215, 24, 24, 30)))
        {
            g.FillPath(brush, path);
        }
        using var separator = new Pen(Color.FromArgb(60, 255, 255, 255), Math.Max(1f, Scale));
        var icons = IconFamily;
        using var iconFont = new Font(icons, 14f * Scale, FontStyle.Regular, GraphicsUnit.Pixel);
        using var center = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        foreach (var (button, rect) in ButtonRects(size))
        {
            if (button is ToolbarButton.Smaller or ToolbarButton.Lock)
            {
                var sx = rect.Left - SeparatorWidth / 2;
                g.DrawLine(separator, sx, rect.Top + rect.Height * 0.28f, sx, rect.Bottom - rect.Height * 0.28f);
            }
            if (button == input.HoverButton)
            {
                using var hover = new SolidBrush(Color.FromArgb(45, 255, 255, 255));
                g.FillEllipse(hover, RectangleF.Inflate(rect, -2f * Scale, -2f * Scale));
            }
            var glyph = button switch
            {
                ToolbarButton.Prev => "",
                ToolbarButton.PlayPause => input.Playing ? "" : "",
                ToolbarButton.Next => "",
                ToolbarButton.Smaller => "",
                ToolbarButton.Bigger => "",
                ToolbarButton.Lock => "",
                _ => "",
            };
            using var brush = new SolidBrush(Color.FromArgb(button == input.HoverButton ? 255 : 220, 255, 255, 255));
            g.DrawString(glyph, iconFont, brush, rect, center);
        }
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        var d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        var path = new GraphicsPath();
        path.AddArc(r.Left, r.Top, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Top, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    public void Dispose()
    {
        _layer?.Dispose();
        _format.Dispose();
    }
}
