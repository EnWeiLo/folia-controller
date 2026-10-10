using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

namespace FoliaController;

/// <summary>
/// Colours taken from the album cover; twin of the Android app's CoverPalette.kt (and of overlay.js's
/// paletteFrom), so every screen tints the same way.
/// Card: a dark tint of the cover's main hue. Highlight: a light, saturated tint for sung words and progress.
/// </summary>
internal sealed record CoverPalette(int Card, int Highlight)
{
    public static readonly CoverPalette Neutral = new(unchecked((int)0xFF1C1C21), unchecked((int)0xFFE8E4D8));

    public Color CardColor => Color.FromArgb(Card);
    public Color HighlightColor => Color.FromArgb(Highlight);

    public static CoverPalette FromImage(byte[] data)
    {
        try
        {
            using var stream = new MemoryStream(data);
            using var image = Image.FromStream(stream);
            using var small = new Bitmap(32, 32, PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(small))
            {
                g.InterpolationMode = InterpolationMode.HighQualityBilinear;
                g.DrawImage(image, 0, 0, 32, 32);
            }
            var pixels = new int[32 * 32];
            var bits = small.LockBits(new Rectangle(0, 0, 32, 32), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            try
            {
                System.Runtime.InteropServices.Marshal.Copy(bits.Scan0, pixels, 0, pixels.Length);
            }
            finally
            {
                small.UnlockBits(bits);
            }
            return From(pixels);
        }
        catch (Exception)
        {
            return Neutral;
        }
    }

    /// <summary>ARGB pixels of a small copy of the cover.</summary>
    public static CoverPalette From(int[] pixels)
    {
        double x = 0, y = 0, weight = 0, satSum = 0;
        foreach (var p in pixels)
        {
            if (((uint)p >> 24) < 128) continue;
            var (h, s, v) = Hsv(p);
            if (v < 0.15) continue;
            // Vivid, bright pixels say most about a cover's colour; greys and shadows say little.
            var w = s * s * v;
            if (w <= 0) continue;
            var angle = h * Math.PI / 180.0;
            x += Math.Cos(angle) * w;
            y += Math.Sin(angle) * w;
            weight += w;
            satSum += s * w;
        }
        if (weight < pixels.Length * 0.02) return Neutral;
        var hue = Math.Atan2(y, x) * 180.0 / Math.PI;
        if (hue < 0) hue += 360.0;
        var sat = satSum / weight;
        var agreement = Math.Sqrt(x * x + y * y) / weight;
        if (agreement < 0.25) return Neutral;
        var cardSat = Math.Min(0.55, sat * 0.8) * Math.Min(1.0, agreement * 1.4);
        var lightSat = Math.Clamp(sat * 0.9, 0.35, 0.6);
        return new CoverPalette(FromHsv(hue, cardSat, 0.2), FromHsv(hue, lightSat, 0.97));
    }

    private static (double H, double S, double V) Hsv(int argb)
    {
        var r = ((argb >> 16) & 0xFF) / 255.0;
        var g = ((argb >> 8) & 0xFF) / 255.0;
        var b = (argb & 0xFF) / 255.0;
        var mx = Math.Max(r, Math.Max(g, b));
        var mn = Math.Min(r, Math.Min(g, b));
        var d = mx - mn;
        double h;
        if (d == 0) h = 0;
        else if (mx == r) h = 60.0 * (((g - b) / d) % 6.0);
        else if (mx == g) h = 60.0 * ((b - r) / d + 2.0);
        else h = 60.0 * ((r - g) / d + 4.0);
        if (h < 0) h += 360.0;
        return (h, mx == 0 ? 0 : d / mx, mx);
    }

    private static int FromHsv(double h, double s, double v)
    {
        var c = v * s;
        var hp = (h % 360.0) / 60.0;
        var xx = c * (1 - Math.Abs(hp % 2 - 1));
        var (r1, g1, b1) = hp switch
        {
            < 1 => (c, xx, 0.0),
            < 2 => (xx, c, 0.0),
            < 3 => (0.0, c, xx),
            < 4 => (0.0, xx, c),
            < 5 => (xx, 0.0, c),
            _ => (c, 0.0, xx),
        };
        var m = v - c;
        int Ch(double value) => Math.Clamp((int)Math.Round((value + m) * 255, MidpointRounding.AwayFromZero), 0, 255);
        return unchecked((int)0xFF000000) | (Ch(r1) << 16) | (Ch(g1) << 8) | Ch(b1);
    }
}
